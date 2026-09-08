package org.edtp.entitycollisionoptimizer.commands;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.edtp.entitycollisionoptimizer.config.CollisionOptimizerConfig;
import org.edtp.entitycollisionoptimizer.natives.FFMBackend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Explicit public controls; internal config fields are never reflected into commands. */
public final class CollisionOptimizerCommand {
    private CollisionOptimizerCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("eco")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(CollisionOptimizerCommand::status)
                .then(Commands.literal("check").executes(CollisionOptimizerCommand::status))
                .then(Commands.literal("vanillaOrder")
                        .then(Commands.argument("value", BoolArgumentType.bool())
                                .executes(CollisionOptimizerCommand::setOrder))));
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal(
                "Entity Collision Optimizer: "
                        + (CollisionOptimizerConfig.enableEntityCollision ? "FFM" : "Vanilla")
                        + "; FFM initialized=" + FFMBackend.isInitialized()
                        + "; vanillaOrder active=" + CollisionOptimizerConfig.STARTUP_VANILLA_ORDER
                        + ", next restart=" + CollisionOptimizerConfig.vanillaOrder), false);
        return 1;
    }

    private static int setOrder(CommandContext<CommandSourceStack> context) {
        boolean order = BoolArgumentType.getBool(context, "value");
        JsonObject config = new JsonObject();
        config.addProperty("enableEntityCollision", CollisionOptimizerConfig.enableEntityCollision);
        config.addProperty("gridSize", CollisionOptimizerConfig.gridSize);
        config.addProperty("vanillaOrder", order);
        try {
            Files.writeString(CollisionOptimizerConfig.getConfigFile().toPath(),
                    new GsonBuilder().setPrettyPrinting().create().toJson(config), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            context.getSource().sendFailure(Component.literal("Cannot save config: " + failure.getMessage()));
            return 0;
        }
        CollisionOptimizerConfig.vanillaOrder = order;
        context.getSource().sendSuccess(() -> Component.literal(
                "vanillaOrder=" + order + " saved for next restart; current="
                        + CollisionOptimizerConfig.STARTUP_VANILLA_ORDER), false);
        return 1;
    }
}
