package com.spawnadmin.server;

import com.spawnadmin.SpawnAdmin;
import com.spawnadmin.SpawnAdminConfig;
import com.spawnadmin.network.SkyEventEndPayload;
import com.spawnadmin.network.SpawnAdminNetwork;
import com.spawnadmin.realm.AdminRealm;
import com.spawnadmin.registry.ModSounds;
import com.spawnadmin.sky.SkyTimeline;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Authoritative state machine of Heavenly Summoning events.
 *
 * <p>The server only decides <em>when</em> things happen and who is carried away; clients build
 * the whole spectacle from the synced seed and start tick. No blocks, entities or world sky are
 * touched.</p>
 */
public final class SkyEventManager {
    /** Pending horn calls during the hidden wait, keyed by player. Value: server tick to fire. */
    private static final Map<UUID, Long> PENDING = new LinkedHashMap<>();
    private static final Map<Integer, ServerSkyEvent> EVENTS = new LinkedHashMap<>();
    private static int nextId = 1;
    /** Temporary flight permission so the server does not kick the frozen/lifted target. */
    private static final ResourceLocation FLIGHT_MODIFIER = SpawnAdmin.id("summoning_hold");

    private SkyEventManager() {
    }

    public static void registerListeners(IEventBus bus) {
        bus.addListener(SkyEventManager::onServerTick);
        bus.addListener(SkyEventManager::onPlayerTick);
        bus.addListener(SkyEventManager::onIncomingDamage);
        bus.addListener(SkyEventManager::onLogin);
        bus.addListener(SkyEventManager::onChangedDimension);
        bus.addListener(SkyEventManager::onRespawn);
        bus.addListener(SkyEventManager::onServerStopped);
    }

    // ------------------------------------------------------------------ public API

    /** True while the player waits for, or is the target of, a summoning. */
    public static boolean isBusy(ServerPlayer player) {
        return PENDING.containsKey(player.getUUID()) || findByTarget(player.getUUID()) != null;
    }

    /** Horn call: the heavens answer after the configured silent delay. */
    public static void scheduleSummon(ServerPlayer player) {
        long fireAt = player.server.getTickCount() + SpawnAdminConfig.HIDDEN_DELAY_SECONDS.get() * 20L;
        PENDING.put(player.getUUID(), fireAt);
    }

    /** Starts the visible part immediately. Returns false if it could not start. */
    public static boolean startNow(ServerPlayer player) {
        PENDING.remove(player.getUUID());
        if (findByTarget(player.getUUID()) != null) {
            return false;
        }
        ServerLevel level = player.serverLevel();
        if (AdminRealm.isInRealm(player)) {
            player.displayClientMessage(Component.translatable("message.spawnadmin.already_in_realm"), true);
            return false;
        }
        long running = EVENTS.values().stream().filter(e -> e.level == level).count();
        if (running >= SpawnAdminConfig.MAX_EVENTS_PER_DIMENSION.get()) {
            player.displayClientMessage(Component.translatable("message.spawnadmin.sky_busy"), true);
            return false;
        }
        ServerSkyEvent event = new ServerSkyEvent(nextId++, level, level.getRandom().nextLong(), player.position(),
                player.getUUID(), player.getId(), level.getGameTime());
        EVENTS.put(event.id, event);
        broadcast(event);
        SpawnAdmin.LOGGER.info("Heavenly Summoning #{} started for {} in {}", event.id, player.getName().getString(),
                level.dimension().location());
        return true;
    }

    /** Aborts every event in a level; the sky reassembles from wherever it is. */
    public static int cancelAll(ServerLevel level) {
        int count = 0;
        for (ServerSkyEvent event : EVENTS.values()) {
            if (event.level == level && cancelEvent(event)) {
                count++;
            }
        }
        PENDING.keySet().removeIf(uuid -> {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(uuid);
            return p == null || p.level() == level;
        });
        return count;
    }

    /** Cancels one player's pending or running summoning. */
    public static boolean cancel(ServerPlayer player) {
        boolean pending = PENDING.remove(player.getUUID()) != null;
        ServerSkyEvent event = findByTarget(player.getUUID());
        return (event != null && cancelEvent(event)) || pending;
    }

    private static boolean cancelEvent(ServerSkyEvent event) {
        if (event.aborted || event.teleported || event.elapsed() >= event.restoreAt) {
            return false;
        }
        abort(event);
        return true;
    }

    public static int activeCount() {
        return EVENTS.size();
    }

    /** Read-only view for tests and diagnostics: is a summoning running for this player? */
    public static boolean hasEvent(UUID player) {
        return findByTarget(player) != null;
    }

    /** Restore tick of the player's event, or -1. */
    public static int restoreAtFor(UUID player) {
        ServerSkyEvent event = findByTarget(player);
        return event == null ? -1 : event.restoreAt;
    }

    // ------------------------------------------------------------------ ticking

    private static void onServerTick(ServerTickEvent.Post tick) {
        MinecraftServer server = tick.getServer();
        long now = server.getTickCount();
        Iterator<Map.Entry<UUID, Long>> pending = PENDING.entrySet().iterator();
        List<ServerPlayer> ready = new ArrayList<>();
        while (pending.hasNext()) {
            Map.Entry<UUID, Long> e = pending.next();
            if (now < e.getValue()) {
                tickClock(server, e.getKey(), e.getValue() - now);
                continue;
            }
            pending.remove();
            ServerPlayer player = server.getPlayerList().getPlayer(e.getKey());
            if (player != null && player.isAlive()) {
                ready.add(player);
            }
        }
        ready.forEach(SkyEventManager::startNow);

        Iterator<ServerSkyEvent> it = EVENTS.values().iterator();
        while (it.hasNext()) {
            ServerSkyEvent event = it.next();
            if (tickEvent(server, event)) {
                SpawnAdminNetwork.sendToLevel(event.level, new SkyEventEndPayload(event.id));
                it.remove();
            }
        }
    }

    /** The hidden wait is silent for the eyes only: a clock counts down the seconds. */
    private static void tickClock(MinecraftServer server, UUID uuid, long remaining) {
        if (remaining % 20 != 0) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        if (player == null) {
            return;
        }
        boolean tock = (remaining / 20) % 2 == 0;
        float volume = remaining <= 60 ? 1.4f : 1.0f;
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                (tock ? ModSounds.CLOCK_TOCK : ModSounds.CLOCK_TICK).get(), SoundSource.AMBIENT, volume, 1.0f);
    }

    /** @return true when the event is finished and must be removed. */
    private static boolean tickEvent(MinecraftServer server, ServerSkyEvent event) {
        long t = event.elapsed();
        ServerPlayer target = server.getPlayerList().getPlayer(event.target);

        if (!event.teleported && !event.aborted && t < SkyTimeline.TELEPORT) {
            boolean lost = target == null || !target.isAlive() || target.level() != event.level;
            if (lost) {
                abort(event);
            }
        }

        if (!event.aborted && !event.teleported && target != null) {
            if (t >= SkyTimeline.FREEZE && event.lockPos == null) {
                lockTarget(event, target);
            }
            if (event.lockPos != null && t < SkyTimeline.TELEPORT) {
                target.setDeltaMovement(Vec3.ZERO);
                target.resetFallDistance();
            }
            if (t >= SkyTimeline.TELEPORT) {
                carryAway(event, target);
            }
        }

        if (!event.restoreAnnounced && t >= event.restoreAt) {
            event.restoreAnnounced = true;
            broadcast(event);
        }
        return t >= event.restoreAt + SkyTimeline.RESTORE_LENGTH;
    }

    private static void lockTarget(ServerSkyEvent event, ServerPlayer target) {
        event.lockPos = target.position();
        event.lockYaw = target.getYRot();
        AttributeInstance flight = target.getAttribute(NeoForgeMod.CREATIVE_FLIGHT);
        if (flight != null && !flight.hasModifier(FLIGHT_MODIFIER)) {
            flight.addTransientModifier(new AttributeModifier(FLIGHT_MODIFIER, 1.0, AttributeModifier.Operation.ADD_VALUE));
            event.grantedFlight = true;
        }
        target.connection.teleport(event.lockPos.x, event.lockPos.y, event.lockPos.z, target.getYRot(), target.getXRot());
        broadcast(event);
    }

    private static void carryAway(ServerSkyEvent event, ServerPlayer target) {
        event.teleported = true;
        revokeFlight(event, target);
        target.noPhysics = false;
        if (AdminRealm.enter(target)) {
            SpawnAdmin.LOGGER.info("Heavenly Summoning #{} carried {} to {}", event.id, target.getName().getString(),
                    AdminRealm.realmKey().location());
        } else {
            target.displayClientMessage(Component.translatable("message.spawnadmin.no_realm"), false);
        }
    }

    private static void abort(ServerSkyEvent event) {
        event.aborted = true;
        long t = event.elapsed();
        event.restoreAt = (int) Math.max(t + 1, SkyTimeline.CRACK);
        event.restoreAnnounced = false;
        ServerPlayer target = event.level.getServer().getPlayerList().getPlayer(event.target);
        if (target != null) {
            revokeFlight(event, target);
            target.noPhysics = false;
            if (event.lockPos != null) {
                target.addEffect(new MobEffectInstance(MobEffects.SLOW_FALLING, 200, 0));
            }
        }
        SpawnAdmin.LOGGER.info("Heavenly Summoning #{} aborted at tick {}", event.id, t);
    }

    private static void revokeFlight(ServerSkyEvent event, ServerPlayer target) {
        if (event.grantedFlight) {
            event.grantedFlight = false;
            AttributeInstance flight = target.getAttribute(NeoForgeMod.CREATIVE_FLIGHT);
            if (flight != null) {
                flight.removeModifier(FLIGHT_MODIFIER);
            }
        }
    }

    // ------------------------------------------------------------------ player hooks

    /**
     * While the hand lifts the target, the client drives the player's position through solid
     * terrain if needed. {@code noPhysics} is reset at the start of every player tick, so it is
     * set again after the tick to keep the server's "moved wrongly" check out of the way.
     */
    private static void onPlayerTick(PlayerTickEvent.Post tick) {
        if (!(tick.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ServerSkyEvent event = findByTarget(player.getUUID());
        if (event == null || event.aborted || event.teleported) {
            return;
        }
        long t = event.elapsed();
        if (t >= SkyTimeline.LIFT && t < SkyTimeline.TELEPORT) {
            player.noPhysics = true;
        }
    }

    private static void onIncomingDamage(LivingIncomingDamageEvent damage) {
        if (!(damage.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ServerSkyEvent event = findByTarget(player.getUUID());
        if (event != null && !event.aborted && event.elapsed() >= SkyTimeline.FREEZE) {
            damage.setCanceled(true);
        }
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
        syncLevelTo(e.getEntity());
    }

    private static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent e) {
        syncLevelTo(e.getEntity());
    }

    private static void onRespawn(PlayerEvent.PlayerRespawnEvent e) {
        syncLevelTo(e.getEntity());
    }

    private static void onServerStopped(ServerStoppedEvent e) {
        PENDING.clear();
        EVENTS.clear();
    }

    /** Late joiners receive the full state of every event running in their dimension. */
    private static void syncLevelTo(Player player) {
        if (!(player instanceof ServerPlayer sp)) {
            return;
        }
        for (ServerSkyEvent event : EVENTS.values()) {
            if (event.level == sp.level()) {
                SpawnAdminNetwork.send(sp, event.toPayload());
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void broadcast(ServerSkyEvent event) {
        SpawnAdminNetwork.sendToLevel(event.level, event.toPayload());
    }

    private static ServerSkyEvent findByTarget(UUID uuid) {
        for (ServerSkyEvent event : EVENTS.values()) {
            if (event.target.equals(uuid)) {
                return event;
            }
        }
        return null;
    }
}
