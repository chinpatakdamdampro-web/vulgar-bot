package com.pvpbot.entity;

import carpet.patches.EntityPlayerMPFake;
import com.pvpbot.PvPBotMod;
import com.pvpbot.config.BotConfig;
import com.pvpbot.faction.FactionManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BotManager — singleton registry + tick driver.
 *
 * Handles delayed registration checks, throttled spawn queue work, and clean
 * fake-player leave-on-death behaviour.
 */
public class BotManager {

    private static final BotManager INSTANCE = new BotManager();
    public static BotManager getInstance() { return INSTANCE; }

    private static final int REGISTRATION_RETRY_INTERVAL_TICKS = 20;
    private static final int MAX_REGISTRATION_CHECKS = 5;
    private static final int SPAWN_QUEUE_INTERVAL_TICKS = 5;
    private static final int MAX_SPAWN_QUEUE_SIZE = 128;

    /**
     * How many ticks to wait after health hits zero before calling
     * fakePlayerDisconnect. This lets Carpet finish its own onDeath()
     * sequence (death message, item drops, stat tracking) before we
     * disconnect the fake player. One tick is enough; two is safer.
     */
    private static final int DEATH_DISCONNECT_DELAY_TICKS = 2;

    private final Map<String, PvPBotEntity> bots = new ConcurrentHashMap<>();
    private final Set<String> deathHandled = ConcurrentHashMap.newKeySet();

    private record RetryEntry(String name, ServerWorld world, Vec3d pos, BotConfig config,
                              int dueTick, int attempts) {}
    private final List<RetryEntry> retryQueue = Collections.synchronizedList(new ArrayList<>());

    private record SpawnEntry(ServerWorld world, String name, Vec3d pos, BotConfig config) {}
    private final Queue<SpawnEntry> spawnQueue = new ArrayDeque<>();

    /**
     * Bots that have died and are waiting DEATH_DISCONNECT_DELAY_TICKS before
     * their fake player is disconnected. Key = bot name, value = the tick on
     * which the disconnect should fire.
     */
    private final Map<String, Integer> pendingDisconnects = new ConcurrentHashMap<>();

    private int retryTickCounter = 0;
    private int nextSpawnQueueTick = 0;

    private BotManager() {}

    // -------------------------------------------------------------------------
    // Registration
    // -------------------------------------------------------------------------

    public void register(PvPBotEntity bot) {
        bots.put(bot.getName(), bot);
        deathHandled.remove(bot.getName());
        pendingDisconnects.remove(bot.getName());
        retryQueue.removeIf(entry -> entry.name().equalsIgnoreCase(bot.getName()));
        synchronized (spawnQueue) {
            spawnQueue.removeIf(entry -> entry.name().equalsIgnoreCase(bot.getName()));
        }
        PvPBotMod.LOGGER.info("[PvPBot] Registered bot: {}", bot.getName());
    }

    public void unregister(String name) {
        bots.remove(name);
        pendingDisconnects.remove(name);
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

    /**
     * Schedules registration checks only. It does not repeatedly call createFake,
     * preventing retry storms when Carpet fake-player registration is delayed.
     */
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
        processPendingDisconnects(server);
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
            if (hasBotDied(bot)) {
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

    /**
     * Carpet fake players can remain connected for the death-screen tick before
     * their entity becomes removed. Health reaching zero is therefore the first
     * reliable server-side signal that the leave-on-death option must act on.
     */
    private boolean hasBotDied(PvPBotEntity bot) {
        EntityPlayerMPFake fake = bot.getFakePlayer();
        return fake == null || fake.isRemoved() || !fake.isAlive() || fake.getHealth() <= 0.0f;
    }

    /**
     * Broadcasts the death message immediately, then schedules the disconnect
     * for DEATH_DISCONNECT_DELAY_TICKS later.
     *
     * The delay is necessary because when health first hits 0, Carpet's own
     * onDeath() sequence (item drops, stat updates, death message packets) has
     * not necessarily completed yet. Calling fakePlayerDisconnect() in the same
     * tick races with that sequence and causes the disconnect to silently fail —
     * the player manager's getPlayer() check succeeds but the internal state is
     * inconsistent, so Carpet's network handler swallows the disconnect.
     *
     * By scheduling the call via server.execute() one tick later, we allow
     * Carpet to complete its death sequence on this tick, and our disconnect
     * fires cleanly on the next server tick.
     */
    private void handleBotDeath(MinecraftServer server, PvPBotEntity bot) {
        EntityPlayerMPFake fake = bot.getFakePlayer();
        String name = bot.getName();
        if (!deathHandled.add(name)) return;

        // Broadcast death message immediately (this part is safe mid-tick).
        Text deathMessage;
        try {
            deathMessage = fake != null
                    ? fake.getDamageTracker().getDeathMessage()
                    : Text.literal(name + " died");
        } catch (Exception e) {
            deathMessage = Text.literal(name + " died");
        }
        server.getPlayerManager().broadcast(deathMessage, false);

        // Schedule the disconnect for DEATH_DISCONNECT_DELAY_TICKS later.
        // We do NOT call fakePlayerDisconnect() here directly — see javadoc above.
        if (bot.getConfig().leaveOnDeath && fake != null) {
            pendingDisconnects.put(name, retryTickCounter + DEATH_DISCONNECT_DELAY_TICKS);
            PvPBotMod.LOGGER.info("[PvPBot] Scheduled disconnect for '{}' in {} ticks",
                    name, DEATH_DISCONNECT_DELAY_TICKS);
        }
    }

    /**
     * Fires pending disconnects whose delay has elapsed.
     *
     * We no longer check server.getPlayerManager().getPlayer(name) == fake
     * because the player-manager identity check can fail in the same tick that
     * Carpet is doing its own internal housekeeping. Instead we check that the
     * fake player object itself is non-null. If Carpet already disconnected it
     * (e.g. on a server with leaveOnDeath=true in CarpetPvP settings),
     * fakePlayerDisconnect() is a safe no-op.
     */
    private void processPendingDisconnects(MinecraftServer server) {
        pendingDisconnects.entrySet().removeIf(entry -> {
            if (retryTickCounter < entry.getValue()) return false; // not yet due

            String name = entry.getKey();
            // Look up the fake player by name — by now it may already be gone
            // (Carpet cleaned it up), which is fine.
            var player = server.getPlayerManager().getPlayer(name);
            if (player instanceof EntityPlayerMPFake fake) {
                PvPBotMod.LOGGER.info("[PvPBot] Disconnecting dead bot '{}'", name);
                fake.fakePlayerDisconnect(Text.empty());
            } else {
                // Player is already gone (Carpet cleaned it up, or it was a real player
                // that somehow shared the name). Either way, nothing to do.
                PvPBotMod.LOGGER.info("[PvPBot] Bot '{}' already disconnected before pending disconnect fired.", name);
            }
            return true; // remove from pending regardless
        });
    }
}
