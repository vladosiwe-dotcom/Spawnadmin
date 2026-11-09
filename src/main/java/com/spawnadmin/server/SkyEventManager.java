package com.spawnadmin.server;

import com.spawnadmin.SpawnAdmin;
import com.spawnadmin.SpawnAdminConfig;
import com.spawnadmin.network.SkyEventEndPayload;
import com.spawnadmin.network.SkyEventPreparePayload;
import com.spawnadmin.network.SpawnAdminNetwork;
import com.spawnadmin.realm.AdminRealm;
import com.spawnadmin.registry.ModSounds;
import com.spawnadmin.sky.SkyTimeline;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Authoritative state machine of Heavenly Summoning events.
 *
 * <p>The server decides <em>who</em> is taken and <em>when</em> each stage happens; clients build
 * the spectacle from the synced seed and start tick. Every stage change is re-broadcast as a
 * checkpoint, so all clients agree on the stage and late joiners get the current state. No blocks,
 * entities or world sky are touched. Time is stopped locally (entities near the scene stop
 * ticking), never for the whole server and never for other players.</p>
 */
public final class SkyEventManager {
    /** Running further than this from the (fixed) rift before being caught breaks the summoning. */
    public static final double ESCAPE_RADIUS = 200.0;
    /** The target may move this far from where they stood; beyond it they are pulled back (client side). */
    public static final double LEASH_RADIUS = 10.0;
    /** Beyond this (an ender pearl, a fast mount, a client ignoring the pull) the server drags them back at once. */
    public static final double LEASH_HARD = 36.0;

    private static double horizontal(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    /** Radius around the scene in which non-player entities freeze while time is stopped. */
    public static final double FREEZE_RADIUS = 96.0;

    private record Pending(long fireAt, UUID caller, long seed) {
    }

    /** Hidden waits, keyed by the target. */
    private static final Map<UUID, Pending> PENDING = new LinkedHashMap<>();
    private static final Map<Integer, ServerSkyEvent> EVENTS = new LinkedHashMap<>();
    private static int nextId = 1;
    /** Temporary flight permission so the server does not kick the frozen/lifted target. */
    private static final ResourceLocation FLIGHT_MODIFIER = SpawnAdmin.id("summoning_hold");

    private SkyEventManager() {
    }

    public static void registerListeners(IEventBus bus) {
        bus.addListener(SkyEventManager::onServerTick);
        bus.addListener(SkyEventManager::onPlayerTick);
        bus.addListener(SkyEventManager::onEntityTick);
        bus.addListener(SkyEventManager::onIncomingDamage);
        bus.addListener(SkyEventManager::onLogin);
        bus.addListener(SkyEventManager::onChangedDimension);
        bus.addListener(SkyEventManager::onRespawn);
        bus.addListener(SkyEventManager::onServerStopped);
    }

    // ------------------------------------------------------------------ public API

    /** True while the player waits for, or is the target of, a summoning. */
    public static boolean isBusy(ServerPlayer player) {
        return PENDING.containsKey(player.getUUID()) || findByTarget(player.getUUID()) != null;
    }

    /** Self-summon: the horn blower is also the target. */
    public static void scheduleSummon(ServerPlayer player) {
        scheduleSummon(player, player);
    }

    /**
     * Horn call on {@code target}. Nothing becomes visible for the configured delay; the clients
     * only receive the seed so they can prepare the fracture in the background. The clock that
     * counts the wait is heard by {@code caller} alone, so the target has no warning.
     */
    public static void scheduleSummon(ServerPlayer target, ServerPlayer caller) {
        long seed = target.getRandom().nextLong();
        long fireAt = target.server.getTickCount() + SpawnAdminConfig.HIDDEN_DELAY_SECONDS.get() * 20L;
        PENDING.put(target.getUUID(), new Pending(fireAt, caller.getUUID(), seed));
        SpawnAdminNetwork.sendToLevel(target.serverLevel(), new SkyEventPreparePayload(seed));
    }

    /** Starts the visible part immediately. Returns false if it could not start. */
    public static boolean startNow(ServerPlayer player) {
        Pending pending = PENDING.remove(player.getUUID());
        if (findByTarget(player.getUUID()) != null) {
            return false;
        }
        ServerLevel level = player.serverLevel();
        if (AdminRealm.isInRealm(player)) {
            player.displayClientMessage(Component.translatable("message.spawnadmin.already_in_realm"), true);
            return false;
        }
        long running = EVENTS.values().stream().filter(e -> e.level == level).count();
        if (running >= SpawnAdminConfig.MAX_EVENTS_PER_DIMENSION.get()) {
            player.displayClientMessage(Component.translatable("message.spawnadmin.sky_busy"), true);
            return false;
        }
        long seed = pending != null ? pending.seed() : level.getRandom().nextLong();
        if (pending != null) {
            // The hour has come: only the one who blew the horn hears the clock strike.
            ServerPlayer caller = player.server.getPlayerList().getPlayer(pending.caller());
            if (caller != null) {
                caller.playNotifySound(ModSounds.HOUR_STRIKE.get(), SoundSource.AMBIENT, 0.8f, 1.0f);
            }
        }
        ServerSkyEvent event = new ServerSkyEvent(nextId++, level, seed, player.position(),
                player.getUUID(), player.getId(), level.getGameTime());
        event.startYaw = player.getYRot();
        EVENTS.put(event.id, event);
        broadcast(event);
        SpawnAdmin.LOGGER.info("Heavenly Summoning #{} started for {} in {}", event.id, player.getName().getString(),
                level.dimension().location());
        return true;
    }

    /** Aborts every event in a level; the sky reassembles from wherever it is. */
    public static int cancelAll(ServerLevel level) {
        int count = 0;
        for (ServerSkyEvent event : EVENTS.values()) {
            if (event.level == level && cancelEvent(event)) {
                count++;
            }
        }
        PENDING.keySet().removeIf(uuid -> {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(uuid);
            return p == null || p.level() == level;
        });
        return count;
    }

    /** Cancels one player's pending or running summoning. */
    public static boolean cancel(ServerPlayer player) {
        boolean pending = PENDING.remove(player.getUUID()) != null;
        ServerSkyEvent event = findByTarget(player.getUUID());
        return (event != null && cancelEvent(event)) || pending;
    }

    private static boolean cancelEvent(ServerSkyEvent event) {
        if (event.aborted || event.teleported || event.elapsed() >= event.restoreAt) {
            return false;
        }
        abort(event, "cancelled");
        return true;
    }

    public static int activeCount() {
        return EVENTS.size();
    }

    /** Read-only view for tests and diagnostics: is a summoning running for this player? */
    public static boolean hasEvent(UUID player) {
        return findByTarget(player) != null;
    }

    /** Restore tick of the player's event, or -1. */
    public static int restoreAtFor(UUID player) {
        ServerSkyEvent event = findByTarget(player);
        return event == null ? -1 : event.restoreAt;
    }

    /** True if non-player entities at {@code pos} in {@code level} are frozen by a scene. */
    public static boolean isTimeFrozenAt(ServerLevel level, Vec3 pos) {
        for (ServerSkyEvent event : EVENTS.values()) {
            if (event.level == level && timeStopped(event) && event.center.distanceToSqr(pos) < FREEZE_RADIUS * FREEZE_RADIUS) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ ticking

    private static void onServerTick(ServerTickEvent.Post tick) {
        MinecraftServer server = tick.getServer();
        long now = server.getTickCount();
        Iterator<Map.Entry<UUID, Pending>> pending = PENDING.entrySet().iterator();
        List<ServerPlayer> ready = new ArrayList<>();
        while (pending.hasNext()) {
            Map.Entry<UUID, Pending> e = pending.next();
            Pending p = e.getValue();
            ServerPlayer target = server.getPlayerList().getPlayer(e.getKey());
            if (target == null || !target.isAlive()) {
                pending.remove();
                continue;
            }
            if (now < p.fireAt()) {
                tickClock(server, p.caller(), p.fireAt() - now);
                continue;
            }
            ready.add(target);
        }
        for (ServerPlayer target : ready) {
            if (!startNow(target)) {
                PENDING.remove(target.getUUID());
            }
        }

        Iterator<ServerSkyEvent> it = EVENTS.values().iterator();
        while (it.hasNext()) {
            ServerSkyEvent event = it.next();
            if (tickEvent(server, event)) {
                SpawnAdminNetwork.sendToLevel(event.level, new SkyEventEndPayload(event.id));
                it.remove();
            }
        }
    }

    /** The clock counting the hidden wait, heard only by the one who blew the horn. */
    private static void tickClock(MinecraftServer server, UUID callerId, long remaining) {
        if (remaining % 20 != 0) {
            return;
        }
        ServerPlayer caller = server.getPlayerList().getPlayer(callerId);
        if (caller == null) {
            return;
        }
        boolean tock = (remaining / 20) % 2 == 0;
        float volume = remaining <= 60 ? 1.0f : 0.7f;
        caller.playNotifySound((tock ? ModSounds.CLOCK_TOCK : ModSounds.CLOCK_TICK).get(), SoundSource.AMBIENT, volume, 1.0f);
    }

    /** @return true when the event is finished and must be removed. */
    private static boolean tickEvent(MinecraftServer server, ServerSkyEvent event) {
        long t = event.elapsed();
        ServerPlayer target = server.getPlayerList().getPlayer(event.target);

        if (!event.teleported && !event.aborted && t < SkyTimeline.TELEPORT) {
            if (target == null) {
                abort(event, "target left the game");
            } else if (!target.isAlive()) {
                abort(event, "target died");
            } else if (target.level() != event.level) {
                abort(event, "target changed dimension");
            } else if (event.lockPos != null && t < SkyTimeline.LIFT && target.position().distanceTo(event.lockPos) > 8.0) {
                abort(event, "target was teleported away");
            } else if (event.lockPos == null && horizontal(target.position(), event.center) > ESCAPE_RADIUS) {
                abort(event, "target escaped the sky");
            } else if (event.lockPos == null && t < SkyTimeline.GRAB && horizontal(target.position(), event.center) > LEASH_HARD) {
                pullBack(event, target);
            }
        }

        if (!event.aborted && !event.teleported && target != null) {
            // The target may run until the hand catches them: they are locked where they are at GRAB.
            if (t >= SkyTimeline.GRAB && event.lockPos == null) {
                lockTarget(event, target);
            }
            if (event.lockPos != null && t < SkyTimeline.TELEPORT) {
                target.setDeltaMovement(Vec3.ZERO);
                target.resetFallDistance();
            }
            if (t >= SkyTimeline.TELEPORT) {
                carryAway(event, target);
            }
        }

        // Stage checkpoints: every client is told the server's view of the current stage.
        SkyTimeline.Phase phase = event.phase();
        if (phase != event.lastPhase) {
            event.lastPhase = phase;
            broadcast(event);
        }
        if (!event.restoreAnnounced && t >= event.restoreAt) {
            event.restoreAnnounced = true;
            broadcast(event);
        }
        return t >= event.restoreAt + SkyTimeline.RESTORE_LENGTH;
    }

    /** Something unseen drags the runaway back to the edge of the leash, facing the rift. */
    private static void pullBack(ServerSkyEvent event, ServerPlayer target) {
        Vec3 off = target.position().subtract(event.center);
        double h = Math.hypot(off.x, off.z);
        double k = (LEASH_RADIUS + 2) / Math.max(h, 1e-6);
        double x = event.center.x + off.x * k, z = event.center.z + off.z * k;
        int top = event.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                net.minecraft.util.Mth.floor(x), net.minecraft.util.Mth.floor(z));
        double y = Math.max(top, Math.min(target.getY(), event.center.y + 4));
        target.stopRiding();
        target.connection.teleport(x, y, z, target.getYRot(), target.getXRot());
        target.setDeltaMovement(Vec3.ZERO);
        target.resetFallDistance();
        target.playNotifySound(ModSounds.LEASH_PULL.get(), SoundSource.PLAYERS, 1.0f, 0.8f);
        SpawnAdmin.LOGGER.info("Heavenly Summoning #{}: {} pulled back", event.id, target.getName().getString());
    }

    private static void lockTarget(ServerSkyEvent event, ServerPlayer target) {
        event.lockPos = target.position();
        event.lockYaw = target.getYRot();
        AttributeInstance flight = target.getAttribute(NeoForgeMod.CREATIVE_FLIGHT);
        if (flight != null && !flight.hasModifier(FLIGHT_MODIFIER)) {
            flight.addTransientModifier(new AttributeModifier(FLIGHT_MODIFIER, 1.0, AttributeModifier.Operation.ADD_VALUE));
            event.grantedFlight = true;
        }
        target.connection.teleport(event.lockPos.x, event.lockPos.y, event.lockPos.z, target.getYRot(), target.getXRot());
        broadcast(event);
    }

    private static void carryAway(ServerSkyEvent event, ServerPlayer target) {
        event.teleported = true;
        revokeFlight(event, target);
        target.noPhysics = false;
        Vec3 takenFrom = event.lockPos != null ? event.lockPos : event.center;
        if (AdminRealm.enter(target, takenFrom)) {
            SpawnAdmin.LOGGER.info("Heavenly Summoning #{} carried {} to {}", event.id, target.getName().getString(),
                    AdminRealm.realmKey().location());
        } else {
            target.displayClientMessage(Component.translatable("message.spawnadmin.no_realm"), false);
        }
        broadcast(event);
    }

    private static void abort(ServerSkyEvent event, String reason) {
        event.aborted = true;
        long t = event.elapsed();
        event.restoreAt = (int) Math.max(t + 1, SkyTimeline.SPREAD);
        event.restoreAnnounced = false;
        ServerPlayer target = event.level.getServer().getPlayerList().getPlayer(event.target);
        if (target != null) {
            revokeFlight(event, target);
            target.noPhysics = false;
            if (event.lockPos != null) {
                target.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 200, 0));
            }
        }
        SpawnAdmin.LOGGER.info("Heavenly Summoning #{} aborted at tick {}: {}", event.id, t, reason);
    }

    private static void revokeFlight(ServerSkyEvent event, ServerPlayer target) {
        if (event.grantedFlight) {
            event.grantedFlight = false;
            AttributeInstance flight = target.getAttribute(NeoForgeMod.CREATIVE_FLIGHT);
            if (flight != null) {
                flight.removeModifier(FLIGHT_MODIFIER);
            }
        }
    }

    private static boolean timeStopped(ServerSkyEvent event) {
        long t = event.elapsed();
        return !event.aborted && t >= SkyTimeline.FREEZE && t < Math.min(event.restoreAt, SkyTimeline.TELEPORT + 20);
    }

    // ------------------------------------------------------------------ entity hooks

    /**
     * While the hand lifts the target, the client drives the player's position, possibly through
     * terrain. {@code noPhysics} is reset at the start of every player tick, so it is set again
     * after the tick to keep the server's "moved wrongly" check out of the way.
     */
    private static void onPlayerTick(PlayerTickEvent.Post tick) {
        if (!(tick.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ServerSkyEvent event = findByTarget(player.getUUID());
        if (event == null || event.aborted || event.teleported) {
            return;
        }
        long t = event.elapsed();
        if (t >= SkyTimeline.LIFT && t < SkyTimeline.TELEPORT) {
            player.noPhysics = true;
        }
    }

    /** Local time stop: mobs, items, projectiles and falling blocks near the scene stand still. */
    private static void onEntityTick(EntityTickEvent.Pre tick) {
        Entity entity = tick.getEntity();
        if (entity instanceof Player || EVENTS.isEmpty() || !(entity.level() instanceof ServerLevel level)) {
            return;
        }
        if (isTimeFrozenAt(level, entity.position())) {
            tick.setCanceled(true);
        }
    }

    private static void onIncomingDamage(LivingIncomingDamageEvent damage) {
        if (!(damage.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ServerSkyEvent event = findByTarget(player.getUUID());
        if (event != null && !event.aborted && event.elapsed() >= SkyTimeline.FREEZE) {
            damage.setCanceled(true);
        }
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        syncLevelTo(e.getEntity());
    }

    private static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        syncLevelTo(e.getEntity());
    }

    private static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        syncLevelTo(e.getEntity());
    }

    private static void onServerStopped(ServerStoppedEvent e) {
        PENDING.clear();
        EVENTS.clear();
    }

    /** Late joiners receive the full state of every event running in their dimension. */
    private static void syncLevelTo(Player player) {
        if (!(player instanceof ServerPlayer sp)) {
            return;
        }
        for (ServerSkyEvent event : EVENTS.values()) {
            if (event.level == sp.level()) {
                SpawnAdminNetwork.send(sp, event.toPayload());
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void broadcast(ServerSkyEvent event) {
        SpawnAdminNetwork.sendToLevel(event.level, event.toPayload());
    }

    private static ServerSkyEvent findByTarget(UUID uuid) {
        for (ServerSkyEvent event : EVENTS.values()) {
            if (event.target.equals(uuid)) {
                return event;
            }
        }
        return null;
    }
}
