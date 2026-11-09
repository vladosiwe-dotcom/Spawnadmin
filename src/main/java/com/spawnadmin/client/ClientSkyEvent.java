package com.spawnadmin.client;

import com.spawnadmin.network.SkyEventSyncPayload;
import com.spawnadmin.sky.HandRig;
import com.spawnadmin.sky.SkyScene;
import com.spawnadmin.sky.SkyTimeline;
import com.spawnadmin.sky.geom.DebrisData;
import com.spawnadmin.sky.geom.DomeShape;
import com.spawnadmin.sky.geom.Fracture;
import com.spawnadmin.sky.geom.V2;
import com.spawnadmin.sky.geom.V3;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;

import java.util.concurrent.CompletableFuture;

/**
 * Client-side mirror of one sky event: the synced state plus the generated scene.
 *
 * <p>The fracture is generated in the background (usually already during the hidden wait, from
 * the prepare payload). It is built facing +X; {@link #toWorld} turns it towards the target's
 * yaw at the first crack, so the first crack opens in front of their eyes.</p>
 */
final class ClientSkyEvent {
    final int id;
    final ClientLevel level;
    SkyEventSyncPayload state;
    private final CompletableFuture<Fracture> pending;
    private SkyScene scene;
    SkyScene.ShardPose[] poses;
    double[] free;
    SkyTimeline.Phase lastPhase;
    /** The score playing for this event, stopped if the event is aborted. */
    SoundInstance theme;
    /** Rotation of the fracture frame into world axes. */
    final double cos, sin;

    ClientSkyEvent(SkyEventSyncPayload state, ClientLevel level, CompletableFuture<Fracture> fracture) {
        this.id = state.id();
        this.level = level;
        this.state = state;
        this.pending = fracture;
        // Fracture +X must point where the target looked: look = (-sin yaw, 0, cos yaw).
        double yaw = Math.toRadians(state.startYaw());
        double ang = Math.atan2(Math.cos(yaw), -Math.sin(yaw));
        this.cos = Math.cos(ang);
        this.sin = Math.sin(ang);
    }

    /** The generated scene, or null while the background generation is still running. */
    SkyScene scene() {
        if (scene == null && pending.isDone() && !pending.isCompletedExceptionally()) {
            scene = new SkyScene(pending.join());
            int n = scene.fracture.shards.size();
            poses = new SkyScene.ShardPose[n];
            for (int i = 0; i < n; i++) {
                poses[i] = new SkyScene.ShardPose();
            }
            free = new double[n];
        }
        return scene;
    }

    /** Event-relative time in ticks, including the partial tick for smooth rendering. */
    double time(float partialTick) {
        return level.getGameTime() - state.startTick() + partialTick;
    }

    double restoreAt() {
        return state.restoreAt();
    }

    boolean finished(double t) {
        return t >= state.restoreAt() + SkyTimeline.RESTORE_LENGTH + 20;
    }

    // ------------------------------------------------------------------ frames

    /** Fracture frame -> world axes (both relative to the event center). */
    V3 toWorld(V3 p) {
        return new V3(p.x() * cos - p.z() * sin, p.y(), p.x() * sin + p.z() * cos);
    }

    /** Rotates (x, z) in place into world axes; y is unchanged. */
    void toWorld(double[] p) {
        double x = p[0], z = p[2];
        p[0] = x * cos - z * sin;
        p[2] = x * sin + z * cos;
    }

    /** Lock position relative to the event center (world axes). */
    V3 lockLocal() {
        return new V3(state.lx() - state.cx(), state.ly() - state.cy(), state.lz() - state.cz());
    }

    /** Heart of the rift: beyond the dome, above the first impact (world axes, relative). */
    V3 riftHeart() {
        SkyScene s = scene();
        if (s == null) {
            return HandRig.defaultRiftHeart();
        }
        V2 c = s.fracture.impact;
        return toWorld(DomeShape.pointAt(c, 34.0));
    }

    /** Updates every shard pose for time {@code t}. */
    void evaluate(double t) {
        double r = restoreAt();
        for (int i = 0; i < poses.length; i++) {
            scene.shardPose(scene.fracture.shards.get(i), t, r, poses[i]);
            free[i] = poses[i].free;
        }
    }

    /** Carried position (relative), the lock during the freeze, or null when not held. */
    V3 heldPosition(double t) {
        if (!state.locked() || t < SkyTimeline.FREEZE || t >= SkyTimeline.TELEPORT || t >= restoreAt()) {
            return null;
        }
        V3 lock = lockLocal();
        V3 c = HandRig.carried(t, restoreAt(), lock, state.lockYaw(), riftHeart());
        return c != null ? c : lock;
    }

    static boolean chip(DebrisData d) {
        return d.isChip();
    }
}
