package com.spawnadmin.item;

import com.spawnadmin.SpawnAdminConfig;
import com.spawnadmin.realm.AdminRealm;
import com.spawnadmin.registry.ModAttachments;
import com.spawnadmin.registry.ModSounds;
import com.spawnadmin.server.SkyEventManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Horn of the Heavens. The one who blows it calls the heavens upon themselves: after a silent
 * wait the sky cracks, breaks open and a hand of light carries them into the administrator's
 * realm. It can only be entered through the full animation; there is no shortcut. Once the
 * horn has carried a player there, it rests for {@link SpawnAdminConfig#HORN_REST_MINUTES}
 * (5 hours by default, real time, saved with the player). Inside the realm it sends the player
 * back to where the hand took them, at any time.
 */
public class HeavenHornItem extends Item {
    private static final int USE_TICKS = 60;
    private static final int RETURN_COOLDOWN = 40;
    /** Short pause after every blow, so the horn is not spammed. */
    private static final int BLOW_COOLDOWN = 60;

    public HeavenHornItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            // A resting horn is not raised at all (the server still says how long it rests).
            if (!inRealmDimension(level) && (clientRestRemaining.getAsLong() > 0 || !hasSky(level))) {
                return InteractionResultHolder.fail(stack);
            }
            player.startUsingItem(hand);
            return InteractionResultHolder.consume(stack);
        }
        ServerPlayer sp = (ServerPlayer) player;
        if (AdminRealm.isInRealm(sp)) {
            AdminRealm.leave(sp);
            sp.getCooldowns().addCooldown(this, RETURN_COOLDOWN);
            level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), ModSounds.GRAB_TELEPORT.get(), SoundSource.PLAYERS, 1.0f, 1.2f);
            return InteractionResultHolder.success(stack);
        }
        if (!hasSky(level)) {
            // Under a ceiling (the Nether) there is no sky to break.
            sp.displayClientMessage(Component.translatable("message.spawnadmin.no_sky").withStyle(ChatFormatting.GRAY), true);
            return InteractionResultHolder.fail(stack);
        }
        if (SkyEventManager.isBusy(sp)) {
            sp.displayClientMessage(Component.translatable("message.spawnadmin.already_heard"), true);
            return InteractionResultHolder.fail(stack);
        }
        long rest = remainingRestMillis(sp);
        if (rest > 0) {
            sp.displayClientMessage(Component.translatable("message.spawnadmin.horn_resting", formatRest(rest))
                    .withStyle(ChatFormatting.GRAY), true);
            sp.getCooldowns().addCooldown(this, 20);
            return InteractionResultHolder.fail(stack);
        }
        // The heavens come for the one who blew the horn.
        player.startUsingItem(hand);
        level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), ModSounds.HEAVEN_HORN.get(), SoundSource.PLAYERS, 8.0f, 1.0f);
        SkyEventManager.scheduleSummon(sp, sp);
        sp.getCooldowns().addCooldown(this, BLOW_COOLDOWN);
        sp.awardStat(Stats.ITEM_USED.get(this));
        return InteractionResultHolder.consume(stack);
    }

    /** Client side: how long the local player's horn still rests (set by the client entry point). */
    public static java.util.function.LongSupplier clientRestRemaining = () -> 0L;

    private static boolean hasSky(Level level) {
        return !level.dimensionType().hasCeiling();
    }

    private static boolean inRealmDimension(Level level) {
        try {
            return level.dimension().equals(AdminRealm.realmKey()) || level.dimension().equals(AdminRealm.DEFAULT_REALM);
        } catch (IllegalStateException configNotLoaded) {
            return level.dimension().equals(AdminRealm.DEFAULT_REALM);
        }
    }

    /** How long the horn still rests for this player (0 when it is ready). */
    public static long remainingRestMillis(ServerPlayer player) {
        return Math.max(0L, player.getData(ModAttachments.HORN_READY_AT.get()) - System.currentTimeMillis());
    }

    /** Starts the rest: called when the horn has carried the player into the realm. */
    public static void startRest(ServerPlayer player) {
        long ms = SpawnAdminConfig.HORN_REST_MINUTES.get() * 60_000L;
        player.setData(ModAttachments.HORN_READY_AT.get(), System.currentTimeMillis() + ms);
        syncRest(player);
    }

    public static void resetRest(ServerPlayer player) {
        player.removeData(ModAttachments.HORN_READY_AT.get());
        syncRest(player);
    }

    /** Tells the player's client how long the horn still rests. */
    public static void syncRest(ServerPlayer player) {
        com.spawnadmin.network.SpawnAdminNetwork.send(player, new com.spawnadmin.network.HornRestPayload(remainingRestMillis(player)));
    }

    /** "4 h 12 min" style, in the player's language. */
    public static Component formatRest(long ms) {
        long minutes = Math.max(1, (ms + 59_999) / 60_000);
        long hours = minutes / 60;
        minutes %= 60;
        if (hours > 0) {
            return Component.translatable("message.spawnadmin.time_hm", hours, minutes);
        }
        return Component.translatable("message.spawnadmin.time_m", minutes);
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return USE_TICKS;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.TOOT_HORN;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.spawnadmin.heaven_horn.desc1").withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
        tooltip.add(Component.translatable("item.spawnadmin.heaven_horn.desc2").withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("item.spawnadmin.heaven_horn.desc3").withStyle(ChatFormatting.DARK_GRAY));
        long rest = clientRestRemaining.getAsLong();
        if (rest > 0) {
            tooltip.add(Component.translatable("message.spawnadmin.horn_resting", formatRest(rest)).withStyle(ChatFormatting.RED));
        }
    }
}
