package com.wiyuka.acceleratedrecoiling.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wiyuka.acceleratedrecoiling.AcceleratedRecoiling;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class FoldConfig {
    public static boolean enableEntityCollision = true;
    public static int maxCollision = 32;
    public static int gridSize = 1;
    public static int densityWindow = 4;
    public static int densityThreshold = 16;
    public static int maxThreads = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("acceleratedrecoiling.json")
            .toFile();

    private FoldConfig() {
    }

    public static File getConfigFile() {
        return CONFIG_FILE;
    }

    public static int effectiveMaxCollision(int entityCount) {
        if (entityCount < 2) {
            return 0;
        }
        return maxCollision == 0
                ? entityCount - 1
                : Math.min(maxCollision, entityCount - 1);
    }

    public static void loadConfig() {
        JsonObject defaults = defaultConfig();
        String defaultJson = GSON.toJson(defaults);

        if (!CONFIG_FILE.exists()) {
            try {
                Files.writeString(CONFIG_FILE.toPath(), defaultJson, StandardCharsets.UTF_8);
            } catch (IOException failure) {
                AcceleratedRecoiling.LOGGER.error("Cannot create config file", failure);
            }
        }

        String configJson;
        try {
            configJson = Files.readString(CONFIG_FILE.toPath(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            AcceleratedRecoiling.LOGGER.warn(
                    "Failed to read config: {}. Using defaults.",
                    failure.getMessage()
            );
            configJson = defaultJson;
        }

        try {
            applyJson(JsonParser.parseString(configJson).getAsJsonObject());
        } catch (Exception failure) {
            AcceleratedRecoiling.LOGGER.warn(
                    "Invalid config: {}. Restoring defaults.",
                    failure.getMessage()
            );
            try {
                Files.writeString(CONFIG_FILE.toPath(), defaultJson, StandardCharsets.UTF_8);
            } catch (IOException writeFailure) {
                AcceleratedRecoiling.LOGGER.error("Cannot restore default config", writeFailure);
            }
            applyJson(defaults);
        }

        AcceleratedRecoiling.LOGGER.info(
                "Collision mode: {}",
                enableEntityCollision ? "FFM (enabled)" : "Vanilla (disabled)"
        );
    }

    private static JsonObject defaultConfig() {
        JsonObject defaults = new JsonObject();
        defaults.addProperty("enableEntityCollision", true);
        defaults.addProperty("maxCollision", 32);
        defaults.addProperty("gridSize", 1);
        defaults.addProperty("densityWindow", 4);
        defaults.addProperty("densityThreshold", 16);
        defaults.addProperty("maxThreads", 1);
        return defaults;
    }

    private static void applyJson(JsonObject config) {
        if (config.has("enableEntityCollision")) {
            enableEntityCollision = config.get("enableEntityCollision").getAsBoolean();
        }
        if (config.has("maxCollision")) {
            maxCollision = config.get("maxCollision").getAsInt();
        }
        if (config.has("gridSize")) {
            gridSize = config.get("gridSize").getAsInt();
        }
        if (config.has("densityWindow")) {
            densityWindow = config.get("densityWindow").getAsInt();
        }
        if (config.has("densityThreshold")) {
            densityThreshold = config.get("densityThreshold").getAsInt();
        }
        if (config.has("maxThreads")) {
            maxThreads = config.get("maxThreads").getAsInt();
        }
    }
}
