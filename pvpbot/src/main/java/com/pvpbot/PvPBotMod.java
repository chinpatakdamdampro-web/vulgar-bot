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

        // ALLOW_DEATH fires the moment a LivingEntity's health reaches zero,
        // BEFORE LivingEntity.onDeath() is called. At this point the fake player
        // is still fully registered in PlayerManager — the only safe window to
        // call fakePlayerDisconnect() in this fork.
        //
        // We match by UUID so we only act on the specific bot we spawned.
        // We return true so Minecraft still processes the death normally.
        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, damageSource, damageAmount) -> {
            if (!(entity instanceof EntityPlayerMPFake fake)) return true;

            String name = fake.getName().getString();
            PvPBotEntity bot = BotManager.getInstance().get(name);
            if (bot == null) return true;

            // UUID check — only act on our specific registered bot
            if (!bot.getFakePlayer().getUuid().equals(fake.getUuid())) return true;

            if (!bot.getConfig().leaveOnDeath) {
                DebugSystem.getInstance().broadcast(
                    "ALLOW_DEATH: '" + name + "' leaveOnDeath=false, skipping disconnect.");
                return true;
            }

            // Totem guard: if a totem of undying saved the bot, health will be > 0
            // by the time we reach here. Skip the disconnect — the bot survived.
            if (fake.getHealth() > 0.0f) {
                DebugSystem.getInstance().broadcast(
                    "ALLOW_DEATH: '" + name + "' hp=" + fake.getHealth() +
                    " > 0 (totem saved) — skipping disconnect");
                return true;
            }

            LOGGER.info("[PvPBot] Bot '{}' dying — disconnecting.", name);
            DebugSystem.getInstance().broadcast(
                "ALLOW_DEATH: '" + name + "' hp=0 confirmed — calling fakePlayerDisconnect");

            // Do NOT broadcast the death message here — ALLOW_DEATH fires inside
            // onDeath(), which broadcasts it naturally after we return true.
            // Broadcasting here causes a duplicate message in chat.

            fake.fakePlayerDisconnect(Text.empty());

            DebugSystem.getInstance().broadcast("fakePlayerDisconnect returned for '" + name + "'");
            return true; // let normal death proceed (broadcasts death message, handles stats, etc.)
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