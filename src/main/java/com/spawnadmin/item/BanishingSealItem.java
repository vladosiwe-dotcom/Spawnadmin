package com.spawnadmin.item;

import com.spawnadmin.realm.AdminRealm;
import com.spawnadmin.registry.ModSounds;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Seal of Banishment, the administrator's tool. Raised by an operator, it sends every
 * non-operator out of the administrator's realm, each back to the spot where the hand took them.
 * Operators stay. In anyone else's hands it does nothing.
 */
public class BanishingSealItem extends Item {
    private static final int COOLDOWN = 40;

    public BanishingSealItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.success(stack);
        }
        ServerPlayer sp = (ServerPlayer) player;
        if (!sp.hasPermissions(2)) {
            sp.displayClientMessage(Component.translatable("message.spawnadmin.seal_refuses").withStyle(ChatFormatting.GRAY), true);
            return InteractionResultHolder.fail(stack);
        }
        int n = banishAll(sp.server);
        sp.getCooldowns().addCooldown(this, COOLDOWN);
        level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), ModSounds.VANISH_FLASH.get(), SoundSource.PLAYERS, 1.0f, 0.8f);
        sp.displayClientMessage(Component.translatable("message.spawnadmin.banished", n), true);
        return InteractionResultHolder.success(stack);
    }

    /** Sends every non-operator in the realm back home. Returns how many were sent. */
    public static int banishAll(net.minecraft.server.MinecraftServer server) {
        ServerLevel realm = AdminRealm.realmLevel(server);
        if (realm == null) {
            return 0;
        }
        int n = 0;
        for (ServerPlayer other : new ArrayList<>(realm.players())) {
            if (other.hasPermissions(2)) {
                continue;
            }
            AdminRealm.leave(other);
            other.playNotifySound(ModSounds.GRAB_TELEPORT.get(), SoundSource.PLAYERS, 1.0f, 0.9f);
            other.displayClientMessage(Component.translatable("message.spawnadmin.you_were_banished").withStyle(ChatFormatting.GOLD), false);
            n++;
        }
        return n;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.spawnadmin.banishing_seal.desc1").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        tooltip.add(Component.translatable("item.spawnadmin.banishing_seal.desc2").withStyle(ChatFormatting.GOLD));
    }
}
