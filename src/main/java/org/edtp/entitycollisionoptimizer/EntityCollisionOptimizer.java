package org.edtp.entitycollisionoptimizer;

import com.mojang.logging.LogUtils;
import org.edtp.entitycollisionoptimizer.commands.CollisionOptimizerCommand;
import org.edtp.entitycollisionoptimizer.config.CollisionOptimizerConfig;
import org.edtp.entitycollisionoptimizer.natives.CollisionFrame;
import org.edtp.entitycollisionoptimizer.natives.FFMBackend;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;

public class EntityCollisionOptimizer implements ModInitializer {
    public static final String MODID = "entity_collision_optimizer";
    public static final Logger LOGGER = LogUtils.getLogger();

    @Override
    public void onInitialize() {
        CollisionOptimizerConfig.loadConfig();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                CollisionOptimizerCommand.register(dispatcher));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            CollisionFrame.destroy();
            FFMBackend.destroy();
        });
    }
}
