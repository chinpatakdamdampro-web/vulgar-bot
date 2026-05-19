package com.pvpbot.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.pvpbot.PvPBotMod;
import net.minecraft.server.MinecraftServer;

import java.io.*;
import java.nio.file.*;

/**
 * PvPBotConfigFile — reads and writes pvpbot_config.json in the world folder.
 *
 * Persisted settings:
 *  - defaultDifficulty  : difficulty applied to newly spawned bots (easy/medium/hard/ultrahard)
 *  - revengeMode        : global revenge mode default for new bots
 *
 * Usage:
 *   PvPBotConfigFile.getInstance().load(server);   // on server start
 *   PvPBotConfigFile.getInstance().save();         // after any change
 *   String diff = PvPBotConfigFile.getInstance().getDefaultDifficulty();
 */
public class PvPBotConfigFile {

    private static final PvPBotConfigFile INSTANCE = new PvPBotConfigFile();
    public static PvPBotConfigFile getInstance() { return INSTANCE; }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "pvpbot_config.json";

    private Path configPath = null;

    // -------------------------------------------------------------------------
    // Config data (POJO that GSON serialises directly)
    // -------------------------------------------------------------------------

    private static class Data {
        String  defaultDifficulty = "medium";
        boolean revengeMode       = false;
    }

    private Data data = new Data();

    private PvPBotConfigFile() {}

    // -------------------------------------------------------------------------
    // Load / Save
    // -------------------------------------------------------------------------

    /**
     * Called once on SERVER_STARTED. Resolves the config path relative to the
     * world folder and loads existing values (or writes defaults if missing).
     */
    public void load(MinecraftServer server) {
        configPath = server.getSavePath(net.minecraft.util.WorldSavePath.ROOT)
                          .resolve(FILE_NAME);
        if (Files.exists(configPath)) {
            try (Reader r = new FileReader(configPath.toFile())) {
                Data loaded = GSON.fromJson(r, Data.class);
                if (loaded != null) data = loaded;
                PvPBotMod.LOGGER.info("[PvPBot] Config loaded from {}", configPath);
                PvPBotMod.LOGGER.info("[PvPBot]   defaultDifficulty = {}", data.defaultDifficulty);
                PvPBotMod.LOGGER.info("[PvPBot]   revengeMode       = {}", data.revengeMode);
            } catch (Exception e) {
                PvPBotMod.LOGGER.error("[PvPBot] Failed to read config: {}", e.getMessage());
            }
        } else {
            save(); // write defaults
            PvPBotMod.LOGGER.info("[PvPBot] Config created at {}", configPath);
        }
    }

    /** Writes current values to disk. No-op if load() hasn't been called yet. */
    public void save() {
        if (configPath == null) return;
        try (Writer w = new FileWriter(configPath.toFile())) {
            GSON.toJson(data, w);
        } catch (Exception e) {
            PvPBotMod.LOGGER.error("[PvPBot] Failed to save config: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public String getDefaultDifficulty()                  { return data.defaultDifficulty; }
    public void   setDefaultDifficulty(String difficulty) { data.defaultDifficulty = difficulty.toLowerCase(); save(); }

    public boolean getRevengeMode()            { return data.revengeMode; }
    public void    setRevengeMode(boolean rev) { data.revengeMode = rev; save(); }

    /** Returns a fresh BotConfig built from the persisted default difficulty. */
    public BotConfig buildDefaultConfig() {
        BotConfig cfg = BotConfig.forDifficulty(data.defaultDifficulty);
        cfg.revengeMode = data.revengeMode;
        return cfg;
    }
}
