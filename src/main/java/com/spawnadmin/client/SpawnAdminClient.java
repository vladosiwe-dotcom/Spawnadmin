package com.spawnadmin.client;

import com.spawnadmin.SpawnAdmin;
import com.spawnadmin.network.SpawnAdminNetwork;
import com.spawnadmin.realm.AdminRealm;
import com.spawnadmin.registry.ModItems;
import com.spawnadmin.registry.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.Music;
import net.neoforged.neoforge.client.event.SelectMusicEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
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
        bus.addListener(SpawnAdminClient::selectMusic);
        bus.addListener((RenderGuiLayerEvent.Pre e) -> {
            if (ClientSkyEvents.INSTANCE.hideHud()) {
                e.setCanceled(true);
            }
        });
        bus.addListener((RenderHandEvent e) -> {
            if (ClientSkyEvents.INSTANCE.hideHud()) {
                e.setCanceled(true);
            }
        });
    }

    /** Quiet piano in the realm (even in creative mode); silence while the sky score plays. */
    private static final Music REALM_MUSIC = new Music(ModSounds.MUSIC_ADMIN_REALM, 200, 600, true);

    private static void selectMusic(SelectMusicEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (ClientSkyEvents.INSTANCE.scoreActive()) {
            event.setMusic(null);
        } else if (mc.level != null && mc.level.dimension().equals(AdminRealm.DEFAULT_REALM) && mc.screen == null) {
            event.setMusic(REALM_MUSIC);
        }
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
