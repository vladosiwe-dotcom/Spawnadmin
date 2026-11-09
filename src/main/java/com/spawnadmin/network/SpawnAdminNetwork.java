package com.spawnadmin.network;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Payload registration. Client handling goes through {@link #clientSink}, which the client-only
 * entry point replaces, so this class never links client classes on a dedicated server.
 */
public final class SpawnAdminNetwork {
    public interface ClientSink {
        void onSync(SkyEventSyncPayload payload);

        void onEnd(SkyEventEndPayload payload);

        void onPrepare(SkyEventPreparePayload payload);
    }

    private static volatile ClientSink clientSink = new ClientSink() {
        @Override
        public void onSync(SkyEventSyncPayload payload) {
        }

        @Override
        public void onEnd(SkyEventEndPayload payload) {
        }

        @Override
        public void onPrepare(SkyEventPreparePayload payload) {
        }
    };

    public static void setClientSink(ClientSink sink) {
        clientSink = sink;
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        // Optional: clients without the mod may still join; they simply do not see the sky event.
        PayloadRegistrar registrar = event.registrar("1").optional();
        registrar.playToClient(SkyEventSyncPayload.TYPE, SkyEventSyncPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> clientSink.onSync(payload)));
        registrar.playToClient(SkyEventEndPayload.TYPE, SkyEventEndPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> clientSink.onEnd(payload)));
        registrar.playToClient(SkyEventPreparePayload.TYPE, SkyEventPreparePayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> clientSink.onPrepare(payload)));
    }

    /** Sends only to players whose connection negotiated our channel (mod installed). */
    public static void send(ServerPlayer player, CustomPacketPayload payload) {
        if (player.connection != null && player.connection.hasChannel(payload)) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    public static void sendToLevel(ServerLevel level, CustomPacketPayload payload) {
        for (ServerPlayer player : level.players()) {
            send(player, payload);
        }
    }

    private SpawnAdminNetwork() {
    }
}
