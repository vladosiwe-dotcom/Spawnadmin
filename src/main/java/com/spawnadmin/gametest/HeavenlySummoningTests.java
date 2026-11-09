package com.spawnadmin.gametest;

import com.spawnadmin.SpawnAdmin;
import com.spawnadmin.SpawnAdminConfig;
import com.spawnadmin.item.HeavenHornItem;
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
    private static final int HIDDEN = 20 * 10;

    private HeavenlySummoningTests() {
    }

    /**
     * The GameTest server only creates the dimensions of its world preset, so datapack dimensions
     * such as the administrator's realm are missing there (a normal server loads them). Fall back
     * to the Nether so the carry-away and return logic is still exercised end to end.
     */
    /** Tests run in parallel in one level: lift the per-dimension event limit for them. */
    private static void allowParallel() {
        SpawnAdminConfig.MAX_EVENTS_PER_DIMENSION.set(16);
    }

    private static void ensureRealm(GameTestHelper helper) {
        allowParallel();
        if (AdminRealm.realmLevel(helper.getLevel().getServer()) == null) {
            SpawnAdminConfig.TARGET_DIMENSION.set("minecraft:the_nether");
        }
    }

    @GameTest(template = "empty", timeoutTicks = HIDDEN + 120)
    public static void hornWaitsSilentlyThenStartsAndCoolsDown(GameTestHelper helper) {
        allowParallel();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 at = helper.absoluteVec(new Vec3(2.5, 2, 2.5));
        player.moveTo(at.x, at.y, at.z, 0f, -90f); // looking straight up: nobody else is targeted
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

        helper.runAfterDelay(SkyTimeline.GRAB + 2, () -> {
            AttributeInstance flight = player.getAttribute(NeoForgeMod.CREATIVE_FLIGHT);
            helper.assertTrue(flight != null && flight.hasModifier(SpawnAdmin.id("summoning_hold")),
                    "target may float once caught (no flying kick)");
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

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void hornAlwaysCallsTheOneWhoBlowsIt(GameTestHelper helper) {
        allowParallel();
        ServerPlayer blower = helper.makeMockServerPlayerInLevel();
        ServerPlayer other = helper.makeMockServerPlayerInLevel();
        Vec3 a = helper.absoluteVec(new Vec3(1.5, 2, 1.5));
        Vec3 v = helper.absoluteVec(new Vec3(1.5, 2, 4.5));
        other.moveTo(v.x, v.y, v.z, 0f, 0f);
        blower.moveTo(a.x, a.y, a.z, 0f, 0f); // looking straight at the other player
        blower.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.HEAVEN_HORN.get()));
        ModItems.HEAVEN_HORN.get().use(helper.getLevel(), blower, InteractionHand.MAIN_HAND);
        helper.assertTrue(SkyEventManager.isBusy(blower), "the heavens come for the one who blew the horn");
        helper.assertFalse(SkyEventManager.isBusy(other), "never for someone else");
        SkyEventManager.cancel(blower);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void restingHornDoesNothingUntilReady(GameTestHelper helper) {
        allowParallel();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 at = helper.absoluteVec(new Vec3(2.5, 2, 2.5));
        player.moveTo(at.x, at.y, at.z, 0f, -90f);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.HEAVEN_HORN.get()));
        player.setData(ModAttachments.HORN_READY_AT.get(), System.currentTimeMillis() + 3_600_000L);
        ModItems.HEAVEN_HORN.get().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.assertFalse(SkyEventManager.isBusy(player), "a resting horn calls nobody");
        helper.assertTrue(HeavenHornItem.remainingRestMillis(player) > 3_500_000L, "the rest is kept");
        HeavenHornItem.resetRest(player);
        player.getCooldowns().removeCooldown(ModItems.HEAVEN_HORN.get());
        ModItems.HEAVEN_HORN.get().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.assertTrue(SkyEventManager.isBusy(player), "after the rest it works again");
        SkyEventManager.cancel(player);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = HIDDEN + SkyTimeline.TELEPORT + 60)
    public static void hornRestsFiveHoursAfterCarryingThePlayer(GameTestHelper helper) {
        ensureRealm(helper);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Vec3 at = helper.absoluteVec(new Vec3(2.5, 2, 2.5));
        player.moveTo(at.x, at.y, at.z, 0f, -90f);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.HEAVEN_HORN.get()));
        ModItems.HEAVEN_HORN.get().use(helper.getLevel(), player, InteractionHand.MAIN_HAND);
        helper.assertTrue(HeavenHornItem.remainingRestMillis(player) == 0, "no rest before the horn has carried anyone");
        int delay = SpawnAdminConfig.HIDDEN_DELAY_SECONDS.get() * 20;
        helper.runAfterDelay(delay + SkyTimeline.TELEPORT + 10, () -> {
            helper.assertTrue(AdminRealm.isInRealm(player), "carried into the realm");
            long rest = HeavenHornItem.remainingRestMillis(player);
            helper.assertTrue(rest > SpawnAdminConfig.HORN_REST_MINUTES.get() * 60_000L - 60_000L, "the horn now rests: " + rest);
            AdminRealm.leave(player);
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = SkyTimeline.FREEZE + 120)
    public static void timeStopsLocallyAroundTheScene(GameTestHelper helper) {
        ensureRealm(helper);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.moveTo(helper.absoluteVec(new Vec3(2.5, 2, 2.5)));
        var item = new net.minecraft.world.entity.item.ItemEntity(helper.getLevel(), player.getX() + 1, player.getY() + 1,
                player.getZ(), new ItemStack(net.minecraft.world.item.Items.STICK));
        item.setNoGravity(false);
        item.setNeverPickUp();
        helper.getLevel().addFreshEntity(item);
        helper.assertTrue(SkyEventManager.startNow(player), "event starts");
        int[] age = new int[1];
        // ItemEntity.age only advances inside a real tick (tickCount is bumped before the tick event).
        helper.runAfterDelay(SkyTimeline.FREEZE + 5, () -> age[0] = item.getAge());
        helper.runAfterDelay(SkyTimeline.FREEZE + 40, () -> {
            helper.assertTrue(item.getAge() == age[0], "entities near the scene do not tick while time is stopped: "
                    + age[0] + " -> " + item.getAge());
            helper.assertTrue(player.tickCount > 0, "players still tick");
            SkyEventManager.cancel(player);
            item.discard();
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
            helper.assertFalse(player.level().getBlockState(AdminRealm.ARRIVAL.below()).isAir(), "arrival island built");
        }
        AdminRealm.leave(player);
        helper.assertTrue(player.level() == helper.getLevel() && player.position().distanceTo(start) < 2.0, "back home");
        helper.succeed();
    }

    // ------------------------------------------------------------------ realm: banishing, leaving
    // A separate batch: it moves everyone in the realm and must not disturb the other tests.

    @GameTest(template = "empty", batch = "realm_exclusive")
    public static void sealSendsEveryoneButOperatorsHome(GameTestHelper helper) {
        ensureRealm(helper);
        var server = helper.getLevel().getServer();
        ServerPlayer admin = helper.makeMockServerPlayerInLevel();
        ServerPlayer guest = helper.makeMockServerPlayerInLevel();
        Vec3 home = helper.absoluteVec(new Vec3(3.5, 2, 3.5));
        guest.moveTo(home);
        admin.moveTo(helper.absoluteVec(new Vec3(1.5, 2, 1.5)));
        helper.assertTrue(AdminRealm.enter(guest) && AdminRealm.enter(admin), "both in the realm");
        admin.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.BANISHING_SEAL.get()));

        ModItems.BANISHING_SEAL.get().use(admin.level(), admin, InteractionHand.MAIN_HAND);
        helper.assertTrue(AdminRealm.isInRealm(guest), "the seal does not obey a non-operator");

        // The GameTest server gives /op level 0, so the entry is added with an explicit level.
        server.getPlayerList().getOps().add(new net.minecraft.server.players.ServerOpListEntry(admin.getGameProfile(), 4, false));
        try {
            helper.assertTrue(admin.hasPermissions(2), "the admin is an operator now");
            admin.getCooldowns().removeCooldown(ModItems.BANISHING_SEAL.get());
            ModItems.BANISHING_SEAL.get().use(admin.level(), admin, InteractionHand.MAIN_HAND);
            helper.assertFalse(AdminRealm.isInRealm(guest), "the guest is banished");
            helper.assertTrue(guest.level() == helper.getLevel() && guest.position().distanceTo(home) < 2.0,
                    "back where they were taken from");
            helper.assertTrue(AdminRealm.isInRealm(admin), "the operator stays");
        } finally {
            server.getPlayerList().getOps().remove(admin.getGameProfile());
            AdminRealm.leave(admin);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "realm_exclusive")
    public static void anyPlayerCanLeaveWithTheCommand(GameTestHelper helper) {
        ensureRealm(helper);
        ServerPlayer guest = helper.makeMockServerPlayerInLevel();
        Vec3 home = helper.absoluteVec(new Vec3(2.5, 2, 2.5));
        guest.moveTo(home);
        helper.assertTrue(AdminRealm.enter(guest), "in the realm");
        helper.assertFalse(guest.hasPermissions(2), "an ordinary player");
        guest.server.getCommands().performPrefixedCommand(guest.createCommandSourceStack(), "heaven leave");
        helper.assertFalse(AdminRealm.isInRealm(guest), "left the realm");
        helper.assertTrue(guest.level() == helper.getLevel() && guest.position().distanceTo(home) < 2.0,
                "back where the hand took them");
        helper.succeed();
    }
}
