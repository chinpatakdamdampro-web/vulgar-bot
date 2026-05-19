package com.pvpbot.faction;

import com.pvpbot.entity.BotManager;
import com.pvpbot.entity.PvPBotEntity;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FactionManager — groups bots into named teams.
 *
 * Factions are in-memory only (reset on server restart).
 * Each bot can belong to at most ONE faction at a time.
 *
 * Commands:
 *   /pb faction create <name>
 *   /pb faction add <botName> <factionName>
 *   /pb faction addall <factionName>
 *   /pb faction givekit <kitName> <factionName>
 *   /pb faction list
 *   /pb faction info <factionName>
 *   /pb faction disband <factionName>
 */
public class FactionManager {

    private static final FactionManager INSTANCE = new FactionManager();
    public static FactionManager getInstance() { return INSTANCE; }

    /** faction name → set of bot names */
    private final Map<String, Set<String>> factions = new ConcurrentHashMap<>();

    private FactionManager() {}

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    /** Returns true if created, false if already exists. */
    public boolean createFaction(String name) {
        if (factions.containsKey(name)) return false;
        factions.put(name, ConcurrentHashMap.newKeySet());
        return true;
    }

    // -------------------------------------------------------------------------
    // Add bot
    // -------------------------------------------------------------------------

    public enum AddResult { SUCCESS, FACTION_NOT_FOUND, BOT_NOT_FOUND }

    /**
     * Add a bot to a faction. Removes it from its previous faction first.
     */
    public AddResult addBot(String botName, String factionName) {
        if (!factions.containsKey(factionName)) return AddResult.FACTION_NOT_FOUND;
        PvPBotEntity bot = BotManager.getInstance().get(botName);
        if (bot == null) return AddResult.BOT_NOT_FOUND;

        // Remove from old faction
        if (bot.getFaction() != null) {
            Set<String> old = factions.get(bot.getFaction());
            if (old != null) old.remove(botName);
        }

        factions.get(factionName).add(botName);
        bot.setFaction(factionName);
        return AddResult.SUCCESS;
    }

    /**
     * Add ALL registered bots to a faction.
     * Returns count of bots added.
     */
    public int addAllBots(String factionName) {
        if (!factions.containsKey(factionName)) return -1;
        int count = 0;
        for (PvPBotEntity bot : BotManager.getInstance().getAll()) {
            if (addBot(bot.getName(), factionName) == AddResult.SUCCESS) count++;
        }
        return count;
    }

    // -------------------------------------------------------------------------
    // Query
    // -------------------------------------------------------------------------

    public boolean factionExists(String name) {
        return factions.containsKey(name);
    }

    public Set<String> getMembers(String factionName) {
        Set<String> members = factions.get(factionName);
        return members != null ? Collections.unmodifiableSet(members) : Collections.emptySet();
    }

    public Collection<String> getFactionNames() {
        return Collections.unmodifiableSet(factions.keySet());
    }

    public boolean disband(String factionName) {
        Set<String> members = factions.remove(factionName);
        if (members == null) return false;
        // Clear faction from all member bots
        for (String botName : members) {
            PvPBotEntity bot = BotManager.getInstance().get(botName);
            if (bot != null && factionName.equals(bot.getFaction())) {
                bot.setFaction(null);
            }
        }
        return true;
    }

    /** Remove a single bot from its faction (called when bot is removed). */
    public void removeBot(String botName) {
        for (Set<String> members : factions.values()) {
            members.remove(botName);
        }
    }
}
