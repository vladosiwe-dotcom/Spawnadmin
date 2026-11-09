package com.spawnadmin.network;

import com.spawnadmin.SpawnAdmin;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * How long the player's horn still rests (milliseconds, 0 = ready). Lets the client skip the
 * blowing animation and show the time left in the tooltip.
 */
public record HornRestPayload(long remainingMillis) implements CustomPacketPayload {
    public static final Type<HornRestPayload> TYPE = new Type<>(SpawnAdmin.id("horn_rest"));
    public static final StreamCodec<FriendlyByteBuf, HornRestPayload> CODEC =
            ByteBufCodecs.VAR_LONG.map(HornRestPayload::new, HornRestPayload::remainingMillis).cast();

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
