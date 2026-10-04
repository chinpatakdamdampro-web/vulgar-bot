package com.pvpbot.entity;

import carpet.patches.EntityPlayerMPFake;
import com.pvpbot.PvPBotMod;
import com.pvpbot.config.BotConfig;
import com.pvpbot.faction.FactionManager;
import com.pvpbot.util.DebugSystem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BotManager {

    private static final BotManager INSTANCE = new BotManager();
    public static BotManager getInstance() { return INSTANCE; }

    private static final int REGISTRATION_RETRY_INTERVAL_TICKS = 20;
    private static final int MAX_REGISTRATION_CHECKS = 5;
    private static final int SPAWN_QUEUE_INTERVAL_TICKS = 5;
    private static final int MAX_SPAWN_QUEUE_SIZE = 128;

    private final Map<String, PvPBotEntity> bots = new ConcurrentHashMap<>();
    private final Set<String> deathHandled = ConcurrentHashMap.newKeySet();

    private record RetryEntry(String name, ServerWorld world, Vec3d pos, BotConfig config,
                              int dueTick, int attempts) {}
    private final List<RetryEntry> retryQueue = Collections.synchronizedList(new ArrayList<>());

    private record SpawnEntry(ServerWorld world, String name, Vec3d pos, BotConfig config) {}
    private final Queue<SpawnEntry> spawnQueue = new ArrayDeque<>();

    private int retryTickCounter = 0;
    private int nextSpawnQueueTick = 0;

    private BotManager() {}

    // -------------------------------------------------------------------------
    // Registration
    // -------------------------------------------------------------------------

    public void register(PvPBotEntity bot) {
        bots.put(bot.getName(), bot);
        deathHandled.remove(bot.getName());
        retryQueue.removeIf(entry -> entry.name().equalsIgnoreCase(bot.getName()));
        synchronized (spawnQueue) {
            spawnQueue.removeIf(entry -> entry.name().equalsIgnoreCase(bot.getName()));
        }
        PvPBotMod.LOGGER.info("[PvPBot] Registered bot: {}", bot.getName());
    }

    public void unregister(String name) {
        bots.remove(name);
        retryQueue.removeIf(entry -> entry.name().equalsIgnoreCase(name));
        synchronized (spawnQueue) {
            spawnQueue.removeIf(entry -> entry.name().equalsIgnoreCase(name));
        }
        PvPBotMod.LOGGER.info("[PvPBot] Unregistered bot: {}", name);
    }

    public PvPBotEntity get(String name)    { return bots.get(name); }
    public boolean exists(String name)      { return bots.containsKey(name); }
    public Collection<PvPBotEntity> getAll(){ return Collections.unmodifiableCollection(bots.values()); }
    public Collection<String> getBotNames() { return Collections.unmodifiableSet(bots.keySet()); }

    // -------------------------------------------------------------------------
    // Retry queue
    // -------------------------------------------------------------------------

    public void scheduleRetry(String name, ServerWorld world, Vec3d pos, BotConfig config, int delayTicks) {
        retryQueue.removeIf(entry -> entry.name().equalsIgnoreCase(name));
        retryQueue.add(new RetryEntry(name, world, pos, config, retryTickCounter + delayTicks, 0));
        PvPBotMod.LOGGER.info("[PvPBot] Registration check scheduled for '{}' in {} ticks", name, delayTicks);
    }

    // -------------------------------------------------------------------------
    // Spawn queue
    // -------------------------------------------------------------------------

    public boolean queueSpawn(ServerWorld world, String name, Vec3d pos, BotConfig config) {
        if (exists(name) || world.getServer().getPlayerManager().getPlayer(name) != null) return false;
        synchronized (spawnQueue) {
            if (spawnQueue.size() >= MAX_SPAWN_QUEUE_SIZE) return false;
            for (SpawnEntry entry : spawnQueue) {
                if (entry.name().equalsIgnoreCase(name)) return false;
            }
            spawnQueue.add(new SpawnEntry(world, name, pos, config));
            return true;
        }
    }

    public int getQueuedSpawnCount() {
        synchronized (spawnQueue) {
            return spawnQueue.size();
        }
    }

    // -------------------------------------------------------------------------
    // Tick
    // -------------------------------------------------------------------------

    public void tickAll(MinecraftServer server) {
        retryTickCounter++;
        processSpawnQueue(server);
        processRegistrationRetries(server);
        tickRegisteredBots(server);
    }

    private void processSpawnQueue(MinecraftServer server) {
        if (retryTickCounter < nextSpawnQueueTick) return;

        SpawnEntry entry;
        synchronized (spawnQueue) {
            entry = spawnQueue.poll();
        }
        if (entry == null) return;

        nextSpawnQueueTick = retryTickCounter + SPAWN_QUEUE_INTERVAL_TICKS;
        if (exists(entry.name()) || server.getPlayerManager().getPlayer(entry.name()) != null) return;
        BotSpawner.spawn(server, entry.world(), entry.name(), entry.pos(), entry.config());
    }

    private void processRegistrationRetries(MinecraftServer server) {
        List<RetryEntry> reschedule = new ArrayList<>();
        retryQueue.removeIf(entry -> {
            if (retryTickCounter < entry.dueTick()) return false;
            if (exists(entry.name())) return true;

            var player = server.getPlayerManager().getPlayer(entry.name());
            if (player instanceof EntityPlayerMPFake fake) {
                PvPBotEntity botEntity = new PvPBotEntity(fake, entry.config());
                bots.put(botEntity.getName(), botEntity);
                deathHandled.remove(botEntity.getName());
                synchronized (spawnQueue) {
                    spawnQueue.removeIf(spawn -> spawn.name().equalsIgnoreCase(botEntity.getName()));
                }
                PvPBotMod.LOGGER.info("[PvPBot] Registration check succeeded for '{}'", entry.name());
                return true;
            }

            int nextAttempt = entry.attempts() + 1;
            if (nextAttempt >= MAX_REGISTRATION_CHECKS) {
                PvPBotMod.LOGGER.warn("[PvPBot] Registration check failed for '{}' after {} attempts",
                        entry.name(), nextAttempt);
                return true;
            }

            reschedule.add(new RetryEntry(entry.name(), entry.world(), entry.pos(), entry.config(),
                    retryTickCounter + REGISTRATION_RETRY_INTERVAL_TICKS, nextAttempt));
            return true;
        });
        retryQueue.addAll(reschedule);
    }

    private void tickRegisteredBots(MinecraftServer server) {
        bots.entrySet().removeIf(entry -> {
            PvPBotEntity bot = entry.getValue();
            EntityPlayerMPFake fake = bot.getFakePlayer();

            boolean died = hasBotDied(bot);
            if (died) {
                DebugSystem.getInstance().broadcast(
                    "hasBotDied=true for '" + bot.getName() + "' — " +
                    "fake=" + (fake == null ? "null" : "ok") +
                    (fake != null ? " removed=" + fake.isRemoved() + " alive=" + fake.isAlive() + " hp=" + fake.getHealth() : "")
                );
                handleBotDeath(server, bot);
                FactionManager.getInstance().removeBot(entry.getKey());
                PvPBotMod.LOGGER.info("[PvPBot] Bot {} died/left, unregistering.", entry.getKey());
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

    private boolean hasBotDied(PvPBotEntity bot) {
        EntityPlayerMPFake fake = bot.getFakePlayer();
        return fake == null || fake.isRemoved() || !fake.isAlive() || fake.getHealth() <= 0.0f;
    }

    private void handleBotDeath(MinecraftServer server, PvPBotEntity bot) {
        EntityPlayerMPFake fake = bot.getFakePlayer();
        String name = bot.getName();

        if (!deathHandled.add(name)) {
            DebugSystem.getInstance().broadcast("handleBotDeath: '" + name + "' already handled, skipping.");
            return;
        }

        DebugSystem.getInstance().broadcast(
            "handleBotDeath: '" + name + "' leaveOnDeath=" + bot.getConfig().leaveOnDeath +
            " fake=" + (fake == null ? "null" : "ok")
        );

        // Death message and disconnect are both handled in PvPBotMod's ALLOW_DEATH
        // event, which fires before onDeath() while the entity is still registered.
        // Nothing to broadcast here — it already happened.

        // leaveOnDeath disconnect is handled in PvPBotMod's ALLOW_DEATH event at the
        // moment health first hits zero — before the entity is dead — because this
        // fork's NetHandlerPlayServerFake routes disconnect() through kill(), which
        // is a no-op on an already-dead entity. Nothing to do here.
        DebugSystem.getInstance().broadcast("handleBotDeath cleanup for '" + name + "' (disconnect already fired if leaveOnDeath=true)");
    }
}