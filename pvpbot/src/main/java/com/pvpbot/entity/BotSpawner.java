package com.pvpbot.entity;

import carpet.patches.EntityPlayerMPFake;
import com.pvpbot.PvPBotMod;
import com.pvpbot.config.BotConfig;
import com.pvpbot.faction.FactionManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;

/**
 * BotSpawner — spawn/remove Carpet fake players.
 *
 * Fixes:
 *  - If fake player already in world, register directly without calling createFake again.
 *  - Auto-retry: schedules a second registration attempt 4 seconds later if first fails.
 *  - massspawn: pulls names from botnames.txt resource file.
 */
public class BotSpawner {

    private BotSpawner() {}

    /**
     * All names from botnames.txt, loaded once.
     */
    private static List<String> BOT_NAMES = null;

    public static List<String> getBotNames() {
        if (BOT_NAMES != null) return BOT_NAMES;
        List<String> names = new ArrayList<>();
        try {
            InputStream is = BotSpawner.class.getResourceAsStream("/botnames.txt");
            if (is != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(is));
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) names.add(line);
                }
            }
        } catch (Exception e) {
            PvPBotMod.LOGGER.error("[PvPBot] Could not load botnames.txt: {}", e.getMessage());
        }
        BOT_NAMES = Collections.unmodifiableList(names);
        return BOT_NAMES;
    }

    // -------------------------------------------------------------------------
    // Spawn single bot
    // -------------------------------------------------------------------------

    public static PvPBotEntity spawn(MinecraftServer server,
                                     ServerWorld world,
                                     String name,
                                     Vec3d pos,
                                     BotConfig config) {
        PvPBotMod.LOGGER.info("[PvPBot] spawn('{}') called", name);

        // Already registered
        if (BotManager.getInstance().exists(name)) {
            PvPBotMod.LOGGER.info("[PvPBot] '{}' already registered, returning existing.", name);
            return BotManager.getInstance().get(name);
        }

        // Fake player already in world (e.g. from prior failed registration)
        ServerPlayerEntity existing = server.getPlayerManager().getPlayer(name);
        if (existing instanceof EntityPlayerMPFake alreadyFake) {
            PvPBotMod.LOGGER.info("[PvPBot] '{}' already in world, registering directly.", name);
            PvPBotEntity botEntity = new PvPBotEntity(alreadyFake, config);
            BotManager.getInstance().register(botEntity);
            return botEntity;
        }
        if (existing != null) {
            PvPBotMod.LOGGER.error("[PvPBot] Real player '{}' is online, cannot use that name.", name);
            return null;
        }

        // Call createFake
        try {
            PvPBotMod.LOGGER.info("[PvPBot] createFake('{}') SURVIVAL @ {}", name, pos);
            EntityPlayerMPFake.createFake(name, server, pos, 0.0, 0.0,
                    world.getRegistryKey(), GameMode.SURVIVAL, false);
        } catch (Exception e) {
            PvPBotMod.LOGGER.error("[PvPBot] createFake exception for '{}': {}", name, e.getMessage(), e);
            return null;
        }

        // Look up immediately
        ServerPlayerEntity spawned = server.getPlayerManager().getPlayer(name);
        PvPBotMod.LOGGER.info("[PvPBot] Post-createFake lookup: {}",
                spawned != null ? spawned.getClass().getSimpleName() : "NULL");

        if (spawned instanceof EntityPlayerMPFake fake) {
            PvPBotEntity botEntity = new PvPBotEntity(fake, config);
            BotManager.getInstance().register(botEntity);
            PvPBotMod.LOGGER.info("[PvPBot] Bot '{}' registered OK.", name);
            return botEntity;
        }

        // Player spawned but not in list yet — schedule retry in 4 seconds (80 ticks)
        PvPBotMod.LOGGER.warn("[PvPBot] Bot '{}' not found immediately — retry queued.", name);
        BotManager.getInstance().scheduleRetry(name, world, pos, config, 80);
        return null;
    }

    // -------------------------------------------------------------------------
    // Mass spawn
    // -------------------------------------------------------------------------

    /**
     * Spawns {@code count} bots with random names from botnames.txt.
     * Returns list of names attempted.
     */
    public static List<String> massSpawn(MinecraftServer server,
                                          ServerWorld world,
                                          Vec3d pos,
                                          int count,
                                          BotConfig config) {
        List<String> available = new ArrayList<>(getBotNames());
        // Remove already-used names
        available.removeIf(n -> server.getPlayerManager().getPlayer(n) != null
                || BotManager.getInstance().exists(n));

        Collections.shuffle(available);
        List<String> attempted = new ArrayList<>();

        int toSpawn = Math.min(count, available.size());
        for (int i = 0; i < toSpawn; i++) {
            String name = available.get(i);
            attempted.add(name);
            spawn(server, world, name, pos, config);
        }
        return attempted;
    }

    // -------------------------------------------------------------------------
    // Remove
    // -------------------------------------------------------------------------

    public static boolean remove(MinecraftServer server, String name) {
        FactionManager.getInstance().removeBot(name);

        PvPBotEntity bot = BotManager.getInstance().get(name);
        if (bot != null) {
            if (!bot.getFakePlayer().isRemoved()) bot.getFakePlayer().kill();
            BotManager.getInstance().unregister(name);
            return true;
        }

        // Try unregistered fake player
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(name);
        if (player instanceof EntityPlayerMPFake fake) {
            fake.kill();
            return true;
        }
        return false;
    }
}
