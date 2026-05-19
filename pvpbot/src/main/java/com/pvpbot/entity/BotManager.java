package com.pvpbot.entity;

import com.pvpbot.PvPBotMod;
import com.pvpbot.config.BotConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BotManager — singleton registry + tick driver.
 *
 * New: retry queue for bots that didn't appear in player list immediately after createFake.
 */
public class BotManager {

    private static final BotManager INSTANCE = new BotManager();
    public static BotManager getInstance() { return INSTANCE; }

    private final Map<String, PvPBotEntity> bots = new ConcurrentHashMap<>();

    /** Pending retry entries. */
    private record RetryEntry(String name, ServerWorld world, Vec3d pos, BotConfig config, int delayTicks) {}
    private final List<RetryEntry> retryQueue = Collections.synchronizedList(new ArrayList<>());
    private int retryTickCounter = 0;

    private BotManager() {}

    // -------------------------------------------------------------------------
    // Registration
    // -------------------------------------------------------------------------

    public void register(PvPBotEntity bot) {
        bots.put(bot.getName(), bot);
        PvPBotMod.LOGGER.info("[PvPBot] Registered bot: {}", bot.getName());
    }

    public void unregister(String name) {
        bots.remove(name);
        PvPBotMod.LOGGER.info("[PvPBot] Unregistered bot: {}", name);
    }

    public PvPBotEntity get(String name)    { return bots.get(name); }
    public boolean exists(String name)      { return bots.containsKey(name); }
    public Collection<PvPBotEntity> getAll(){ return Collections.unmodifiableCollection(bots.values()); }
    public Collection<String> getBotNames() { return Collections.unmodifiableSet(bots.keySet()); }

    // -------------------------------------------------------------------------
    // Retry queue
    // -------------------------------------------------------------------------

    /**
     * Schedule a second spawn attempt for a bot that didn't appear immediately.
     * Called by BotSpawner when createFake succeeds but player lookup returns null.
     */
    public void scheduleRetry(String name, ServerWorld world, Vec3d pos, BotConfig config, int delayTicks) {
        retryQueue.add(new RetryEntry(name, world, pos, config, retryTickCounter + delayTicks));
        PvPBotMod.LOGGER.info("[PvPBot] Retry scheduled for '{}' in {} ticks", name, delayTicks);
    }

    // -------------------------------------------------------------------------
    // Tick
    // -------------------------------------------------------------------------

    public void tickAll(MinecraftServer server) {
        retryTickCounter++;

        // Process retry queue
        retryQueue.removeIf(entry -> {
            if (retryTickCounter < entry.delayTicks()) return false; // not yet
            if (BotManager.getInstance().exists(entry.name())) return true; // already registered

            PvPBotMod.LOGGER.info("[PvPBot] Retry attempt for '{}'", entry.name());
            // Attempt to find the player — they may be in list now
            var player = server.getPlayerManager().getPlayer(entry.name());
            if (player instanceof carpet.patches.EntityPlayerMPFake fake) {
                PvPBotEntity botEntity = new PvPBotEntity(fake, entry.config());
                register(botEntity);
                PvPBotMod.LOGGER.info("[PvPBot] Retry succeeded for '{}'", entry.name());
            } else {
                // Try spawning fresh
                BotSpawner.spawn(server, entry.world(), entry.name(), entry.pos(), entry.config());
            }
            return true; // remove from queue regardless
        });

        // Tick all registered bots, prune dead ones
        bots.entrySet().removeIf(entry -> {
            PvPBotEntity bot = entry.getValue();
            if (!bot.isAlive()) {
                PvPBotMod.LOGGER.info("[PvPBot] Bot {} removed from world, unregistering.", entry.getKey());
                return true;
            }
            try {
                bot.tick(server);
            } catch (Exception e) {
                PvPBotMod.LOGGER.error("[PvPBot] Error ticking bot {}: {}", entry.getKey(), e.getMessage(), e);
            }
            return false;
        });
    }
}
