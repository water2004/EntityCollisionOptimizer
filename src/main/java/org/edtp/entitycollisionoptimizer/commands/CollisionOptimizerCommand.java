package org.edtp.entitycollisionoptimizer.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.edtp.entitycollisionoptimizer.natives.FFMBackend;

/** Explicit public controls; internal config fields are never reflected into commands. */
public final class CollisionOptimizerCommand {
    private CollisionOptimizerCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("eco")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(CollisionOptimizerCommand::status)
                .then(Commands.literal("check").executes(CollisionOptimizerCommand::status)));
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal(
                "Entity Collision Optimizer: FFM initialized=" + FFMBackend.isInitialized()), false);
        return 1;
    }
}
