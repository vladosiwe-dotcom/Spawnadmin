package com.spawnadmin.network;

import com.spawnadmin.SpawnAdmin;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Complete state of one sky event. Sent when the event starts, when the target is locked at
 * the freeze, when the restore is (re)scheduled, and to anyone joining the dimension mid-event.
 * Clients upsert by {@code id}: geometry is generated once from {@code seed} and reused.
 *
 * @param restoreAt tick (relative to start) at which the reverse animation begins
 * @param startYaw  the target's yaw when the first crack appeared: the fracture is turned so the
 *                  first crack opens in front of their eyes
 */
public record SkyEventSyncPayload(int id, long seed, double cx, double cy, double cz, int targetEntityId,
                                  long startTick, int restoreAt, boolean locked, double lx, double ly, double lz,
                                  float lockYaw, float startYaw) implements CustomPacketPayload {
    public static final Type<SkyEventSyncPayload> TYPE = new Type<>(SpawnAdmin.id("sky_event_sync"));

    public static final StreamCodec<FriendlyByteBuf, SkyEventSyncPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.id);
                buf.writeLong(p.seed);
                buf.writeDouble(p.cx);
                buf.writeDouble(p.cy);
                buf.writeDouble(p.cz);
                buf.writeVarInt(p.targetEntityId);
                buf.writeLong(p.startTick);
                buf.writeVarInt(p.restoreAt);
                buf.writeBoolean(p.locked);
                buf.writeDouble(p.lx);
                buf.writeDouble(p.ly);
                buf.writeDouble(p.lz);
                buf.writeFloat(p.lockYaw);
                buf.writeFloat(p.startYaw);
            },
            buf -> new SkyEventSyncPayload(buf.readVarInt(), buf.readLong(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readVarInt(), buf.readLong(), buf.readVarInt(), buf.readBoolean(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readFloat()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
