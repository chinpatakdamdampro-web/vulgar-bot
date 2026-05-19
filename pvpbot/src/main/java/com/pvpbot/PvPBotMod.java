package com.pvpbot;

import com.pvpbot.command.PvPBotCommand;
import com.pvpbot.config.PvPBotConfigFile;
import com.pvpbot.entity.BotManager;
import com.pvpbot.kit.KitManager;
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
            // Load persistent config first (difficulty default, revenge mode)
            PvPBotConfigFile.getInstance().load(server);
            // Then init kit manager
            KitManager.getInstance().init(server);
            LOGGER.info("[PvPBot] Ready. Default difficulty: {}",
                    PvPBotConfigFile.getInstance().getDefaultDifficulty());
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                PvPBotCommand.register(dispatcher));

        ServerTickEvents.END_SERVER_TICK.register(server ->
                BotManager.getInstance().tickAll(server));

        LOGGER.info("[PvPBot] Ready. /pb help for commands.");
    }
}
