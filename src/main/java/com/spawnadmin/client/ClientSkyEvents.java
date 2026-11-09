package com.spawnadmin.client;

import com.spawnadmin.network.SkyEventEndPayload;
import com.spawnadmin.network.SkyEventPreparePayload;
import com.spawnadmin.network.SkyEventSyncPayload;
import com.spawnadmin.network.SpawnAdminNetwork;
import com.spawnadmin.registry.ModSounds;
import com.spawnadmin.server.SkyEventManager;
import com.spawnadmin.sky.SkyScene;
import com.spawnadmin.sky.SkyTimeline;
import com.spawnadmin.sky.geom.DomeShape;
import com.spawnadmin.sky.geom.Fracture;
import com.spawnadmin.sky.geom.V3;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Registry of the sky events this client knows about, plus per-tick client logic. */
final class ClientSkyEvents implements SpawnAdminNetwork.ClientSink {
    static final ClientSkyEvents INSTANCE = new ClientSkyEvents();

    /** Music and sky-wide sounds are heard in full within this distance of the event. */
    private static final double HEARING_RANGE = 260.0;

    private final Map<Integer, ClientSkyEvent> events = new LinkedHashMap<>();
    /** Fractures prepared in the background during the hidden wait, keyed by seed. */
    private final Map<Long, CompletableFuture<Fracture>> prepared = new LinkedHashMap<>();
    /** Keeps the HUD hidden through the white-out after the teleport (the old level is gone by then). */
    private long hudHiddenUntil;
    /** Score that keeps playing after its event was left behind (the target carried away). */
    private SoundInstance lingeringTheme;
    private int idleTicks;
    private final java.util.Set<Integer> gripPlayed = new java.util.HashSet<>();
    /** The reverse swell is timed to end exactly on the snap (its length is 2.5 s). */
    private static final int RISER_AT = SkyTimeline.FIRST_CRACK - 50;
    /** Strength of the pull felt by the local player (0..1), eased for the screen warp. */
    private float pull, pullPrev, pullGoal;

    private ClientSkyEvents() {
    }

    Collection<ClientSkyEvent> all() {
        return events.values();
    }

    // ------------------------------------------------------------------ network

    @Override
    public void onPrepare(SkyEventPreparePayload payload) {
        if (prepared.size() > 8) {
            prepared.remove(prepared.keySet().iterator().next());
        }
        prepared.computeIfAbsent(payload.seed(), seed -> CompletableFuture.supplyAsync(() -> Fracture.generate(seed), Util.backgroundExecutor()));
    }

    /** Wall-clock time until which the local player's horn rests. */
    private long hornRestUntil;

    @Override
    public void onHornRest(com.spawnadmin.network.HornRestPayload payload) {
        hornRestUntil = payload.remainingMillis() > 0 ? System.currentTimeMillis() + payload.remainingMillis() : 0;
    }

    long hornRestRemaining() {
        return Math.max(0, hornRestUntil - System.currentTimeMillis());
    }

    @Override
    public void onSync(SkyEventSyncPayload payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        ClientSkyEvent event = events.get(payload.id());
        if (event == null || event.level != level) {
            CompletableFuture<Fracture> fracture = prepared.remove(payload.seed());
            if (fracture == null) {
                fracture = CompletableFuture.supplyAsync(() -> Fracture.generate(payload.seed()), Util.backgroundExecutor());
            }
            event = new ClientSkyEvent(payload, level, fracture);
            // A late joiner starts silently in whatever phase is running.
            event.lastPhase = SkyTimeline.phaseAt(event.time(0), payload.restoreAt());
            event.riserPlayed = event.time(0) > RISER_AT + 10;
            events.put(payload.id(), event);
        } else {
            event.state = payload;
            if (payload.restoreAt() < SkyTimeline.RESTORE) {
                stopTheme(event); // aborted: the score no longer matches what happens
            }
        }
    }

    @Override
    public void onEnd(SkyEventEndPayload payload) {
        ClientSkyEvent event = events.remove(payload.id());
        if (event != null) {
            stopTheme(event);
        }
        Cinema.forget(payload.id());
        gripPlayed.remove(payload.id());
    }

    void clear() {
        events.values().forEach(ClientSkyEvents::stopTheme);
        events.clear();
        prepared.clear();
        gripPlayed.clear();
        SceneCopy.release();
        ScreenFlash.clear();
        Cinema.forgetAll();
        pull = pullPrev = pullGoal = 0;
        hornRestUntil = 0;
        hudHiddenUntil = 0;
        lingeringTheme = null;
    }

    private static void stopTheme(ClientSkyEvent event) {
        if (event.theme != null) {
            Minecraft.getInstance().getSoundManager().stop(event.theme);
            event.theme = null;
        }
    }

    // ------------------------------------------------------------------ queries

    /** True while a score is playing close enough that it should replace the game music. */
    boolean scoreActive() {
        Minecraft mc = Minecraft.getInstance();
        if (lingeringTheme != null) {
            if (mc.getSoundManager().isActive(lingeringTheme)) {
                return true;
            }
            lingeringTheme = null;
        }
        if (mc.player == null) {
            return false;
        }
        for (ClientSkyEvent event : events.values()) {
            if (event.time(0) >= 0 && distanceTo(event, mc.player.position()) < HEARING_RANGE * 1.2) {
                return true;
            }
        }
        return false;
    }

    /** The target sees nothing but the sky: no HUD from the first crack until they have arrived. */
    boolean hideHud() {
        if (System.currentTimeMillis() < hudHiddenUntil) {
            return true;
        }
        ClientSkyEvent event = localTargetEvent();
        if (event == null) {
            return false;
        }
        double t = event.time(0);
        return t >= SkyTimeline.PRESS && t < SkyTimeline.TELEPORT + 2 && t < event.restoreAt();
    }

    /** Client side of the local time stop: entities near the scene stop animating too. */
    boolean isFrozenAt(Vec3 pos) {
        for (ClientSkyEvent event : events.values()) {
            SkyScene scene = event.scene();
            if (scene == null) continue;
            double t = event.time(0);
            boolean stopped = t >= SkyTimeline.FREEZE && t < Math.min(event.restoreAt(), SkyTimeline.TELEPORT + 20)
                    && event.restoreAt() > SkyTimeline.FREEZE;
            if (stopped && distanceTo(event, pos) < SkyEventManager.FREEZE_RADIUS) {
                return true;
            }
        }
        return false;
    }

    /** Scale of a player model vanishing into the light (1 = normal). */
    float vanishScale(Entity entity, float partial) {
        for (ClientSkyEvent event : events.values()) {
            if (event.state.targetEntityId() == entity.getId() && event.restoreAt() >= SkyTimeline.TELEPORT) {
                double t = event.time(partial);
                return (float) (1.0 - 0.97 * SkyScene.smooth(SkyTimeline.progress(t, SkyTimeline.TELEPORT - 26, SkyTimeline.TELEPORT)));
            }
        }
        return 1f;
    }

    /** Camera shake (degrees) for the local player at render time. */
    float[] cameraShake(float partial) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || events.isEmpty()) {
            return null;
        }
        double secs = System.nanoTime() / 1e9;
        float pitch = 0, yaw = 0, roll = 0;
        for (ClientSkyEvent event : events.values()) {
            double t = event.time(partial);
            if (t < 0 || event.finished(t)) continue;
            boolean target = event.state.targetEntityId() == mc.player.getId();
            double d = distanceTo(event, mc.player.position());
            double near = 1 - SkyScene.smoothstep(40, 140, d);
            double a = 0;
            double fall = t < event.restoreAt() ? Math.exp(-Math.abs(t - SkyTimeline.FALL) / 12.0) : 0;
            double grip = t >= SkyTimeline.GRIP && t < event.restoreAt() ? Math.exp(-(t - SkyTimeline.GRIP) / 7.0) : 0;
            double snap = t >= SkyTimeline.FIRST_CRACK && t < event.restoreAt() ? Math.exp(-(t - SkyTimeline.FIRST_CRACK) / 5.0) : 0;
            if (target) {
                a += 0.06 * SkyScene.smooth(SkyTimeline.progress(t, SkyTimeline.CONNECT, SkyTimeline.FALL));
                a += 0.7 * snap + 1.1 * fall + 1.6 * grip;
                if (t >= SkyTimeline.LIFT && t < SkyTimeline.TELEPORT) {
                    a += 0.7 * SkyTimeline.progress(t, SkyTimeline.LIFT, SkyTimeline.TELEPORT);
                }
                // The hand looms over them: the view leans a little.
                roll += (float) (2.4 * SkyScene.smooth(SkyTimeline.progress(t, SkyTimeline.HAND, SkyTimeline.GRAB))
                        * (1 - SkyScene.smooth(SkyTimeline.progress(t, SkyTimeline.LIFT, SkyTimeline.LIFT + 15))));
            } else {
                a += (0.35 * snap + 0.5 * fall + 0.45 * grip) * near;
            }
            pitch += (float) (a * (Math.sin(secs * 31) * 0.6 + Math.sin(secs * 47 + 1) * 0.4));
            yaw += (float) (a * (Math.sin(secs * 37 + 2) * 0.6 + Math.sin(secs * 23 + 3) * 0.4));
            roll += (float) (a * 0.5 * Math.sin(secs * 29 + 4));
        }
        return new float[]{pitch, yaw, roll};
    }

    /** FOV multiplier: acceleration as the target is pulled up. */
    double fovMultiplier(float partial) {
        ClientSkyEvent event = localTargetEvent();
        if (event == null) {
            return 1.0;
        }
        double t = event.time(partial);
        if (t < SkyTimeline.LIFT || t >= event.restoreAt()) {
            return 1.0;
        }
        return 1.0 + 0.32 * SkyScene.smooth(SkyTimeline.progress(t, SkyTimeline.LIFT + 10, SkyTimeline.TELEPORT));
    }

    /** Screen warp while the local player is being dragged back. */
    double pullWarp(float partial) {
        return pullPrev + (pull - pullPrev) * partial;
    }

    /** Cinematic bars: full for the target, a little thinner for those watching nearby. */
    double letterbox(float partial) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return 0;
        }
        double amount = 0;
        for (ClientSkyEvent event : events.values()) {
            double t = event.time(partial), r = event.restoreAt();
            if (t < 0 || event.finished(t)) continue;
            double in = SkyScene.smooth(SkyTimeline.progress(t, 0, 30));
            double out = t >= r ? 1 - SkyScene.smoothstep(0.15, 0.7, SkyScene.restoreProgress(t, r)) : 1;
            if (event.state.targetEntityId() == mc.player.getId()) {
                amount = Math.max(amount, in * out);
            } else {
                double d = distanceTo(event, mc.player.position());
                amount = Math.max(amount, 0.75 * in * out * (1 - SkyScene.smoothstep(120, 200, d)));
            }
        }
        return amount;
    }

    private ClientSkyEvent localTargetEvent() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return null;
        }
        for (ClientSkyEvent event : events.values()) {
            if (event.state.targetEntityId() == mc.player.getId()) {
                return event;
            }
        }
        return null;
    }

    private static double distanceTo(ClientSkyEvent event, Vec3 pos) {
        return pos.distanceTo(new Vec3(event.state.cx(), event.state.cy(), event.state.cz()));
    }

    private static Vec3 world(ClientSkyEvent event, V3 local) {
        return new Vec3(event.state.cx() + local.x(), event.state.cy() + local.y(), event.state.cz() + local.z());
    }

    // ------------------------------------------------------------------ ticking

    /** Client tick: stage sounds, the carried body on every screen, cleanup of stale events. */
    void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Iterator<ClientSkyEvent> it = events.values().iterator();
        while (it.hasNext()) {
            ClientSkyEvent event = it.next();
            if (event.level != level) {
                if (event.theme != null) {
                    lingeringTheme = event.theme; // carried away: let the score finish its resolution
                }
                it.remove();
                continue;
            }
            double t = event.time(0);
            if (event.finished(t)) {
                it.remove();
                continue;
            }
            if (event.scene() == null) {
                continue;
            }
            SkyTimeline.Phase phase = SkyTimeline.phaseAt(t, event.restoreAt());
            if (phase != event.lastPhase) {
                onPhase(event, phase);
                event.lastPhase = phase;
            }
            if (t >= SkyTimeline.GRIP && t < SkyTimeline.LIFT && event.restoreAt() > SkyTimeline.GRIP && gripPlayed.add(event.id)) {
                V3 held = event.heldPosition(t);
                play(event, ModSounds.GRAB_CLOSE.get(), SoundSource.AMBIENT, held != null ? world(event, held) : null, 1.0f);
            }
            if (!event.riserPlayed && t >= RISER_AT && event.restoreAt() > SkyTimeline.FIRST_CRACK) {
                event.riserPlayed = true;
                play(event, ModSounds.CINE_RISER.get(), SoundSource.AMBIENT, null, 0.9f);
            }
            trackTarget(event, level);
            leashTrail(event, level, t);
            moveRemoteTarget(event, level, t);
            landingEffects(event, level, t);
        }
        pullPrev = pull;
        pull += (pullGoal - pull) * 0.25f;
        pullGoal *= 0.8f;
        if (events.isEmpty()) {
            if (++idleTicks > 100 && SceneCopy.allocated()) {
                SceneCopy.release();
            }
        } else {
            idleTicks = 0;
        }
    }

    /** The hand chases the target until it is caught. */
    private void trackTarget(ClientSkyEvent event, ClientLevel level) {
        Entity entity = level.getEntity(event.state.targetEntityId());
        if (entity == null) {
            return;
        }
        event.updateAim(new V3(entity.getX() - event.state.cx(), entity.getY() - event.state.cy(), entity.getZ() - event.state.cz()));
    }

    /**
     * A target who runs too far is dragged back by something unseen: everyone sees the streaks
     * of light pulling at them, towards the place where they stood.
     */
    private void leashTrail(ClientSkyEvent event, ClientLevel level, double t) {
        if (!leashActive(event, t)) {
            return;
        }
        Entity entity = level.getEntity(event.state.targetEntityId());
        if (entity == null || entity == Minecraft.getInstance().player) {
            return; // the target feels the pull on screen instead (see Cinema)
        }
        double dx = event.state.cx() - entity.getX(), dz = event.state.cz() - entity.getZ();
        double d = Math.hypot(dx, dz);
        if (d <= SkyEventManager.LEASH_RADIUS) {
            return;
        }
        double s = Math.min(1, (d - SkyEventManager.LEASH_RADIUS) / 6.0);
        int n = 2 + (int) (s * 6);
        for (int i = 0; i < n; i++) {
            double a = level.random.nextDouble() * Math.PI * 2, rr = 0.4 + level.random.nextDouble() * 0.6;
            level.addParticle(net.minecraft.core.particles.ParticleTypes.END_ROD,
                    entity.getX() + Math.cos(a) * rr, entity.getY() + 0.2 + level.random.nextDouble() * 1.6, entity.getZ() + Math.sin(a) * rr,
                    dx / d * 0.35 * s, 0.02, dz / d * 0.35 * s);
        }
        if (s > 0.2) {
            level.addParticle(net.minecraft.core.particles.ParticleTypes.REVERSE_PORTAL, entity.getX(), entity.getY() + 1, entity.getZ(),
                    dx / d * 0.6, 0, dz / d * 0.6);
        }
    }

    /** The leash holds from the first sight of the event until the hand has caught the target. */
    static boolean leashActive(ClientSkyEvent event, double t) {
        return t >= SkyTimeline.PRESS && t < SkyTimeline.GRAB && t < event.restoreAt() && !event.state.locked();
    }

    /**
     * Shards hitting the ground: a burst of glass crumbs where each one lands and, for the larger
     * ones, the sound of glass smashing on the ground. Purely visual: no damage, no block changes.
     */
    private void landingEffects(ClientSkyEvent event, ClientLevel level, double t) {
        SkyScene scene = event.scene();
        double r = event.restoreAt();
        double now = SkyTimeline.shardClock(t, r), before = SkyTimeline.shardClock(t - 1, r);
        if (now <= before) {
            return;
        }
        int sounds = 0;
        var glass = new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK,
                net.minecraft.world.level.block.Blocks.GLASS.defaultBlockState());
        for (var s : scene.fracture.shards) {
            if (!s.detaches || s.hovers) continue;
            double land = scene.landingTime(s);
            if (land <= before || land > now) continue;
            V3 at = event.toWorld(s.origin.add(scene.landingOffset(s)));
            double x = event.state.cx() + at.x(), y = event.state.cy() + at.y(), z = event.state.cz() + at.z();
            double size = Math.sqrt(Math.abs(s.area2())) * 115;
            int n = (int) Math.min(40, 6 + size * 3);
            for (int i = 0; i < n; i++) {
                level.addParticle(glass, x + (level.random.nextDouble() - 0.5) * size * 0.6, y + 0.2,
                        z + (level.random.nextDouble() - 0.5) * size * 0.6,
                        (level.random.nextDouble() - 0.5) * 0.4, 0.15 + level.random.nextDouble() * 0.35,
                        (level.random.nextDouble() - 0.5) * 0.4);
            }
            for (int i = 0; i < Math.min(8, (int) size); i++) {
                level.addParticle(net.minecraft.core.particles.ParticleTypes.POOF, x + (level.random.nextDouble() - 0.5) * size * 0.5,
                        y + 0.3, z + (level.random.nextDouble() - 0.5) * size * 0.5, 0, 0.02, 0);
            }
            if (size > 3 && sounds++ < 3) {
                Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(ModSounds.GLASS_LAND.get().getLocation(),
                        SoundSource.AMBIENT, 0.9f, 0.8f + level.random.nextFloat() * 0.4f, SoundInstance.createUnseededRandom(),
                        false, 0, SoundInstance.Attenuation.LINEAR, x, y, z, false));
            }
        }
    }

    /**
     * Observers see the target physically carried: the remote player's body follows the exact
     * same deterministic path as the hand on their screen, instead of lagging server updates.
     */
    private void moveRemoteTarget(ClientSkyEvent event, ClientLevel level, double t) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && event.state.targetEntityId() == mc.player.getId()) {
            return;
        }
        V3 now = event.heldPosition(t);
        if (now == null) {
            return;
        }
        Entity entity = level.getEntity(event.state.targetEntityId());
        if (entity == null) {
            return;
        }
        V3 prev = event.heldPosition(t - 1);
        if (prev == null) prev = now;
        Vec3 w = world(event, now);
        Vec3 p = world(event, prev);
        entity.setPos(w.x, w.y, w.z);
        entity.xo = p.x;
        entity.yo = p.y;
        entity.zo = p.z;
        entity.xOld = p.x;
        entity.yOld = p.y;
        entity.zOld = p.z;
        entity.setDeltaMovement(Vec3.ZERO);
    }

    private void onPhase(ClientSkyEvent event, SkyTimeline.Phase phase) {
        SkyScene scene = event.scene();
        Vec3 impact = world(event, event.toWorld(DomeShape.pointAt(scene.fracture.impact, 0)));
        Vec3 rift = world(event, event.riftHeart());
        switch (phase) {
            case PRESS -> {
                // The sky is pressed in: the glass creaks and groans under the load.
                play(event, ModSounds.GLASS_PRESS.get(), SoundSource.AMBIENT, impact, 1.0f);
                // The world dims: a deep, wide hit opens the scene.
                play(event, ModSounds.CINE_HIT.get(), SoundSource.AMBIENT, null, 1.0f);
                Minecraft.getInstance().getMusicManager().stopPlaying();
                event.theme = play(event, ModSounds.THEME_SUMMONING.get(), SoundSource.MUSIC, null, 1.0f);
            }
            case FIRST_CRACK -> {
                // It snaps: one sharp crack, then the cracks run on.
                play(event, ModSounds.GLASS_SNAP.get(), SoundSource.AMBIENT, impact, 1.0f);
                play(event, ModSounds.SKY_CRACK.get(), SoundSource.AMBIENT, impact, 0.9f);
            }
            case FALL -> {
                // The sky gives way: a flash of light for everyone near enough to see it.
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null && distanceTo(event, mc.player.position()) < 180) {
                    ScreenFlash.pulse(0.55f, 900);
                }
                play(event, ModSounds.SKY_SHATTER.get(), SoundSource.AMBIENT, rift, 1.0f);
                play(event, ModSounds.RIFT_OPEN.get(), SoundSource.AMBIENT, rift, 1.0f);
            }
            case FREEZE -> {
                play(event, ModSounds.TIME_STOP.get(), SoundSource.AMBIENT, null, 0.9f);
                play(event, ModSounds.CLOCK_HALT.get(), SoundSource.AMBIENT, null, 0.8f);
            }
            case HAND -> play(event, ModSounds.HAND_APPEAR.get(), SoundSource.AMBIENT, rift, 1.0f);
            case LIFT -> {
                V3 held = event.heldPosition(SkyTimeline.LIFT);
                play(event, ModSounds.LIFT_WIND.get(), SoundSource.AMBIENT, held != null ? world(event, held) : null, 1.0f);
            }
            case GONE -> play(event, ModSounds.VANISH_FLASH.get(), SoundSource.AMBIENT, rift, 1.0f);
            case RESTORE -> play(event, ModSounds.SKY_RESTORE.get(), SoundSource.AMBIENT, rift, 1.0f);
            default -> {
            }
        }
    }

    /**
     * Plays a scene sound. With a position it is a real 3D source (attenuation from sounds.json);
     * without, it is heard everywhere around the event, scaled by the distance to it.
     */
    private SoundInstance play(ClientSkyEvent event, SoundEvent sound, SoundSource source, Vec3 at, float volume) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return null;
        }
        SoundInstance instance;
        if (at != null) {
            instance = new SimpleSoundInstance(sound.getLocation(), source, volume, 1.0f,
                    SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.LINEAR, at.x, at.y, at.z, false);
        } else {
            double d = distanceTo(event, mc.player.position());
            float v = (float) Math.max(0, Math.min(1, 1.25 - d / HEARING_RANGE)) * volume;
            if (v <= 0.01f) {
                return null;
            }
            instance = new SimpleSoundInstance(sound.getLocation(), source, v, 1.0f,
                    SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true);
        }
        mc.getSoundManager().play(instance);
        return instance;
    }

    /**
     * The pull back: inside {@link SkyEventManager#LEASH_RADIUS} the target moves freely; beyond
     * it an elastic force drags them back towards where they stood, the harder the further they go.
     */
    private void leash(ClientSkyEvent event, LocalPlayer player, double t) {
        if (!leashActive(event, t)) {
            event.pullTicks = 0;
            return;
        }
        double dx = event.state.cx() - player.getX(), dz = event.state.cz() - player.getZ();
        double d = Math.hypot(dx, dz);
        if (d <= SkyEventManager.LEASH_RADIUS) {
            event.pullTicks = 0;
            return;
        }
        double s = Math.min(1, (d - SkyEventManager.LEASH_RADIUS) / 6.0);
        event.pullTicks++;
        double nx = dx / d, nz = dz / d;
        Vec3 v = player.getDeltaMovement();
        // Moving away is cancelled, then the body is pulled back.
        double away = -(v.x * nx + v.z * nz);
        double vx = v.x, vz = v.z;
        if (away > 0) {
            vx += nx * away * (0.6 + 0.4 * s);
            vz += nz * away * (0.6 + 0.4 * s);
        }
        double pullSpeed = 0.08 + 0.22 * s;
        vx += nx * pullSpeed;
        vz += nz * pullSpeed;
        double h = Math.hypot(vx, vz), max = 0.85;
        if (h > max) {
            vx *= max / h;
            vz *= max / h;
        }
        double vy = v.y;
        if (player.horizontalCollision && player.onGround()) {
            vy = 0.45; // dragged over the obstacle
        }
        player.setDeltaMovement(vx, vy, vz);
        pullGoal = Math.max(pullGoal, (float) (0.35 + 0.65 * s));
        long now = player.level().getGameTime();
        if (now - event.lastPullSound > 50) {
            event.lastPullSound = now;
            Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(ModSounds.LEASH_PULL.get(), 1.0f, 0.9f));
        }
    }

    /**
     * Runs at the end of the local player's tick, before its position is sent to the server:
     * keeps the frozen target in place and moves them along the hand's path during the lift.
     */
    void onPlayerTick(PlayerTickEvent.Post tick) {
        Minecraft mc = Minecraft.getInstance();
        if (!(tick.getEntity() instanceof LocalPlayer player) || player != mc.player) {
            return;
        }
        for (ClientSkyEvent event : events.values()) {
            if (event.state.targetEntityId() != player.getId() || event.scene() == null) {
                continue;
            }
            double t = event.time(0);
            leash(event, player, t);
            // While the sky cracks the gaze is drawn gently upward, never forced.
            if (t >= SkyTimeline.CONNECT && t < SkyTimeline.FREEZE && t < event.restoreAt() && player.getXRot() > -14f) {
                player.setXRot(player.getXRot() - 0.25f);
                player.xRotO = player.getXRot();
            }
            V3 now = event.heldPosition(t);
            if (now == null) {
                continue;
            }
            V3 prev = event.heldPosition(t - 1);
            if (prev == null) prev = now;
            double cx = event.state.cx(), cy = event.state.cy(), cz = event.state.cz();
            player.setDeltaMovement(Vec3.ZERO);
            player.resetFallDistance();
            player.noPhysics = t >= SkyTimeline.LIFT;
            player.setPos(cx + now.x(), cy + now.y(), cz + now.z());
            player.xo = cx + prev.x();
            player.yo = cy + prev.y();
            player.zo = cz + prev.z();
            player.xOld = player.xo;
            player.yOld = player.yo;
            player.zOld = player.zo;
            if (t >= SkyTimeline.HAND - 10) {
                // Cinematic assist: the target looks up at the hand reaching for them.
                float pitch = player.getXRot();
                float target = t < SkyTimeline.LIFT ? -50f : -70f;
                player.xRotO = pitch;
                player.setXRot(pitch + (target - pitch) * 0.08f);
            }
            if (t >= SkyTimeline.TELEPORT - 22) {
                ScreenFlash.push((float) SkyTimeline.progress(t, SkyTimeline.TELEPORT - 22, SkyTimeline.TELEPORT - 2));
            }
            if (t >= SkyTimeline.TELEPORT - 3) {
                ScreenFlash.whiteOut(700, 1600);
                hudHiddenUntil = System.currentTimeMillis() + 2600;
            }
        }
    }
}
