package com.spawnadmin.registry;

import com.spawnadmin.SpawnAdmin;
import com.spawnadmin.item.BanishingSealItem;
import com.spawnadmin.item.HeavenHornItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(SpawnAdmin.MOD_ID);

    public static final DeferredItem<HeavenHornItem> HEAVEN_HORN = ITEMS.registerItem("heaven_horn", HeavenHornItem::new,
            new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));
    /** The administrator's tool: sends everyone but operators out of the realm. */
    public static final DeferredItem<BanishingSealItem> BANISHING_SEAL = ITEMS.registerItem("banishing_seal", BanishingSealItem::new,
            new Item.Properties().stacksTo(1).rarity(Rarity.EPIC));

    public static void addToCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES || event.getTabKey() == CreativeModeTabs.OP_BLOCKS) {
            event.accept(HEAVEN_HORN);
            event.accept(BANISHING_SEAL);
        }
    }

    private ModItems() {
    }
}
