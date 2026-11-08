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
 * /heaven enter [players]  – straight into the administrator's realm
 * /heaven exit [players]   – back to the saved return point
 * /heaven summon [players] – the full sky event, without the silent wait
 * /heaven cancel [players] – abort events (in this dimension, or of the given players)
 */
public final class HeavenCommand {
    private HeavenCommand() {
    }

    public static void register(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("heaven")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("enter")
                        .executes(ctx -> run(ctx, List.of(ctx.getSource().getPlayerOrException()), HeavenCommand::enter, "enter"))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> run(ctx, EntityArgument.getPlayers(ctx, "targets"), HeavenCommand::enter, "enter"))))
                .then(Commands.literal("exit")
                        .executes(ctx -> run(ctx, List.of(ctx.getSource().getPlayerOrException()), HeavenCommand::exit, "exit"))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> run(ctx, EntityArgument.getPlayers(ctx, "targets"), HeavenCommand::exit, "exit"))))
                .then(Commands.literal("summon")
                        .executes(ctx -> run(ctx, List.of(ctx.getSource().getPlayerOrException()), SkyEventManager::startNow, "summon"))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> run(ctx, EntityArgument.getPlayers(ctx, "targets"), SkyEventManager::startNow, "summon"))))
                .then(Commands.literal("cancel")
                        .executes(ctx -> {
                            int n = SkyEventManager.cancelAll(ctx.getSource().getLevel());
                            ctx.getSource().sendSuccess(() -> Component.translatable("commands.spawnadmin.cancel", n), true);
                            return n;
                        })
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> run(ctx, EntityArgument.getPlayers(ctx, "targets"), SkyEventManager::cancel, "cancel")))));
    }

    private static boolean enter(ServerPlayer player) {
        return AdminRealm.enter(player);
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
