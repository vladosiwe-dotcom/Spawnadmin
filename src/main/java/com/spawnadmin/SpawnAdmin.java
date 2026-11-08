package com.spawnadmin;

import com.mojang.logging.LogUtils;
import com.spawnadmin.command.HeavenCommand;
import com.spawnadmin.network.SpawnAdminNetwork;
import com.spawnadmin.registry.ModAttachments;
import com.spawnadmin.registry.ModItems;
import com.spawnadmin.registry.ModSounds;
import com.spawnadmin.server.SkyEventManager;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(SpawnAdmin.MOD_ID)
public final class SpawnAdmin {
    public static final String MOD_ID = "spawnadmin";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SpawnAdmin(IEventBus modBus, ModContainer container) {
        ModItems.ITEMS.register(modBus);
        ModSounds.SOUNDS.register(modBus);
        ModAttachments.ATTACHMENTS.register(modBus);
        modBus.addListener(ModItems::addToCreativeTabs);
        modBus.addListener(SpawnAdminNetwork::register);
        container.registerConfig(ModConfig.Type.SERVER, SpawnAdminConfig.SPEC);

        SkyEventManager.registerListeners(NeoForge.EVENT_BUS);
        NeoForge.EVENT_BUS.addListener(HeavenCommand::register);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
