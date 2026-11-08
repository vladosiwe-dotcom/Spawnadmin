package com.spawnadmin.client;

import com.spawnadmin.network.SkyEventSyncPayload;
import com.spawnadmin.sky.SkyScene;
import com.spawnadmin.sky.SkyTimeline;
import com.spawnadmin.sky.geom.Fracture;
import com.spawnadmin.sky.geom.V3;
import net.minecraft.client.multiplayer.ClientLevel;

/** Client-side mirror of one sky event: the synced state plus the generated scene. */
final class ClientSkyEvent {
    final int id;
    final ClientLevel level;
    SkyEventSyncPayload state;
    final SkyScene scene;
    final SkyScene.ShardPose[] poses;
    final double[] free;
    SkyTimeline.Phase lastPhase;
    /** The score playing for this event, stopped if the event is aborted. */
    net.minecraft.client.resources.sounds.SoundInstance theme;

    ClientSkyEvent(SkyEventSyncPayload state, ClientLevel level) {
        this.id = state.id();
        this.level = level;
        this.state = state;
        this.scene = new SkyScene(Fracture.generate(state.seed()));
        int n = scene.fracture.shards.size();
        this.poses = new SkyScene.ShardPose[n];
        for (int i = 0; i < n; i++) {
            poses[i] = new SkyScene.ShardPose();
        }
        this.free = new double[n];
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

    V3 lockLocal() {
        return new V3(state.lx() - state.cx(), state.ly() - state.cy(), state.lz() - state.cz());
    }

    /** Updates every shard pose for time {@code t}. */
    void evaluate(double t) {
        double r = restoreAt();
        for (int i = 0; i < poses.length; i++) {
            scene.shardPose(scene.fracture.shards.get(i), t, r, poses[i]);
            free[i] = poses[i].free;
        }
    }
}
