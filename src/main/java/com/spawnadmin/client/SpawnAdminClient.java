package com.spawnadmin.client;

import com.spawnadmin.SpawnAdmin;
import com.spawnadmin.network.SpawnAdminNetwork;
import com.spawnadmin.registry.ModItems;
import net.minecraft.client.renderer.item.ItemProperties;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.io.IOException;
import java.io.UncheckedIOException;

/** Client-only entry point: shaders, rendering, sounds, local player control. */
@Mod(value = SpawnAdmin.MOD_ID, dist = Dist.CLIENT)
public final class SpawnAdminClient {
    public SpawnAdminClient(IEventBus modBus) {
        SpawnAdminNetwork.setClientSink(ClientSkyEvents.INSTANCE);
        modBus.addListener(SpawnAdminClient::registerShaders);
        modBus.addListener(SpawnAdminClient::clientSetup);

        var bus = NeoForge.EVENT_BUS;
        bus.addListener(SkyRenderer::onRenderStage);
        bus.addListener(ScreenFlash::render);
        bus.addListener((ClientTickEvent.Post e) -> ClientSkyEvents.INSTANCE.tick());
        bus.addListener((PlayerTickEvent.Post e) -> {
            if (e.getEntity().level().isClientSide()) {
                ClientSkyEvents.INSTANCE.onPlayerTick(e);
            }
        });
        bus.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> ClientSkyEvents.INSTANCE.clear());
    }

    private static void registerShaders(RegisterShadersEvent event) {
        try {
            ModShaders.register(event);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load Heavenly Summoning shaders", e);
        }
    }

    private static void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ItemProperties.register(ModItems.HEAVEN_HORN.get(), SpawnAdmin.id("tooting"),
                (stack, level, entity, seed) -> entity != null && entity.isUsingItem() && entity.getUseItem() == stack ? 1.0f : 0.0f));
    }
}
