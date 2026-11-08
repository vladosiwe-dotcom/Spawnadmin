package com.spawnadmin.network;

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
    }

    private static volatile ClientSink clientSink = new ClientSink() {
        @Override
        public void onSync(SkyEventSyncPayload payload) {
        }

        @Override
        public void onEnd(SkyEventEndPayload payload) {
        }
    };

    public static void setClientSink(ClientSink sink) {
        clientSink = sink;
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(SkyEventSyncPayload.TYPE, SkyEventSyncPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> clientSink.onSync(payload)));
        registrar.playToClient(SkyEventEndPayload.TYPE, SkyEventEndPayload.CODEC,
                (payload, ctx) -> ctx.enqueueWork(() -> clientSink.onEnd(payload)));
    }

    private SpawnAdminNetwork() {
    }
}
