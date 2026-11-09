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
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
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
        com.spawnadmin.item.HeavenHornItem.clientRestRemaining = ClientSkyEvents.INSTANCE::hornRestRemaining;
        modBus.addListener(SpawnAdminClient::registerShaders);
        modBus.addListener(SpawnAdminClient::clientSetup);

        var bus = NeoForge.EVENT_BUS;
        bus.addListener(SkyRenderer::onRenderStage);
        bus.addListener(Cinema::letterbox);
        bus.addListener(ScreenFlash::render);
        bus.addListener(ScreenFlash::renderScreen);
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
        // Camera: subtle shake by stage, the impact of the grip, a lean as the hand looms.
        bus.addListener((ViewportEvent.ComputeCameraAngles e) -> {
            float[] shake = ClientSkyEvents.INSTANCE.cameraShake((float) e.getPartialTick());
            if (shake != null) {
                e.setPitch(e.getPitch() + shake[0]);
                e.setYaw(e.getYaw() + shake[1]);
                e.setRoll(e.getRoll() + shake[2]);
            }
        });
        // Acceleration while being pulled up.
        bus.addListener((ViewportEvent.ComputeFov e) ->
                e.setFOV(e.getFOV() * ClientSkyEvents.INSTANCE.fovMultiplier((float) e.getPartialTick())));
        // The carried body dissolves into the light right before it vanishes.
        bus.addListener((RenderPlayerEvent.Pre e) -> {
            float scale = ClientSkyEvents.INSTANCE.vanishScale(e.getEntity(), e.getPartialTick());
            if (scale < 0.999f) {
                e.getPoseStack().pushPose();
                e.getPoseStack().translate(0, 0.9, 0);
                e.getPoseStack().scale(scale, scale, scale);
                e.getPoseStack().translate(0, -0.9, 0);
                SCALED.add(e.getEntity().getId());
            }
        });
        bus.addListener((RenderPlayerEvent.Post e) -> {
            if (SCALED.remove(e.getEntity().getId())) {
                e.getPoseStack().popPose();
            }
        });
        // Local time stop on this client too: mobs and items near the scene stop animating.
        bus.addListener((EntityTickEvent.Pre e) -> {
            if (e.getEntity().level().isClientSide() && !(e.getEntity() instanceof net.minecraft.world.entity.player.Player)
                    && !ClientSkyEvents.INSTANCE.all().isEmpty() && ClientSkyEvents.INSTANCE.isFrozenAt(e.getEntity().position())) {
                e.setCanceled(true);
            }
        });
    }

    private static final java.util.Set<Integer> SCALED = new java.util.HashSet<>();

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
