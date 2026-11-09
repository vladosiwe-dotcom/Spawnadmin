package com.spawnadmin.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.spawnadmin.realm.AdminRealm;
import com.spawnadmin.server.SkyEventManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;

/**
 * /heaven enter [players]  – into the administrator's realm, always through the full sky
 *                            animation (only the silent wait is skipped); there is no instant path
 * /heaven exit [players]   – back to the saved return point
 * /heaven return <players> – send the named players back to the spot where the hand took them
 * /heaven cancel [players] – abort events (in this dimension, or of the given players)
 * /heaven leave            – for every player: leave the realm, back to where the hand took you
 * /heaven banish           – send everyone but operators out of the realm (like the Seal)
 * /heaven resetcooldown <players> – let the horn carry these players again right away
 *
 * Everything except {@code leave} needs permission level 2 (operator).
 */
public final class HeavenCommand {
    private HeavenCommand() {
    }

    public static void register(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("heaven")
                .then(Commands.literal("leave")
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            if (!exit(player)) {
                                ctx.getSource().sendFailure(Component.translatable("commands.spawnadmin.leave.failed"));
                                return 0;
                            }
                            player.playNotifySound(com.spawnadmin.registry.ModSounds.GRAB_TELEPORT.get(),
                                    net.minecraft.sounds.SoundSource.PLAYERS, 1.0f, 1.2f);
                            return 1;
                        }))
                .then(Commands.literal("banish")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> {
                            int n = com.spawnadmin.item.BanishingSealItem.banishAll(ctx.getSource().getServer());
                            ctx.getSource().sendSuccess(() -> Component.translatable("message.spawnadmin.banished", n), true);
                            return n;
                        }))
                .then(Commands.literal("resetcooldown")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> run(ctx, EntityArgument.getPlayers(ctx, "targets"), p -> {
                                    com.spawnadmin.item.HeavenHornItem.resetRest(p);
                                    return true;
                                }, "resetcooldown"))))
                .then(Commands.literal("enter")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> run(ctx, List.of(ctx.getSource().getPlayerOrException()), SkyEventManager::startNow, "enter"))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> run(ctx, EntityArgument.getPlayers(ctx, "targets"), SkyEventManager::startNow, "enter"))))
                .then(Commands.literal("exit")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> run(ctx, List.of(ctx.getSource().getPlayerOrException()), HeavenCommand::exit, "exit"))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> run(ctx, EntityArgument.getPlayers(ctx, "targets"), HeavenCommand::exit, "exit"))))
                .then(Commands.literal("return")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> run(ctx, EntityArgument.getPlayers(ctx, "targets"), HeavenCommand::exit, "return"))))
                .then(Commands.literal("cancel")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> {
                            int n = SkyEventManager.cancelAll(ctx.getSource().getLevel());
                            ctx.getSource().sendSuccess(() -> Component.translatable("commands.spawnadmin.cancel", n), true);
                            return n;
                        })
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> run(ctx, EntityArgument.getPlayers(ctx, "targets"), SkyEventManager::cancel, "cancel")))));
    }

    private static boolean exit(ServerPlayer player) {
        if (!AdminRealm.isInRealm(player)) {
            return false;
        }
        AdminRealm.leave(player);
        return true;
    }

    private static int run(CommandContext<CommandSourceStack> ctx, Collection<ServerPlayer> players,
                           Predicate<ServerPlayer> action, String key) throws CommandSyntaxException {
        int ok = 0;
        for (ServerPlayer player : players) {
            if (action.test(player)) {
                ok++;
            }
        }
        int done = ok;
        if (done > 0) {
            ctx.getSource().sendSuccess(() -> Component.translatable("commands.spawnadmin." + key, done), true);
        } else {
            ctx.getSource().sendFailure(Component.translatable("commands.spawnadmin." + key + ".failed"));
        }
        return done;
    }
}
