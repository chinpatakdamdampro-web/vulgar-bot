package com.pvpbot.util;

import com.pvpbot.PvPBotMod;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DebugSystem — global debug mode manager.
 *
 * When debug mode is ON for a player, all /pb commands print
 * verbose step-by-step output directly to that player's chat.
 *
 * Usage:
 *   /pb debug on   — enables debug for the sender
 *   /pb debug off  — disables debug for the sender
 */
public class DebugSystem {

    private static final DebugSystem INSTANCE = new DebugSystem();

    /** Set of player names with debug mode enabled. */
    private final Set<String> debugPlayers = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private DebugSystem() {}

    public static DebugSystem getInstance() { return INSTANCE; }

    public void enable(String playerName)  { debugPlayers.add(playerName); }
    public void disable(String playerName) { debugPlayers.remove(playerName); }
    public boolean isEnabled(String playerName) { return debugPlayers.contains(playerName); }

    /** Debug info — only shown when debug is on, always logged to console. */
    public void log(ServerCommandSource src, String message) {
        PvPBotMod.LOGGER.info("[PvPBot DEBUG] {}", message);
        if (debugPlayers.contains(src.getName())) {
            src.sendFeedback(() -> Text.literal("§8[§bDBG§8] §7" + message), false);
        }
    }

    /** Debug warning — yellow. */
    public void warn(ServerCommandSource src, String message) {
        PvPBotMod.LOGGER.warn("[PvPBot WARN] {}", message);
        if (debugPlayers.contains(src.getName())) {
            src.sendFeedback(() -> Text.literal("§8[§eWRN§8] §e" + message), false);
        }
    }

    /** Error — always shown in chat regardless of debug mode. */
    public void error(ServerCommandSource src, String message) {
        PvPBotMod.LOGGER.error("[PvPBot ERROR] {}", message);
        src.sendFeedback(() -> Text.literal("§8[§cERR§8] §c" + message), false);
    }

    /** Error with exception — shows stack frames from our mod if debug is on. */
    public void error(ServerCommandSource src, String message, Exception e) {
        PvPBotMod.LOGGER.error("[PvPBot ERROR] {}: {}", message, e.getMessage(), e);
        src.sendFeedback(() -> Text.literal("§8[§cERR§8] §c" + message + "§7: " + e.getMessage()), false);
        if (debugPlayers.contains(src.getName())) {
            for (StackTraceElement el : e.getStackTrace()) {
                if (el.toString().contains("pvpbot")) {
                    src.sendFeedback(() -> Text.literal("§8  → §7" + el), false);
                }
            }
        }
    }
}
