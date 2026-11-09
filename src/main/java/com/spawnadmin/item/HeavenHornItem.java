package com.spawnadmin.item;

import com.spawnadmin.SpawnAdminConfig;
import com.spawnadmin.realm.AdminRealm;
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
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Horn of the Heavens. Pointed at a player, it calls the heavens upon them: after a silent wait
 * the sky cracks, breaks open and a hand of light carries them away into the administrator's
 * realm. Pointed at nobody, it summons the one who blew it. The realm can only be entered through
 * the full animation; there is no shortcut. Inside the realm it sends the player back.
 */
public class HeavenHornItem extends Item {
    private static final int USE_TICKS = 60;
    private static final int RETURN_COOLDOWN = 40;
    private static final double TARGET_RANGE = 128.0;

    public HeavenHornItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
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
        // The heavens take the player the horn is pointed at; with nobody in sight, the blower.
        ServerPlayer target = lookedAtPlayer(sp, TARGET_RANGE);
        if (target == null) {
            target = sp;
        }
        if (SkyEventManager.isBusy(target) || AdminRealm.isInRealm(target)) {
            sp.displayClientMessage(Component.translatable("message.spawnadmin.already_heard"), true);
            return InteractionResultHolder.fail(stack);
        }
        player.startUsingItem(hand);
        level.playSound(null, sp.getX(), sp.getY(), sp.getZ(), ModSounds.HEAVEN_HORN.get(), SoundSource.PLAYERS, 8.0f, 1.0f);
        SkyEventManager.scheduleSummon(target, sp);
        if (target != sp) {
            sp.displayClientMessage(Component.translatable("message.spawnadmin.heard_target", target.getDisplayName()), true);
        }
        sp.getCooldowns().addCooldown(this, SpawnAdminConfig.HORN_COOLDOWN_SECONDS.get() * 20);
        sp.awardStat(Stats.ITEM_USED.get(this));
        return InteractionResultHolder.consume(stack);
    }

    /** The player closest to the line of sight (within a few degrees), visible and in range. */
    static ServerPlayer lookedAtPlayer(ServerPlayer caller, double range) {
        Vec3 eye = caller.getEyePosition();
        Vec3 look = caller.getLookAngle();
        ServerPlayer best = null;
        double bestAngle = Double.MAX_VALUE;
        for (ServerPlayer other : caller.serverLevel().players()) {
            if (other == caller || !other.isAlive() || other.isSpectator()) {
                continue;
            }
            Vec3 to = other.getBoundingBox().getCenter().subtract(eye);
            double dist = to.length();
            if (dist > range || dist < 1e-3) {
                continue;
            }
            double angle = Math.acos(Math.max(-1, Math.min(1, to.normalize().dot(look))));
            double tolerance = Math.atan(1.6 / dist) + Math.toRadians(2);
            if (angle <= tolerance && angle < bestAngle && caller.hasLineOfSight(other)) {
                best = other;
                bestAngle = angle;
            }
        }
        return best;
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
    }
}
