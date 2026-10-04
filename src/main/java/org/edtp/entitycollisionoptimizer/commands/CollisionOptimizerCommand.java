package org.edtp.entitycollisionoptimizer.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.edtp.entitycollisionoptimizer.OptimizerSwitches;
import org.edtp.entitycollisionoptimizer.natives.FFMBackend;

import java.util.function.Consumer;

/** Explicit public controls; internal config fields are never reflected into commands. */
public final class CollisionOptimizerCommand {
    private CollisionOptimizerCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("eco")
                .requires(CollisionOptimizerCommand::mayUse)
                .executes(CollisionOptimizerCommand::status)
                .then(Commands.literal("check").executes(CollisionOptimizerCommand::status))
                // Subsystem switches exist so one in-game session can attribute a behavioural
                // difference to the solver, the push run or the index without rebuilding.
                .then(Commands.literal("movement")
                        .executes(context -> state(context.getSource(), "movement", OptimizerSwitches.movement()))
                        .then(Commands.literal("on").executes(context ->
                                apply(context.getSource(), "movement", OptimizerSwitches::movement, true)))
                        .then(Commands.literal("off").executes(context ->
                                apply(context.getSource(), "movement", OptimizerSwitches::movement, false))))
                .then(Commands.literal("push")
                        .executes(context -> state(context.getSource(), "push", OptimizerSwitches.push()))
                        .then(Commands.literal("on").executes(context ->
                                apply(context.getSource(), "push", OptimizerSwitches::push, true)))
                        .then(Commands.literal("off").executes(context ->
                                apply(context.getSource(), "push", OptimizerSwitches::push, false))))
                .then(Commands.literal("index")
                        .executes(context -> state(context.getSource(), "index", OptimizerSwitches.index()))
                        .then(Commands.literal("on").executes(context ->
                                apply(context.getSource(), "index", OptimizerSwitches::index, true)))
                        .then(Commands.literal("off").executes(context ->
                                apply(context.getSource(), "index", OptimizerSwitches::index, false))))
                .then(Commands.literal("all")
                        .executes(CollisionOptimizerCommand::status)
                        .then(Commands.literal("on").executes(context -> report(context.getSource(), true)))
                        .then(Commands.literal("off").executes(context -> report(context.getSource(), false))))
                // Short forms for the two states a player toggles most often.
                .then(Commands.literal("on").executes(context -> report(context.getSource(), true)))
                .then(Commands.literal("off").executes(context -> report(context.getSource(), false))));
    }

    /**
     * Single-player worlds have no operator to grant the gamemaster level, so the hosting player may
     * always tune their own world; a dedicated server keeps the gamemaster requirement.
     *
     * <p>The client decides whether a command node is usable from the requirement evaluated against
     * a source that has neither permissions nor a server ({@code Commands.createCompilationContext}).
     * Returning true for that source keeps the node visible and sendable in single-player instead of
     * being flagged as restricted, while real dedicated-server sources still have to pass the
     * permission check.
     */
    private static boolean mayUse(CommandSourceStack source) {
        if (Commands.LEVEL_GAMEMASTERS.check(source.permissions())) {
            return true;
        }
        MinecraftServer server = source.getServer();
        return server == null || server.isSingleplayer();
    }

    private static int apply(CommandSourceStack source, String name, Consumer<Boolean> writer, boolean enabled) {
        writer.accept(enabled);
        source.sendSuccess(() -> Component.literal(
                "Entity Collision Optimizer: " + name + "=" + enabled + " (" + OptimizerSwitches.describe() + ")"), true);
        return 1;
    }

    private static int report(CommandSourceStack source, boolean enabled) {
        OptimizerSwitches.all(enabled);
        source.sendSuccess(() -> Component.literal(
                "Entity Collision Optimizer: " + OptimizerSwitches.describe()), true);
        return 1;
    }

    private static int state(CommandSourceStack source, String name, boolean enabled) {
        source.sendSuccess(() -> Component.literal(
                "Entity Collision Optimizer: " + name + "=" + enabled), false);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal(
                "Entity Collision Optimizer: FFM initialized=" + FFMBackend.isInitialized()
                        + " " + OptimizerSwitches.describe()), false);
        return 1;
    }
}
