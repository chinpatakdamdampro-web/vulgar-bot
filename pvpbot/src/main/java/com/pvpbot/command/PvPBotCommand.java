package com.pvpbot.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.pvpbot.config.BotConfig;
import com.pvpbot.config.PvPBotConfigFile;
import com.pvpbot.entity.BotManager;
import com.pvpbot.entity.BotSpawner;
import com.pvpbot.entity.PvPBotEntity;
import com.pvpbot.faction.FactionManager;
import com.pvpbot.kit.KitManager;
import com.pvpbot.util.DebugSystem;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;

import java.util.Collection;
import java.util.List;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class PvPBotCommand {

    private static final DebugSystem DEBUG = DebugSystem.getInstance();

    private static final SuggestionProvider<ServerCommandSource> BOT_NAMES =
            (ctx, builder) -> { BotManager.getInstance().getBotNames().forEach(builder::suggest); return builder.buildFuture(); };

    private static final SuggestionProvider<ServerCommandSource> KIT_NAMES =
            (ctx, builder) -> { KitManager.getInstance().listKits().forEach(builder::suggest); return builder.buildFuture(); };

    private static final SuggestionProvider<ServerCommandSource> FACTION_NAMES =
            (ctx, builder) -> { FactionManager.getInstance().getFactionNames().forEach(builder::suggest); return builder.buildFuture(); };

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(literal("pb")
            .requires(src -> src.hasPermissionLevel(2))

            // Core
            .then(literal("help").executes(PvPBotCommand::execHelp))
            .then(literal("list").executes(PvPBotCommand::execList))
            .then(literal("debug")
                .then(literal("on") .executes(ctx -> execDebug(ctx, true)))
                .then(literal("off").executes(ctx -> execDebug(ctx, false))))

            // Spawn / Remove
            .then(literal("spawn")
                .then(argument("name", StringArgumentType.word()).executes(PvPBotCommand::execSpawn)))
            .then(literal("massspawn")
                .then(argument("count", IntegerArgumentType.integer(1, 20)).executes(PvPBotCommand::execMassSpawn)))
            .then(literal("remove")
                .then(argument("botName", StringArgumentType.word()).suggests(BOT_NAMES).executes(PvPBotCommand::execRemove)))

            // Control
            .then(literal("stop")
                .then(argument("botName", StringArgumentType.word()).suggests(BOT_NAMES).executes(PvPBotCommand::execStop)))
            .then(literal("attack")
                .then(argument("botName", StringArgumentType.word()).suggests(BOT_NAMES)
                    .then(argument("targetPlayer", EntityArgumentType.player()).executes(PvPBotCommand::execAttack))))
            .then(literal("follow")
                .then(argument("botName", StringArgumentType.word()).suggests(BOT_NAMES)
                    .then(argument("targetPlayer", EntityArgumentType.player()).executes(PvPBotCommand::execFollow))))

            // Info
            .then(literal("status")
                .then(argument("botName", StringArgumentType.word()).suggests(BOT_NAMES).executes(PvPBotCommand::execStatus)))
            .then(literal("inventory")
                .then(argument("botName", StringArgumentType.word()).suggests(BOT_NAMES).executes(PvPBotCommand::execInventory)))
            .then(literal("equip")
                .then(argument("botName", StringArgumentType.word()).suggests(BOT_NAMES).executes(PvPBotCommand::execEquip)))

            // Kit
            .then(literal("savekit")
                .then(argument("kitName", StringArgumentType.word()).executes(PvPBotCommand::execSaveKit)))
            .then(literal("givekit")
                .then(argument("kitName", StringArgumentType.word()).suggests(KIT_NAMES)
                    .then(argument("target", EntityArgumentType.player()).executes(PvPBotCommand::execGiveKit))))
            .then(literal("gb")
                .then(argument("kitName", StringArgumentType.word()).suggests(KIT_NAMES).executes(PvPBotCommand::execGiveBots)))
            .then(literal("listkits").executes(PvPBotCommand::execListKits))

            // /pb diff <bot> <easy|medium|hard|ultrahard>
            .then(literal("diff")
                .then(argument("botName", StringArgumentType.word()).suggests(BOT_NAMES)
                    .then(literal("easy")      .executes(ctx -> execDiff(ctx, "easy")))
                    .then(literal("medium")    .executes(ctx -> execDiff(ctx, "medium")))
                    .then(literal("hard")      .executes(ctx -> execDiff(ctx, "hard")))
                    .then(literal("ultrahard") .executes(ctx -> execDiff(ctx, "ultrahard")))))

            // /pb mode <bot> <crit|combo|smp>
            .then(literal("mode")
                .then(argument("botName", StringArgumentType.word()).suggests(BOT_NAMES)
                    .then(literal("crit") .executes(ctx -> execMode(ctx, "crit")))
                    .then(literal("combo").executes(ctx -> execMode(ctx, "combo")))
                    .then(literal("smp")  .executes(ctx -> execMode(ctx, "smp")))))

            // /pb ai <bot> <legacy|v2>
            .then(literal("ai")
                .then(argument("botName", StringArgumentType.word()).suggests(BOT_NAMES)
                    .then(literal("legacy").executes(ctx -> execAiEngine(ctx, "legacy")))
                    .then(literal("v2")    .executes(ctx -> execAiEngine(ctx, "v2")))))

            // /pb config — persistent server config
            .then(literal("config")
                .then(literal("setdefault")
                    .then(literal("easy")      .executes(ctx -> execConfigSetDefault(ctx, "easy")))
                    .then(literal("medium")    .executes(ctx -> execConfigSetDefault(ctx, "medium")))
                    .then(literal("hard")      .executes(ctx -> execConfigSetDefault(ctx, "hard")))
                    .then(literal("ultrahard") .executes(ctx -> execConfigSetDefault(ctx, "ultrahard"))))
                .then(literal("show").executes(PvPBotCommand::execConfigShow)))

            // /pb settings Revenge <true|false> — GLOBAL, applies to ALL bots
            .then(literal("settings")
                .then(literal("Revenge")
                    .then(literal("true") .executes(ctx -> execSettings(ctx, "Revenge", "true")))
                    .then(literal("false").executes(ctx -> execSettings(ctx, "Revenge", "false")))))

            // /pb attackall <player> — all bots attack without needing factions
            .then(literal("attackall")
                .then(argument("targetPlayer", EntityArgumentType.player()).executes(PvPBotCommand::execAttackAll)))

            // /pb stopall
            .then(literal("stopall").executes(PvPBotCommand::execStopAll))

            // Faction
            .then(literal("faction")
                .then(literal("create")
                    .then(argument("factionName", StringArgumentType.word()).executes(PvPBotCommand::execFactionCreate)))
                .then(literal("add")
                    .then(argument("botName", StringArgumentType.word()).suggests(BOT_NAMES)
                        .then(argument("factionName", StringArgumentType.word()).suggests(FACTION_NAMES)
                            .executes(PvPBotCommand::execFactionAdd))))
                .then(literal("addall")
                    .then(argument("factionName", StringArgumentType.word()).suggests(FACTION_NAMES)
                        .executes(PvPBotCommand::execFactionAddAll)))
                .then(literal("givekit")
                    .then(argument("kitName", StringArgumentType.word()).suggests(KIT_NAMES)
                        .then(argument("factionName", StringArgumentType.word()).suggests(FACTION_NAMES)
                            .executes(PvPBotCommand::execFactionGiveKit))))
                .then(literal("disband")
                    .then(argument("factionName", StringArgumentType.word()).suggests(FACTION_NAMES)
                        .executes(PvPBotCommand::execFactionDisband)))
                .then(literal("list").executes(PvPBotCommand::execFactionList))
                .then(literal("info")
                    .then(argument("factionName", StringArgumentType.word()).suggests(FACTION_NAMES)
                        .executes(PvPBotCommand::execFactionInfo)))
                .then(literal("attack")
                    .then(argument("factionName", StringArgumentType.word()).suggests(FACTION_NAMES)
                        .then(argument("targetPlayer", EntityArgumentType.player())
                            .executes(PvPBotCommand::execFactionAttack)))))
        );
    }

    // =========================================================================
    // Core
    // =========================================================================

    private static int execHelp(CommandContext<ServerCommandSource> ctx) {
        send(ctx, "§6§l╔══ PvPBot Commands ══╗");
        send(ctx, "§e/pb spawn §f<name>               §7Spawn a bot");
        send(ctx, "§e/pb massspawn §f<count>           §7Spawn multiple bots");
        send(ctx, "§e/pb remove §f<bot>                §7Remove a bot");
        send(ctx, "§e/pb stop §f<bot>                  §7Stop bot actions");
        send(ctx, "§e/pb stopall                      §7Stop ALL bots → IDLE");
        send(ctx, "§e/pb attack §f<bot> <player>       §7Attack player");
        send(ctx, "§e/pb attackall §f<player>          §7ALL bots attack player");
        send(ctx, "§e/pb follow §f<bot> <player>       §7Follow player");
        send(ctx, "§e/pb status §f<bot>                §7Bot status");
        send(ctx, "§e/pb inventory §f<bot>             §7Bot hotbar");
        send(ctx, "§e/pb equip §f<bot>                 §7Force gear refresh");
        send(ctx, "§e/pb list                         §7List all bots");
        send(ctx, "§6§l── Difficulty ──");
        send(ctx, "§e/pb diff §f<bot> <easy|medium|hard|ultrahard>");
        send(ctx, "§7  easy§7=slow  §emedium§7=default  §chard§7=fast  §4ultrahard§7=Str3");
        send(ctx, "§6§l── Combat Mode ──");
        send(ctx, "§e/pb mode §f<bot> <crit|combo|smp> §7Set combat style (updated AI tuning)");
        send(ctx, "§6§l── Global Settings ──");
        send(ctx, "§e/pb settings §fRevenge <true|false>  §7Revenge for ALL bots + save");
        send(ctx, "§e/pb config setdefault §f<diff>       §7Default difficulty for new spawns");
        send(ctx, "§e/pb config show                    §7Show current server config");
        send(ctx, "§6§l── Kit ──");
        send(ctx, "§e/pb savekit §f<name>              §7Save YOUR inventory as kit");
        send(ctx, "§e/pb givekit §f<name> <player>     §7Give kit to player/yourself");
        send(ctx, "§e/pb gb §f<kitName>                §7Give kit to ALL bots");
        send(ctx, "§e/pb listkits                     §7List saved kits");
        send(ctx, "§6§l── Faction ──");
        send(ctx, "§e/pb faction create §f<name>       §7Create faction");
        send(ctx, "§e/pb faction add §f<bot> <faction> §7Add bot to faction");
        send(ctx, "§e/pb faction addall §f<faction>    §7Add ALL bots to faction");
        send(ctx, "§e/pb faction givekit §f<kit> <f>  §7Give kit to faction");
        send(ctx, "§e/pb faction disband §f<faction>   §7Disband faction");
        send(ctx, "§e/pb faction list                 §7List factions");
        send(ctx, "§e/pb faction info §f<faction>      §7Faction details");
        send(ctx, "§e/pb faction attack §f<faction> <p>  §7Faction attacks player");
        send(ctx, "§e/pb debug §f<on|off>              §7Toggle verbose debug");
        send(ctx, "§6§l╚══════════════════════╝");
        return 1;
    }

    private static int execDebug(CommandContext<ServerCommandSource> ctx, boolean enable) {
        String name = ctx.getSource().getName();
        if (enable) { DEBUG.enable(name);  send(ctx, "§b[DEBUG] §aEnabled."); }
        else        { DEBUG.disable(name); send(ctx, "§7[DEBUG] Disabled."); }
        return 1;
    }

    private static int execList(CommandContext<ServerCommandSource> ctx) {
        ServerCommandSource src = ctx.getSource();
        Collection<PvPBotEntity> bots = BotManager.getInstance().getAll();

        long stray = src.getServer().getPlayerManager().getPlayerList().stream()
                .filter(p -> p instanceof carpet.patches.EntityPlayerMPFake
                        && !BotManager.getInstance().exists(p.getName().getString()))
                .count();

        if (bots.isEmpty()) {
            send(ctx, "§7No registered bots." + (stray > 0 ? " §e(" + stray + " unregistered fake players in world)" : ""));
            if (stray > 0) send(ctx, "§7Use §f/pb spawn <name>§7 to register them.");
            return 1;
        }
        send(ctx, "§6§lBots (" + bots.size() + "):");
        for (PvPBotEntity bot : bots) {
            String target = bot.getTargetingSystem().getCombatTarget() != null
                    ? " §c→ " + bot.getTargetingSystem().getCombatTarget().getName().getString() : "";
            String faction = bot.getFaction() != null ? " §7[" + bot.getFaction() + "]" : "";
            send(ctx, "§a● §f" + bot.getName() + " §7[" + bot.getState() + "]"
                    + " §7HP:§c" + String.format("%.1f", bot.getFakePlayer().getHealth())
                    + faction + target);
        }
        if (stray > 0) send(ctx, "§e⚠ " + stray + " unregistered fake player(s) also in world.");
        return 1;
    }

    // =========================================================================
    // Spawn
    // =========================================================================

    private static int execSpawn(CommandContext<ServerCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        ServerCommandSource src = ctx.getSource();
        DEBUG.log(src, "spawn('" + name + "')");

        if (BotManager.getInstance().exists(name)) {
            sendError(ctx, "Bot §f'" + name + "'§c already registered. /pb remove first.");
            return 0;
        }

        var existing = src.getServer().getPlayerManager().getPlayer(name);
        if (existing != null && !(existing instanceof carpet.patches.EntityPlayerMPFake)) {
            sendError(ctx, "Real player '§f" + name + "§c' is online — pick a different name.");
            return 0;
        }

        send(ctx, "§7Spawning §f" + name + "§7...");
        Vec3d pos = src.getPosition();

        // Use difficulty from persistent config file
        BotConfig cfg = PvPBotConfigFile.getInstance().buildDefaultConfig();
        PvPBotEntity bot = BotSpawner.spawn(src.getServer(), src.getWorld(), name, pos, cfg);

        if (bot != null) {
            sendSuccess(ctx, "Bot §f" + name + "§a spawned and registered!");
            DEBUG.log(src, "Registered bots: " + BotManager.getInstance().getBotNames());
            send(ctx, "§7Try: §f/pb attack " + name + " <yourname>");
        } else {
            send(ctx, "§e⚠ Bot §f" + name + "§e joined the world but registration is pending.");
            send(ctx, "§7Auto-retry in §f4 seconds§7 — watch for success message.");
            send(ctx, "§7If it fails: §f/pb spawn " + name + " §7again.");
            DEBUG.log(src, "Retry queued for: " + name);
        }
        return 1;
    }

    private static int execMassSpawn(CommandContext<ServerCommandSource> ctx) {
        int count = IntegerArgumentType.getInteger(ctx, "count");
        ServerCommandSource src = ctx.getSource();

        List<String> names = BotSpawner.getBotNames();
        if (names.isEmpty()) {
            sendError(ctx, "botnames.txt is empty or not found.");
            return 0;
        }

        send(ctx, "§7Mass-spawning §f" + count + "§7 bots...");
        DEBUG.log(src, "massSpawn count=" + count + ", available names=" + names.size());

        BotConfig cfg = PvPBotConfigFile.getInstance().buildDefaultConfig();
        List<String> attempted = BotSpawner.massSpawn(
                src.getServer(), src.getWorld(), src.getPosition(), count, cfg);

        sendSuccess(ctx, "Spawned §f" + attempted.size() + "§a bots: §7" + String.join(", ", attempted));
        send(ctx, "§7Registration may take §f4 seconds §7for each bot to appear.");
        if (attempted.size() < count) {
            send(ctx, "§e⚠ Only §f" + attempted.size() + "§e names available (wanted " + count + ").");
            send(ctx, "§7Add more names to §fbotnames.txt§7 in resources.");
        }
        return 1;
    }

    private static int execRemove(CommandContext<ServerCommandSource> ctx) {
        String botName = StringArgumentType.getString(ctx, "botName");
        ServerCommandSource src = ctx.getSource();
        DEBUG.log(src, "remove('" + botName + "')");

        boolean removed = BotSpawner.remove(src.getServer(), botName);
        if (removed) {
            sendSuccess(ctx, "Bot §f" + botName + "§a removed.");
        } else {
            sendError(ctx, "No bot named §f'" + botName + "'§c.");
            send(ctx, "§7Active: §f" + String.join(", ", BotManager.getInstance().getBotNames()));
        }
        return removed ? 1 : 0;
    }

    // =========================================================================
    // Control
    // =========================================================================

    private static int execStop(CommandContext<ServerCommandSource> ctx) {
        String botName = StringArgumentType.getString(ctx, "botName");
        PvPBotEntity bot = getBot(ctx, botName);
        if (bot == null) return 0;
        bot.stop();
        sendSuccess(ctx, "§f" + botName + "§a stopped → IDLE.");
        return 1;
    }

    private static int execAttack(CommandContext<ServerCommandSource> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String botName = StringArgumentType.getString(ctx, "botName");
        ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "targetPlayer");
        ServerCommandSource src = ctx.getSource();
        DEBUG.log(src, "attack bot='" + botName + "' target='" + target.getName().getString() + "'");

        PvPBotEntity bot = getBot(ctx, botName);
        if (bot == null) return 0;
        if (bot.getFakePlayer().getName().getString().equals(target.getName().getString())) {
            sendError(ctx, "A bot cannot attack itself."); return 0;
        }
        bot.startAttacking(target);
        sendSuccess(ctx, "§f" + botName + "§a → attacking §f" + target.getName().getString());
        send(ctx, "§7Pattern: §f" + bot.getCombat().getCurrentPatternName()
                + " §7| Crit: §f" + bot.getConfig().critChancePercent + "%");
        return 1;
    }

    private static int execFollow(CommandContext<ServerCommandSource> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String botName = StringArgumentType.getString(ctx, "botName");
        ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "targetPlayer");
        PvPBotEntity bot = getBot(ctx, botName);
        if (bot == null) return 0;
        bot.startFollowing(target);
        sendSuccess(ctx, "§f" + botName + "§a → following §f" + target.getName().getString());
        return 1;
    }

    // =========================================================================
    // Info
    // =========================================================================

    private static int execStatus(CommandContext<ServerCommandSource> ctx) {
        String botName = StringArgumentType.getString(ctx, "botName");
        PvPBotEntity bot = getBot(ctx, botName);
        if (bot == null) return 0;
        var fp = bot.getFakePlayer();
        String ct = bot.getTargetingSystem().getCombatTarget() != null
                ? bot.getTargetingSystem().getCombatTarget().getName().getString() : "none";
        String ft = bot.getTargetingSystem().getFollowTarget() != null
                ? bot.getTargetingSystem().getFollowTarget().getName().getString() : "none";
        send(ctx, "§6§l── " + botName + " ──");
        send(ctx, "§7State:    §f" + bot.getState());
        send(ctx, "§7Difficulty: " + bot.getConfig().difficultyDisplayName());
        send(ctx, "§7HP:       §c" + String.format("%.1f/%.1f", fp.getHealth(), fp.getMaxHealth()));
        send(ctx, "§7Hunger:   §6" + fp.getHungerManager().getFoodLevel() + "/20");
        send(ctx, "§7Target:   §f" + ct);
        send(ctx, "§7Follow:   §f" + ft);
        send(ctx, "§7Pattern:  §f" + bot.getCombat().getCurrentPatternName());
        send(ctx, "§7Shield:   §f" + bot.getCombat().isShielding());
        send(ctx, "§7Mode:     §f" + bot.getConfig().mode.name());
        send(ctx, "§7Revenge:  §f" + bot.getConfig().revengeMode);
        send(ctx, "§7BreachMace: §f" + (bot.getInventory().hasBreachMace() ? "§aYES" : "§7none"));
        send(ctx, "§7Faction:  §f" + (bot.getFaction() != null ? bot.getFaction() : "none"));
        send(ctx, "§7Pos:      §f" + String.format("%.1f, %.1f, %.1f", fp.getX(), fp.getY(), fp.getZ()));
        return 1;
    }

    private static int execInventory(CommandContext<ServerCommandSource> ctx) {
        String botName = StringArgumentType.getString(ctx, "botName");
        PvPBotEntity bot = getBot(ctx, botName);
        if (bot == null) return 0;
        var inv = bot.getFakePlayer().getInventory();
        send(ctx, "§6§l" + botName + "'s Hotbar (selected=" + inv.selectedSlot + "):");
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            String sel = (i == inv.selectedSlot) ? "§a►" : "§7 ";
            send(ctx, sel + " §7[" + i + "] §f" + stackName(stack));
        }
        return 1;
    }

    private static int execEquip(CommandContext<ServerCommandSource> ctx) {
        String botName = StringArgumentType.getString(ctx, "botName");
        PvPBotEntity bot = getBot(ctx, botName);
        if (bot == null) return 0;
        bot.getInventory().equipBestWeapon(false);
        var fp = bot.getFakePlayer();
        sendSuccess(ctx, "§f" + botName + "§a gear refreshed:");
        send(ctx, "§7 Helmet:     §f" + stackName(fp.getEquippedStack(EquipmentSlot.HEAD)));
        send(ctx, "§7 Chestplate: §f" + stackName(fp.getEquippedStack(EquipmentSlot.CHEST)));
        send(ctx, "§7 Leggings:   §f" + stackName(fp.getEquippedStack(EquipmentSlot.LEGS)));
        send(ctx, "§7 Boots:      §f" + stackName(fp.getEquippedStack(EquipmentSlot.FEET)));
        send(ctx, "§7 Mainhand:   §f" + stackName(fp.getEquippedStack(EquipmentSlot.MAINHAND)));
        send(ctx, "§7 Offhand:    §f" + stackName(fp.getEquippedStack(EquipmentSlot.OFFHAND)));
        return 1;
    }

    // =========================================================================
    // Kit
    // =========================================================================

    private static int execSaveKit(CommandContext<ServerCommandSource> ctx) {
        String kitName = StringArgumentType.getString(ctx, "kitName");
        ServerCommandSource src = ctx.getSource();

        ServerPlayerEntity player;
        try { player = src.getPlayerOrThrow(); }
        catch (Exception e) { sendError(ctx, "Must be run by a player."); return 0; }

        DEBUG.log(src, "savekit name='" + kitName + "' player='" + player.getName().getString() + "'");
        String err = KitManager.getInstance().saveKit(kitName, player);
        if (err != null) { sendError(ctx, err); return 0; }

        sendSuccess(ctx, "Kit §f'" + kitName + "'§a saved! Includes full inventory + armor + offhand.");
        send(ctx, "§7Use §f/pb givekit " + kitName + " <player>§7 to apply it.");
        return 1;
    }

    private static int execGiveKit(CommandContext<ServerCommandSource> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String kitName = StringArgumentType.getString(ctx, "kitName");
        ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "target");
        ServerCommandSource src = ctx.getSource();

        DEBUG.log(src, "givekit '" + kitName + "' → '" + target.getName().getString() + "'");
        if (!KitManager.getInstance().kitExists(kitName)) {
            sendError(ctx, "Kit §f'" + kitName + "'§c not found. Use §f/pb listkits§c.");
            return 0;
        }
        String err = KitManager.getInstance().giveKit(kitName, target);
        if (err != null) { sendError(ctx, err); return 0; }
        sendSuccess(ctx, "Kit §f'" + kitName + "'§a given to §f" + target.getName().getString());
        return 1;
    }

    private static int execGiveBots(CommandContext<ServerCommandSource> ctx) {
        String kitName = StringArgumentType.getString(ctx, "kitName");
        ServerCommandSource src = ctx.getSource();

        if (!KitManager.getInstance().kitExists(kitName)) {
            sendError(ctx, "Kit §f'" + kitName + "'§c not found."); return 0;
        }

        Collection<PvPBotEntity> bots = BotManager.getInstance().getAll();
        if (bots.isEmpty()) { sendError(ctx, "No registered bots."); return 0; }

        int ok = 0, fail = 0;
        for (PvPBotEntity bot : bots) {
            String err = KitManager.getInstance().giveKit(kitName, bot.getFakePlayer());
            if (err == null) ok++; else { fail++; DEBUG.error(src, "giveBot " + bot.getName() + ": " + err); }
        }
        sendSuccess(ctx, "Kit §f'" + kitName + "'§a given to §f" + ok + "§a bots."
                + (fail > 0 ? " §c" + fail + " failed." : ""));
        return 1;
    }

    private static int execListKits(CommandContext<ServerCommandSource> ctx) {
        List<String> kits = KitManager.getInstance().listKits();
        if (kits.isEmpty()) { send(ctx, "§7No kits saved. Use §f/pb savekit <name>§7."); return 1; }
        send(ctx, "§6§lSaved kits (" + kits.size() + "):");
        kits.forEach(k -> send(ctx, "§7 • §f" + k));
        return 1;
    }

    // =========================================================================
    // /pb mode <bot> <crit|combo|smp>
    // FIX: now wired into command tree above
    // =========================================================================

    private static int execMode(CommandContext<ServerCommandSource> ctx, String modeName) {
        String botName = StringArgumentType.getString(ctx, "botName");
        PvPBotEntity bot = getBot(ctx, botName);
        if (bot == null) return 0;

        BotConfig.BotMode mode = switch (modeName.toLowerCase()) {
            case "crit"  -> BotConfig.BotMode.CRIT;
            case "smp"   -> BotConfig.BotMode.SMP;
            default       -> BotConfig.BotMode.COMBO;
        };

        bot.getConfig().mode = mode;
        bot.getConfig().applyMode();

        sendSuccess(ctx, "§f" + botName + "§a mode → §f" + mode.name());
        switch (mode) {
            case CRIT  -> send(ctx, "§7High crit frequency. Less strafing. Aggressive.");
            case COMBO -> send(ctx, "§7Balanced combos + crits. Default behaviour.");
            case SMP   -> send(ctx, "§7Less strafe. Shield prediction. More deliberate hits.");
        }
        send(ctx, "§7Crit: §f" + bot.getConfig().critChancePercent
                + "% §7| Cooldown: §f" + bot.getConfig().attackCooldownTicks + " ticks");
        return 1;
    }

    // =========================================================================
    // /pb settings Revenge <true|false>
    // GLOBAL — applies to ALL registered bots at once, no bot name argument.
    // =========================================================================

    private static int execSettings(CommandContext<ServerCommandSource> ctx,
                                     String setting, String value) {
        Collection<PvPBotEntity> bots = BotManager.getInstance().getAll();

        switch (setting) {
            case "Revenge" -> {
                boolean rev = value.equals("true");
                // Apply to all currently registered bots
                for (PvPBotEntity bot : bots) {
                    bot.getConfig().revengeMode = rev;
                }
                // Persist to config so new bots also get this default
                PvPBotConfigFile.getInstance().setRevengeMode(rev);

                if (bots.isEmpty()) {
                    send(ctx, "§7No bots running — setting saved for future spawns.");
                } else {
                    sendSuccess(ctx, "§aRevenge → §f" + rev
                            + " §7(applied to §f" + bots.size() + "§7 bot"
                            + (bots.size() == 1 ? "" : "s") + " + saved as default)");
                }
                if (rev) send(ctx, "§7All bots will auto-attack any player that hits them.");
                else     send(ctx, "§7All bots will ignore hits unless commanded to attack.");
            }
            default -> sendError(ctx, "Unknown setting: §f" + setting + "§c. Available: §fRevenge");
        }
        return 1;
    }

    // =========================================================================
    // /pb diff <bot> <easy|medium|hard|ultrahard>
    // =========================================================================


    private static int execAiEngine(CommandContext<ServerCommandSource> ctx, String engineName) {
        String botName = StringArgumentType.getString(ctx, "botName");
        PvPBotEntity bot = getBot(ctx, botName);
        if (bot == null) return 0;

        BotConfig.CombatEngine engine = switch (engineName.toLowerCase()) {
            case "v2" -> BotConfig.CombatEngine.V2;
            default   -> BotConfig.CombatEngine.LEGACY;
        };

        bot.setCombatEngine(engine);
        sendSuccess(ctx, "§f" + botName + "§a combat engine → §f" + engine.name() + " §7(safe mode)");
        if (engine == BotConfig.CombatEngine.LEGACY) {
            send(ctx, "§7Legacy engine: maximum stability.");
        } else {
            send(ctx, "§7V2 engine selected. Currently compatibility mode (no risky behavior override yet).");
        }
        return 1;
    }


    private static int execDiff(CommandContext<ServerCommandSource> ctx, String diffName) {
        String botName = StringArgumentType.getString(ctx, "botName");
        PvPBotEntity bot = getBot(ctx, botName);
        if (bot == null) return 0;

        BotConfig.Difficulty diff = switch (diffName.toLowerCase()) {
            case "easy"      -> BotConfig.Difficulty.EASY;
            case "hard"      -> BotConfig.Difficulty.HARD;
            case "ultrahard" -> BotConfig.Difficulty.ULTRA_HARD;
            default          -> BotConfig.Difficulty.MEDIUM;
        };

        bot.setDifficulty(diff);
        sendSuccess(ctx, "§f" + botName + "§a difficulty → " + bot.getConfig().difficultyDisplayName());
        switch (diff) {
            case EASY       -> send(ctx, "§7Slow attacks, no crits, no potions. Beginner friendly.");
            case MEDIUM     -> send(ctx, "§7Balanced behaviour. Default.");
            case HARD       -> send(ctx, "§7Fast attacks, high crits, aggressive heal threshold.");
            case ULTRA_HARD -> send(ctx, "§4§lPermanent Strength III applied. Hits like a truck.");
        }
        return 1;
    }

    // =========================================================================
    // /pb config setdefault <diff>
    // /pb config setrevenge <true|false>
    // /pb config show
    // =========================================================================

    private static int execConfigSetDefault(CommandContext<ServerCommandSource> ctx, String diffName) {
        PvPBotConfigFile.getInstance().setDefaultDifficulty(diffName);
        sendSuccess(ctx, "§aDefault difficulty → §f" + diffName.toUpperCase()
                + " §7(applies to newly spawned bots)");
        send(ctx, "§7Saved to §fpvpbot_config.json");
        return 1;
    }

    private static int execConfigShow(CommandContext<ServerCommandSource> ctx) {
        PvPBotConfigFile cfg = PvPBotConfigFile.getInstance();
        send(ctx, "§6§l── PvPBot Config ──");
        send(ctx, "§7defaultDifficulty: §f" + cfg.getDefaultDifficulty().toUpperCase());
        send(ctx, "§7revengeMode:       §f" + cfg.getRevengeMode());
        send(ctx, "§7Saved at: §fpvpbot_config.json §7in world folder");
        send(ctx, "§7Change with: §f/pb config setdefault <diff>§7 or §f/pb settings Revenge <true|false>");
        return 1;
    }

    // =========================================================================
    // /pb attackall <player>  — all bots attack one player without needing factions
    // =========================================================================

    private static int execAttackAll(CommandContext<ServerCommandSource> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "targetPlayer");
        ServerCommandSource src = ctx.getSource();
        Collection<PvPBotEntity> bots = BotManager.getInstance().getAll();
        if (bots.isEmpty()) { sendError(ctx, "No registered bots."); return 0; }

        int ordered = 0;
        for (PvPBotEntity bot : bots) {
            if (bot.getFakePlayer().getName().getString().equals(target.getName().getString())) continue;
            bot.startAttacking(target);
            ordered++;
        }
        sendSuccess(ctx, "§f" + ordered + "§a bot" + (ordered == 1 ? "" : "s")
                + "§a → attacking §f" + target.getName().getString());
        DEBUG.log(src, "attackall: ordered " + ordered + " bots");
        return 1;
    }

    // =========================================================================
    // /pb stopall
    // =========================================================================

    private static int execStopAll(CommandContext<ServerCommandSource> ctx) {
        Collection<PvPBotEntity> bots = BotManager.getInstance().getAll();
        if (bots.isEmpty()) { sendError(ctx, "No registered bots."); return 0; }
        bots.forEach(PvPBotEntity::stop);
        sendSuccess(ctx, "All §f" + bots.size() + "§a bot(s) stopped → IDLE.");
        return 1;
    }

    private static int execFactionCreate(CommandContext<ServerCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "factionName");
        boolean created = FactionManager.getInstance().createFaction(name);
        if (created) sendSuccess(ctx, "Faction §f'" + name + "'§a created.");
        else sendError(ctx, "Faction §f'" + name + "'§c already exists.");
        return created ? 1 : 0;
    }

    private static int execFactionAdd(CommandContext<ServerCommandSource> ctx) {
        String botName     = StringArgumentType.getString(ctx, "botName");
        String factionName = StringArgumentType.getString(ctx, "factionName");
        ServerCommandSource src = ctx.getSource();
        DEBUG.log(src, "faction add bot='" + botName + "' faction='" + factionName + "'");

        switch (FactionManager.getInstance().addBot(botName, factionName)) {
            case SUCCESS          -> sendSuccess(ctx, "§f" + botName + "§a added to faction §f" + factionName);
            case FACTION_NOT_FOUND-> sendError(ctx, "Faction §f'" + factionName + "'§c not found. Create first: §f/pb faction create " + factionName);
            case BOT_NOT_FOUND    -> { sendError(ctx, "Bot §f'" + botName + "'§c not found."); getBot(ctx, botName); }
        }
        return 1;
    }

    private static int execFactionAddAll(CommandContext<ServerCommandSource> ctx) {
        String factionName = StringArgumentType.getString(ctx, "factionName");
        int count = FactionManager.getInstance().addAllBots(factionName);
        if (count < 0) { sendError(ctx, "Faction §f'" + factionName + "'§c not found."); return 0; }
        sendSuccess(ctx, "Added §f" + count + "§a bots to faction §f" + factionName);
        return 1;
    }

    private static int execFactionGiveKit(CommandContext<ServerCommandSource> ctx) {
        String kitName     = StringArgumentType.getString(ctx, "kitName");
        String factionName = StringArgumentType.getString(ctx, "factionName");
        ServerCommandSource src = ctx.getSource();

        if (!KitManager.getInstance().kitExists(kitName)) {
            sendError(ctx, "Kit §f'" + kitName + "'§c not found."); return 0;
        }
        if (!FactionManager.getInstance().factionExists(factionName)) {
            sendError(ctx, "Faction §f'" + factionName + "'§c not found."); return 0;
        }

        int ok = 0, fail = 0;
        for (String botName : FactionManager.getInstance().getMembers(factionName)) {
            PvPBotEntity bot = BotManager.getInstance().get(botName);
            if (bot == null) { fail++; continue; }
            String err = KitManager.getInstance().giveKit(kitName, bot.getFakePlayer());
            if (err == null) ok++; else { fail++; DEBUG.error(src, botName + ": " + err); }
        }
        sendSuccess(ctx, "Kit §f'" + kitName + "'§a given to §f" + ok + "§a members of §f" + factionName
                + (fail > 0 ? "§c (" + fail + " failed)" : "§a."));
        return 1;
    }

    private static int execFactionDisband(CommandContext<ServerCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "factionName");
        boolean disbanded = FactionManager.getInstance().disband(name);
        if (disbanded) sendSuccess(ctx, "Faction §f'" + name + "'§a disbanded.");
        else sendError(ctx, "Faction §f'" + name + "'§c not found.");
        return disbanded ? 1 : 0;
    }

    private static int execFactionList(CommandContext<ServerCommandSource> ctx) {
        Collection<String> factions = FactionManager.getInstance().getFactionNames();
        if (factions.isEmpty()) { send(ctx, "§7No factions. Use §f/pb faction create <name>§7."); return 1; }
        send(ctx, "§6§lFactions (" + factions.size() + "):");
        factions.forEach(f -> {
            int members = FactionManager.getInstance().getMembers(f).size();
            send(ctx, "§7 • §f" + f + " §7(" + members + " members)");
        });
        return 1;
    }

    private static int execFactionInfo(CommandContext<ServerCommandSource> ctx) {
        String name = StringArgumentType.getString(ctx, "factionName");
        if (!FactionManager.getInstance().factionExists(name)) {
            sendError(ctx, "Faction §f'" + name + "'§c not found."); return 0;
        }
        var members = FactionManager.getInstance().getMembers(name);
        send(ctx, "§6§l── Faction: " + name + " ──");
        send(ctx, "§7Members (" + members.size() + "):");
        for (String botName : members) {
            PvPBotEntity bot = BotManager.getInstance().get(botName);
            String status = bot != null
                    ? "§7[" + bot.getState() + "] HP:§c" + String.format("%.1f", bot.getFakePlayer().getHealth())
                    : "§c(unregistered)";
            send(ctx, "§7 • §f" + botName + " " + status);
        }
        return 1;
    }

    private static int execFactionAttack(CommandContext<ServerCommandSource> ctx)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        String factionName = StringArgumentType.getString(ctx, "factionName");
        ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "targetPlayer");
        ServerCommandSource src = ctx.getSource();

        if (!FactionManager.getInstance().factionExists(factionName)) {
            sendError(ctx, "Faction '" + factionName + "' not found."); return 0;
        }

        var members = FactionManager.getInstance().getMembers(factionName);
        if (members.isEmpty()) {
            sendError(ctx, "Faction '" + factionName + "' has no members."); return 0;
        }

        int ordered = 0;
        for (String botName : members) {
            PvPBotEntity bot = BotManager.getInstance().get(botName);
            if (bot == null) continue;
            if (bot.getFakePlayer().getName().getString().equals(target.getName().getString())) continue;
            bot.startAttacking(target);
            ordered++;
        }
        sendSuccess(ctx, "§f" + ordered + "§a members of §f" + factionName
                + "§a → attacking §f" + target.getName().getString());
        DEBUG.log(src, "faction attack: " + ordered + " bots ordered");
        return 1;
    }

    // =========================================================================
    // Shared helpers
    // =========================================================================

    private static PvPBotEntity getBot(CommandContext<ServerCommandSource> ctx, String name) {
        PvPBotEntity bot = BotManager.getInstance().get(name);
        if (bot != null) return bot;

        sendError(ctx, "No registered bot named §f'" + name + "'§c.");
        send(ctx, "§7Registered: §f" + (BotManager.getInstance().getBotNames().isEmpty()
                ? "none" : String.join(", ", BotManager.getInstance().getBotNames())));

        var stray = ctx.getSource().getServer().getPlayerManager().getPlayer(name);
        if (stray instanceof carpet.patches.EntityPlayerMPFake) {
            send(ctx, "§e⚠ '§f" + name + "§e' is in world but NOT registered.");
            send(ctx, "§7Run §f/pb spawn " + name + "§7 to register them.");
        } else if (stray != null) {
            send(ctx, "§e⚠ '§f" + name + "§e' is a real player, not a bot.");
        } else {
            send(ctx, "§7'§f" + name + "§7' not in server at all. Use §f/pb spawn " + name);
        }
        return null;
    }

    private static String stackName(ItemStack stack) {
        if (stack.isEmpty()) return "§7Empty";
        return stack.getName().getString() + (stack.getCount() > 1 ? " §7x" + stack.getCount() : "");
    }

    private static void send(CommandContext<ServerCommandSource> ctx, String msg) {
        ctx.getSource().sendFeedback(() -> Text.literal(msg), false);
    }
    private static void sendSuccess(CommandContext<ServerCommandSource> ctx, String msg) {
        ctx.getSource().sendFeedback(() -> Text.literal("§a✔ " + msg), false);
    }
    private static void sendError(CommandContext<ServerCommandSource> ctx, String msg) {
        ctx.getSource().sendFeedback(() -> Text.literal("§c✘ " + msg), false);
    }
}
