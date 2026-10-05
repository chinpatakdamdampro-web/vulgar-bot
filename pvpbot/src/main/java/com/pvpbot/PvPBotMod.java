package com.pvpbot;

import carpet.patches.EntityPlayerMPFake;
import com.pvpbot.command.PvPBotCommand;
import com.pvpbot.config.PvPBotConfigFile;
import com.pvpbot.entity.BotManager;
import com.pvpbot.entity.PvPBotEntity;
import com.pvpbot.kit.KitManager;
import com.pvpbot.util.DebugSystem;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.text.Text;
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

            try {
                KitManager.getInstance().init(server);
                LOGGER.info("[PvPBot] KitManager ready.");
            } catch (Exception e) {
                LOGGER.error("[PvPBot] KitManager init failed", e);
            }

            LOGGER.info("[PvPBot] Ready. /pb help for commands.");
        });

        // AFTER_DEATH fires inside die() after death is fully confirmed — meaning
        // the totem of undying check has already run and did NOT save the entity.
        // Per Fabric API docs, ALLOW_DEATH fires AFTER the totem check, so if a
        // totem saved the bot, ALLOW_DEATH never fires. But we use AFTER_DEATH
        // instead because it fires inside die() which is only reached on a real
        // death, making totem pops impossible to trigger by definition.
        // The death message is broadcast naturally by die() before AFTER_DEATH fires.
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, damageSource) -> {
            if (!(entity instanceof EntityPlayerMPFake fake)) return;

            String name = fake.getName().getString();
            PvPBotEntity bot = BotManager.getInstance().get(name);
            if (bot == null) return;

            // UUID check — only act on our specific registered bot
            if (!bot.getFakePlayer().getUuid().equals(fake.getUuid())) return;

            if (!bot.getConfig().leaveOnDeath) {
                DebugSystem.getInstance().broadcast(
                    "AFTER_DEATH: '" + name + "' leaveOnDeath=false, skipping disconnect.");
                return;
            }

            LOGGER.info("[PvPBot] Bot '{}' confirmed dead — disconnecting.", name);
            DebugSystem.getInstance().broadcast(
                "AFTER_DEATH: '" + name + "' — calling fakePlayerDisconnect");

            fake.fakePlayerDisconnect(Text.empty());

            DebugSystem.getInstance().broadcast("fakePlayerDisconnect returned for '" + name + "'");
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