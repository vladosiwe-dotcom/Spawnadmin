package com.spawnadmin.client;

import com.spawnadmin.network.SkyEventEndPayload;
import com.spawnadmin.network.SkyEventSyncPayload;
import com.spawnadmin.network.SpawnAdminNetwork;
import com.spawnadmin.registry.ModSounds;
import com.spawnadmin.sky.HandRig;
import com.spawnadmin.sky.SkyTimeline;
import com.spawnadmin.sky.geom.V3;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Registry of the sky events this client knows about, plus per-tick client logic. */
final class ClientSkyEvents implements SpawnAdminNetwork.ClientSink {
    static final ClientSkyEvents INSTANCE = new ClientSkyEvents();

    /** Sounds are heard in full within this distance of the event, fading out further away. */
    private static final double HEARING_RANGE = 260.0;

    private final Map<Integer, ClientSkyEvent> events = new LinkedHashMap<>();
    private int idleTicks;

    private ClientSkyEvents() {
    }

    Collection<ClientSkyEvent> all() {
        return events.values();
    }

    @Override
    public void onSync(SkyEventSyncPayload payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        ClientSkyEvent event = events.get(payload.id());
        if (event == null || event.level != level) {
            event = new ClientSkyEvent(payload, level);
            // A late joiner starts silently in whatever phase is running.
            event.lastPhase = SkyTimeline.phaseAt(event.time(0), payload.restoreAt());
            events.put(payload.id(), event);
        } else {
            event.state = payload;
        }
    }

    @Override
    public void onEnd(SkyEventEndPayload payload) {
        events.remove(payload.id());
    }

    void clear() {
        events.clear();
        SceneCopy.release();
        ScreenFlash.clear();
    }

    /** Client tick: phase sounds, cleanup of stale events, release of idle GPU resources. */
    void tick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Iterator<ClientSkyEvent> it = events.values().iterator();
        while (it.hasNext()) {
            ClientSkyEvent event = it.next();
            if (event.level != level) {
                it.remove();
                continue;
            }
            double t = event.time(0);
            if (event.finished(t)) {
                it.remove();
                continue;
            }
            SkyTimeline.Phase phase = SkyTimeline.phaseAt(t, event.restoreAt());
            if (phase != event.lastPhase) {
                onPhase(event, phase);
                event.lastPhase = phase;
            }
        }
        if (events.isEmpty()) {
            if (++idleTicks > 100 && SceneCopy.allocated()) {
                SceneCopy.release();
            }
        } else {
            idleTicks = 0;
        }
    }

    private void onPhase(ClientSkyEvent event, SkyTimeline.Phase phase) {
        SoundEvent sound = switch (phase) {
            case GLOW -> ModSounds.SKY_GLOW.get();
            case CRACK -> ModSounds.SKY_CRACK.get();
            case FALL -> ModSounds.SKY_SHATTER.get();
            case FREEZE -> ModSounds.TIME_STOP.get();
            case HAND -> ModSounds.HAND_APPEAR.get();
            case LIFT -> ModSounds.GRAB_TELEPORT.get();
            case RESTORE -> ModSounds.SKY_RESTORE.get();
            default -> null;
        };
        if (sound != null) {
            playSky(event, sound);
        }
    }

    /** The sky is everywhere: play without attenuation, scaled by distance to the event. */
    private void playSky(ClientSkyEvent event, SoundEvent sound) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        double d = mc.player.position().distanceTo(new Vec3(event.state.cx(), event.state.cy(), event.state.cz()));
        float volume = (float) Math.max(0, Math.min(1, 1.25 - d / HEARING_RANGE));
        if (volume <= 0.01f) {
            return;
        }
        mc.getSoundManager().play(new SimpleSoundInstance(sound.getLocation(), SoundSource.AMBIENT, volume, 1.0f,
                SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true));
    }

    /**
     * Runs at the end of the local player's tick, before its position is sent to the server:
     * keeps the frozen summoner in place and moves them along the hand's path during the lift.
     */
    void onPlayerTick(PlayerTickEvent.Post tick) {
        Minecraft mc = Minecraft.getInstance();
        if (!(tick.getEntity() instanceof LocalPlayer player) || player != mc.player) {
            return;
        }
        for (ClientSkyEvent event : events.values()) {
            if (event.state.targetEntityId() != player.getId() || !event.state.locked()) {
                continue;
            }
            double t = event.time(0);
            double r = event.restoreAt();
            if (t < SkyTimeline.FREEZE || t >= SkyTimeline.TELEPORT || t >= r) {
                continue;
            }
            V3 lock = event.lockLocal();
            V3 now = HandRig.carried(t, r, lock, event.state.lockYaw());
            V3 prev = HandRig.carried(t - 1, r, lock, event.state.lockYaw());
            if (now == null) {
                now = lock;
            }
            if (prev == null) {
                prev = now;
            }
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
            if (t >= SkyTimeline.TELEPORT - 22) {
                ScreenFlash.push((float) SkyTimeline.progress(t, SkyTimeline.TELEPORT - 22, SkyTimeline.TELEPORT - 2));
            }
            if (t >= SkyTimeline.TELEPORT - 3) {
                ScreenFlash.whiteOut(700, 1600);
            }
        }
    }
}
