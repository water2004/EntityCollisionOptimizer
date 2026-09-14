package org.edtp.entitycollisionoptimizer.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.edtp.entitycollisionoptimizer.EntityCollisionOptimizer;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class CollisionOptimizerConfig {
    public static volatile int gridSize = 1;
    public static volatile boolean vanillaOrder = true;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("entity_collision_optimizer.json")
            .toFile();

    static { loadConfig(); }
    public static final boolean STARTUP_VANILLA_ORDER = vanillaOrder;

    private CollisionOptimizerConfig() {
    }

    public static File getConfigFile() {
        return CONFIG_FILE;
    }

    public static void loadConfig() {
        JsonObject defaults = defaultConfig();
        String defaultJson = GSON.toJson(defaults);

        if (!CONFIG_FILE.exists()) {
            try {
                Files.writeString(CONFIG_FILE.toPath(), defaultJson, StandardCharsets.UTF_8);
            } catch (IOException failure) {
                EntityCollisionOptimizer.LOGGER.error("Cannot create config file", failure);
            }
        }

        String configJson;
        try {
            configJson = Files.readString(CONFIG_FILE.toPath(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            EntityCollisionOptimizer.LOGGER.warn(
                    "Failed to read config: {}. Using defaults.",
                    failure.getMessage()
            );
            configJson = defaultJson;
        }

        try {
            applyJson(JsonParser.parseString(configJson).getAsJsonObject());
        } catch (Exception failure) {
            EntityCollisionOptimizer.LOGGER.warn(
                    "Invalid config: {}. Restoring defaults.",
                    failure.getMessage()
            );
            try {
                Files.writeString(CONFIG_FILE.toPath(), defaultJson, StandardCharsets.UTF_8);
            } catch (IOException writeFailure) {
                EntityCollisionOptimizer.LOGGER.error("Cannot restore default config", writeFailure);
            }
            applyJson(defaults);
        }

    }

    private static JsonObject defaultConfig() {
        JsonObject defaults = new JsonObject();
        defaults.addProperty("gridSize", 1);
        defaults.addProperty("vanillaOrder", true);
        return defaults;
    }

    private static void applyJson(JsonObject config) {
        vanillaOrder = !config.has("vanillaOrder") || config.get("vanillaOrder").getAsBoolean();
        if (config.has("gridSize")) {
            gridSize = config.get("gridSize").getAsInt();
            if (gridSize <= 0) {
                throw new IllegalArgumentException("gridSize must be greater than zero");
            }
        }
    }
}
