package com.spawnadmin.gametest;

import com.spawnadmin.SpawnAdmin;
import com.spawnadmin.SpawnAdminConfig;
import com.spawnadmin.realm.AdminRealm;
import com.spawnadmin.registry.ModAttachments;
import com.spawnadmin.registry.ModItems;
import com.spawnadmin.server.SkyEventManager;
import com.spawnadmin.sky.SkyTimeline;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game tests of the server state machine. Run with ./gradlew runGameTestServer.
 * Each test uses its own mock player, so they may run in parallel.
 */
@SuppressWarnings("removal")
@GameTestHolder(SpawnAdmin.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HeavenlySummoningTests {
    private static final int HIDDEN = 20 * 15;

    private HeavenlySummoningTests() {
    }

    /**
     * The GameTest server only creates the dimensions of its world preset, so datapack dimensions
     * such as the administrator's realm are missing there (a normal server loads them). Fall back
     * to the Nether so the carry-away and return logic is still exercised end to end.
     */
    private static void ensureRealm(GameTestHelper helper) {
        if (AdminRealm.realmLevel(helper.getLevel().getServer()) == null) {
            SpawnAdminConfig.TARGET_DIMENSION.set("minecraft:the_nether");
        }
    }

    @GameTest(template = "empty", timeoutTicks = HIDDEN + 120)
    public static void hornWaitsSilentlyThenStartsAndCoolsDown(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.moveTo(helper.absoluteVec(new Vec3(2.5, 2, 2.5)));
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.HEAVEN_HORN.get()));
        ModItems.HEAVEN_HORN.get().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);

        helper.assertTrue(player.getCooldowns().isOnCooldown(ModItems.HEAVEN_HORN.get()), "horn is on cooldown");
        helper.assertTrue(SkyEventManager.isBusy(player), "summoning is pending");
        helper.assertFalse(SkyEventManager.hasEvent(player.getUUID()), "nothing visible during the hidden wait");
        int delay = SpawnAdminConfig.HIDDEN_DELAY_SECONDS.get() * 20;
        helper.runAfterDelay(delay - 20, () ->
                helper.assertFalse(SkyEventManager.hasEvent(player.getUUID()), "still hidden one second before"));
        helper.runAfterDelay(delay + 10, () -> {
            helper.assertTrue(SkyEventManager.hasEvent(player.getUUID()), "the sky answers after the wait");
            SkyEventManager.cancel(player);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = SkyTimeline.RESTORE + SkyTimeline.RESTORE_LENGTH + 120)
    public static void fullCycleCarriesThePlayerAndRestoresTheSky(GameTestHelper helper) {
        ensureRealm(helper);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        ServerLevel origin = helper.getLevel();
        Vec3 start = helper.absoluteVec(new Vec3(2.5, 2, 2.5));
        player.moveTo(start);
        helper.assertTrue(SkyEventManager.startNow(player), "event starts");

        helper.runAfterDelay(SkyTimeline.FREEZE + 2, () -> {
            AttributeInstance flight = player.getAttribute(NeoForgeMod.CREATIVE_FLIGHT);
            helper.assertTrue(flight != null && flight.hasModifier(SpawnAdmin.id("summoning_hold")),
                    "target may float while frozen (no flying kick)");
        });
        helper.runAfterDelay(SkyTimeline.TELEPORT + 5, () -> {
            helper.assertTrue(AdminRealm.isInRealm(player), "carried into the administrator's realm");
            helper.assertTrue(player.getData(ModAttachments.RETURN_POINT.get()).valid(), "return point saved");
            AttributeInstance flight = player.getAttribute(NeoForgeMod.CREATIVE_FLIGHT);
            helper.assertFalse(flight != null && flight.hasModifier(SpawnAdmin.id("summoning_hold")), "flight hold removed");
            AdminRealm.leave(player);
        });
        helper.runAfterDelay(SkyTimeline.TELEPORT + 15, () -> {
            helper.assertTrue(player.level() == origin, "returned to the original dimension");
            helper.assertTrue(player.position().distanceTo(start) < 2.0, "returned to the original spot");
        });
        helper.runAfterDelay(SkyTimeline.RESTORE + SkyTimeline.RESTORE_LENGTH + 5, () -> {
            helper.assertFalse(SkyEventManager.hasEvent(player.getUUID()), "event cleaned up after the restore");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = SkyTimeline.FALL + 20 + SkyTimeline.RESTORE_LENGTH + 80)
    public static void cancelAbortsIntoTheRestore(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.moveTo(helper.absoluteVec(new Vec3(2.5, 2, 2.5)));
        helper.assertTrue(SkyEventManager.startNow(player), "event starts");
        helper.runAfterDelay(SkyTimeline.FALL + 20, () -> {
            helper.assertTrue(SkyEventManager.cancel(player), "cancel accepted");
            int restore = SkyEventManager.restoreAtFor(player.getUUID());
            helper.assertTrue(restore > 0 && restore <= SkyTimeline.FALL + 22, "restore scheduled right away: " + restore);
            helper.assertFalse(AdminRealm.isInRealm(player), "aborted target stays home");
        });
        helper.runAfterDelay(SkyTimeline.FALL + 22 + SkyTimeline.RESTORE_LENGTH + 5, () -> {
            helper.assertFalse(SkyEventManager.hasEvent(player.getUUID()), "aborted event is removed");
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void enterAndExitCommandsRoundTrip(GameTestHelper helper) {
        ensureRealm(helper);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 start = helper.absoluteVec(new Vec3(2.5, 2, 2.5));
        player.moveTo(start);
        helper.assertTrue(AdminRealm.enter(player), "enter works");
        helper.assertTrue(AdminRealm.isInRealm(player), "in realm");
        if (player.level().dimension().equals(AdminRealm.DEFAULT_REALM)) {
            helper.assertFalse(player.level().getBlockState(AdminRealm.ARRIVAL.below()).isAir(), "arrival platform built");
        }
        AdminRealm.leave(player);
        helper.assertTrue(player.level() == helper.getLevel() && player.position().distanceTo(start) < 2.0, "back home");
        helper.succeed();
    }
}
