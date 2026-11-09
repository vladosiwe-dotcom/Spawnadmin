package com.spawnadmin.server;

import com.spawnadmin.network.SkyEventSyncPayload;
import com.spawnadmin.sky.SkyTimeline;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Server-side state of one running sky event. Only state and timing live here, never visuals. */
final class ServerSkyEvent {
    final int id;
    final ServerLevel level;
    final long seed;
    final Vec3 center;
    final UUID target;
    final int targetEntityId;
    final long startTick;

    int restoreAt = SkyTimeline.RESTORE;
    boolean restoreAnnounced;
    Vec3 lockPos;
    float lockYaw;
    boolean teleported;
    boolean aborted;
    boolean grantedFlight;
    /** Started by the horn (not by a command): arriving starts the horn's rest. */
    boolean fromHorn;
    float startYaw;
    SkyTimeline.Phase lastPhase = SkyTimeline.Phase.PRESS;

    ServerSkyEvent(int id, ServerLevel level, long seed, Vec3 center, UUID target, int targetEntityId, long startTick) {
        this.id = id;
        this.level = level;
        this.seed = seed;
        this.center = center;
        this.target = target;
        this.targetEntityId = targetEntityId;
        this.startTick = startTick;
    }

    long elapsed() {
        return level.getGameTime() - startTick;
    }

    SkyTimeline.Phase phase() {
        return SkyTimeline.phaseAt(elapsed(), restoreAt);
    }

    SkyEventSyncPayload toPayload() {
        Vec3 l = lockPos == null ? Vec3.ZERO : lockPos;
        return new SkyEventSyncPayload(id, seed, center.x, center.y, center.z, targetEntityId, startTick, restoreAt,
                lockPos != null, l.x, l.y, l.z, lockYaw, startYaw);
    }
}
