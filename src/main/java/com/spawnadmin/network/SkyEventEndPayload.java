package com.spawnadmin.network;

import com.spawnadmin.SpawnAdmin;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** The event is over on the server: clients release every visual resource tied to it. */
public record SkyEventEndPayload(int id) implements CustomPacketPayload {
    public static final Type<SkyEventEndPayload> TYPE = new Type<>(SpawnAdmin.id("sky_event_end"));
    public static final StreamCodec<FriendlyByteBuf, SkyEventEndPayload> CODEC =
            ByteBufCodecs.VAR_INT.map(SkyEventEndPayload::new, SkyEventEndPayload::id).cast();

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
