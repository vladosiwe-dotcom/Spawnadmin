package com.spawnadmin.network;

import com.spawnadmin.SpawnAdmin;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Sent when the horn is blown, during the hidden wait: carries only the seed, so clients can
 * build the fracture in the background before anything is visible. Reveals nothing on screen.
 */
public record SkyEventPreparePayload(long seed) implements CustomPacketPayload {
    public static final Type<SkyEventPreparePayload> TYPE = new Type<>(SpawnAdmin.id("sky_event_prepare"));
    public static final StreamCodec<FriendlyByteBuf, SkyEventPreparePayload> CODEC =
            StreamCodec.of((buf, p) -> buf.writeLong(p.seed), buf -> new SkyEventPreparePayload(buf.readLong()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
