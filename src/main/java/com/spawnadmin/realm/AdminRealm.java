package com.spawnadmin.realm;

import com.spawnadmin.SpawnAdmin;
import com.spawnadmin.SpawnAdminConfig;
import com.spawnadmin.registry.ModAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;

/** The administrator's personal realm: entering, leaving and the arrival platform. */
public final class AdminRealm {
    public static final ResourceKey<Level> DEFAULT_REALM = ResourceKey.create(Registries.DIMENSION, SpawnAdmin.id("admin_realm"));
    public static final BlockPos ARRIVAL = new BlockPos(0, 64, 0);

    private AdminRealm() {
    }

    public static ResourceKey<Level> realmKey() {
        ResourceLocation id = ResourceLocation.tryParse(SpawnAdminConfig.TARGET_DIMENSION.get());
        return id == null ? DEFAULT_REALM : ResourceKey.create(Registries.DIMENSION, id);
    }

    private static ResourceKey<Level> warnedMissing;

    public static ServerLevel realmLevel(MinecraftServer server) {
        ResourceKey<Level> key = realmKey();
        ServerLevel level = server.getLevel(key);
        if (level == null && !key.equals(DEFAULT_REALM)) {
            level = server.getLevel(DEFAULT_REALM);
        }
        if (level == null && !key.equals(warnedMissing)) {
            warnedMissing = key;
            SpawnAdmin.LOGGER.warn("Target dimension {} is not loaded; the hand cannot carry anyone away", key.location());
        }
        return level;
    }

    public static boolean isInRealm(ServerPlayer player) {
        ServerLevel realm = realmLevel(player.server);
        return realm != null && player.level() == realm;
    }

    /** Moves the player into the realm, remembering where they came from. */
    public static boolean enter(ServerPlayer player) {
        return enter(player, player.position());
    }

    /**
     * Moves the player into the realm. {@code returnPos} is where they will be sent back to: for
     * the summoning this is the spot on the ground where the hand took them, not the sky.
     */
    public static boolean enter(ServerPlayer player, Vec3 returnPos) {
        ServerLevel realm = realmLevel(player.server);
        if (realm == null) {
            return false;
        }
        if (player.level() != realm) {
            player.setData(ModAttachments.RETURN_POINT.get(), new ReturnPoint(true, player.level().dimension(),
                    returnPos, player.getYRot(), 0f));
        }
        Vec3 arrival = prepareArrival(realm);
        player.changeDimension(new DimensionTransition(realm, arrival, Vec3.ZERO, player.getYRot(), 0f, DimensionTransition.DO_NOTHING));
        player.resetFallDistance();
        player.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 100, 0, false, false));
        return true;
    }

    /** Returns the player to the saved point, or to world spawn if none is stored. */
    public static void leave(ServerPlayer player) {
        MinecraftServer server = player.server;
        ReturnPoint point = player.getData(ModAttachments.RETURN_POINT.get());
        ServerLevel target = point.valid() ? server.getLevel(point.dimension()) : null;
        Vec3 pos;
        float yRot = player.getYRot();
        float xRot = player.getXRot();
        if (target != null) {
            pos = point.pos();
            yRot = point.yRot();
            xRot = point.xRot();
        } else {
            target = server.overworld();
            BlockPos spawn = target.getSharedSpawnPos();
            BlockPos top = target.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn);
            pos = Vec3.atBottomCenterOf(top);
        }
        player.removeData(ModAttachments.RETURN_POINT.get());
        player.changeDimension(new DimensionTransition(target, pos, Vec3.ZERO, yRot, xRot, DimensionTransition.DO_NOTHING));
        player.resetFallDistance();
    }

    private static Vec3 prepareArrival(ServerLevel realm) {
        if (SpawnAdminConfig.BUILD_ARRIVAL_PLATFORM.get() && realm.dimension().equals(DEFAULT_REALM)
                && realm.getBlockState(ARRIVAL.below()).isAir()) {
            buildPlatform(realm);
        }
        return Vec3.atBottomCenterOf(ARRIVAL);
    }

    /** A round platform of white quartz with a ring of pale glass and soft lights. */
    static void buildPlatform(ServerLevel level) {
        BlockPos center = ARRIVAL.below();
        BlockState floor = Blocks.SMOOTH_QUARTZ.defaultBlockState();
        BlockState ring = Blocks.WHITE_STAINED_GLASS.defaultBlockState();
        BlockState light = Blocks.SEA_LANTERN.defaultBlockState();
        int radius = 10;
        for (int dx = -radius - 1; dx <= radius + 1; dx++) {
            for (int dz = -radius - 1; dz <= radius + 1; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                BlockPos p = center.offset(dx, 0, dz);
                if (d <= radius - 0.5) {
                    boolean lamp = (dx == 0 && dz == 0) || (Math.abs(d - 6) < 0.5 && (dx == 0 || dz == 0));
                    level.setBlock(p, lamp ? light : floor, 3);
                } else if (d <= radius + 0.6) {
                    level.setBlock(p, ring, 3);
                }
            }
        }
    }
}
