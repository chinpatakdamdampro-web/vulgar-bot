package com.pvpbot;

import com.pvpbot.command.PvPBotCommand;
import com.pvpbot.config.PvPBotConfigFile;
import com.pvpbot.entity.BotManager;
import com.pvpbot.kit.KitManager;
import com.pvpbot.util.DebugSystem;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PvPBotMod implements ModInitializer {

    public static final String MOD_ID = "pvpbot";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[PvPBot] Initializing...");

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            DebugSystem.getInstance().setServer(server);
            LOGGER.info("[PvPBot] SERVER_STARTED — loading config...");
            try {
                PvPBotConfigFile.getInstance().load(server);
                LOGGER.info("[PvPBot] Config loaded — difficulty={} revenge={} leaveOnDeath={}",
                        PvPBotConfigFile.getInstance().getDefaultDifficulty(),
                        PvPBotConfigFile.getInstance().getRevengeMode(),
                        PvPBotConfigFile.getInstance().getLeaveOnDeath());
            } catch (Exception e) {
                LOGGER.error("[PvPBot] Failed to load config — using defaults", e);
            }

            LOGGER.info("[PvPBot] Initializing KitManager...");
            try {
                KitManager.getInstance().init(server);
                LOGGER.info("[PvPBot] KitManager ready.");
            } catch (Exception e) {
                LOGGER.error("[PvPBot] KitManager init failed", e);
            }

            LOGGER.info("[PvPBot] Ready. /pb help for commands.");
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            LOGGER.info("[PvPBot] SERVER_STOPPING — active bots: {}",
                    BotManager.getInstance().getBotNames());
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            LOGGER.info("[PvPBot] Registering commands...");
            try {
                PvPBotCommand.register(dispatcher);
                LOGGER.info("[PvPBot] Commands registered.");
            } catch (Exception e) {
                LOGGER.error("[PvPBot] Command registration failed", e);
            }
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            try {
                BotManager.getInstance().tickAll(server);
            } catch (Exception e) {
                LOGGER.error("[PvPBot] Uncaught exception in tick loop — bots may be in a bad state", e);
            }
        });

        LOGGER.info("[PvPBot] Event hooks registered.");
    }
}