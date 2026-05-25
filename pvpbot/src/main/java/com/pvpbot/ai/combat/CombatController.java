package com.pvpbot.ai.combat;

import carpet.patches.EntityPlayerMPFake;
import com.pvpbot.ai.inventory.InventoryManager;
import com.pvpbot.ai.movement.MovementController;
import com.pvpbot.ai.targeting.TargetingSystem;
import com.pvpbot.config.BotConfig;
import com.pvpbot.entity.PvPBotEntity;
import net.minecraft.block.Blocks;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

/**
 * CombatController â€” full PvP AI brain.
 *
 * Changes in this version:
 *  - Knockback detection removed from here; MovementController owns it now.
 *    CombatController still pauses attacks while suppression is active so the
 *    bot doesn't frantically swing while being knocked around.
 *  - minRangeSq attack gate raised to 1.0 (1.0 block) to match the new
 *    MIN_COMBAT_RANGE in MovementController â€” bot won't freeze-attack while
 *    inside the too-close zone it's already backing out of.
 *  - Mace block: shield goes into MAIN HAND (not offhand) so the bot holds it
 *    facing the falling player, then counter-attacks after impact.
 *  - Totem handling: InventoryManager now manages totem-in-offhand priority;
 *    CombatController skips shield-raise when totem is active.
 *  - Post-totem recovery: after totem pops, bot re-buffs (potions) and heals.
 *  - BREACH_SWAP pattern added.
 *  - Shield-disable bug fixed: setCurrentHand called after axe equip.
 */
public class CombatController {

    private final PvPBotEntity bot;
    private final TargetingSystem targeting;
    private final MovementController movement;
    private final InventoryManager inventory;
    private final BotConfig cfg;
    private final Random rng = new Random();

    // Combo state
    private ComboPattern currentPattern;
    private int comboStep = 0;

    // Attack timing
    private int attackCooldown = 0;

    // Sprint reset (w-tap)
    private int sprintResetTimer = 0;

    // Shield state (mainhand block for mace, offhand for SMP predict)
    private boolean shielding       = false;
    private boolean shieldInMainhand = false; // true = mace block (slot 8), false = SMP offhand
    private int shieldTimer          = 0;
    private int shieldLockout        = 0;

    // Jump crit
    private boolean waitingForCrit = false;

    // Axe swap for shield-disable
    private int axeSwapCooldown = 0;

    // Orbital strike (optional OrbitalStrike+ mod integration)
    private int orbitalStrikeCooldown = 0;
    private int targetInWebTicks      = 0;
    private static final int ORBITAL_COOLDOWN       = 100; // 5 seconds between strikes
    private static final int WEB_CONFIRM_TICKS      = 10;  // must be stuck this long before firing
    private static final int WEB_CONFIRM_TICKS_BUBBLE = 4; // cobweb bubble = faster confirm (can't escape)

    // Cobweb trap + orbital retreat state
    private boolean cobwebTrapActive     = false;
    private Vec3d   orbitalRetreatTarget = null;
    private int     orbitalRetreatTimer  = 0;
    private static final int RETREAT_BLOCKS        = 11;
    private static final int ORBITAL_RETREAT_TICKS = 80;
    private int cobwebCooldown = 0;

    // Retreat
    private int retreatTimer = 0;
    private static final int RETREAT_TICKS = 60;



    // Potion tracking
    private int potionCheckTimer = 0;

    // SMP mode: shield prediction
    private int smpShieldTimer = 0;

    // Post-totem recovery â€” after totem pops, force a re-buff cycle
    private boolean totemJustPopped = false;

    public CombatController(PvPBotEntity bot,
                            TargetingSystem targeting,
                            MovementController movement,
                            InventoryManager inventory) {
        this.bot       = bot;
        this.targeting = targeting;
        this.movement  = movement;
        this.inventory = inventory;
        this.cfg       = bot.getConfig();
        pickNewPattern();
    }

    // =========================================================================
    // Main combat tick
    // =========================================================================

    public void tick() {
        ServerPlayerEntity target = targeting.getCombatTarget();
        if (target == null || !target.isAlive()) return;

        EntityPlayerMPFake fp = bot.getFakePlayer();

        // LOW HEALTH â€” retreat (but not if we just popped a totem and are recovering)
        if (fp.getHealth() <= cfg.gappleThreshold
                && bot.getState() != PvPBotEntity.BotState.RETREAT
                && !totemJustPopped) {
            enterRetreat();
            return;
        }

        // POST-TOTEM RECOVERY â€” force potions and gaps immediately
        if (totemJustPopped) {
            inventory.doPostTotemRecovery();
            totemJustPopped = false;
            // Reset potion check so it fires immediately next cycle
            potionCheckTimer = cfg.potionCheckIntervalTicks;
        }

        // While knockback is being ridden out, pause attacks but keep looking
        if (movement.getKnockbackSuppressTicks() > 0) {
            movement.lookAtTarget(target);
            return;
        }

        // Cobweb cooldown countdown
        if (cobwebCooldown > 0) cobwebCooldown--;

        // Wind burst airborne breach swap â€” opportunistic crit when launched up
        tickWindBurstBreachSwap(target);

        // Cobweb trap + orbital retreat â€” movement is handled inside tickCobwebTrap
        // while active, so skip normal combat movement during retreat
        tickCobwebTrap(target);
        if (cobwebTrapActive) return;

        // Movement (only when not retreating for orbital)
        movement.combatMoveTo(target);

        // Totem check â€” runs every tick (cheap), ensures totem is in offhand
        boolean hasTotem = inventory.ensureTotemInOffhand();

        // Detect totem pop: health reset to 1 is the signal
        checkTotemPop(fp);

        // Potion check
        tickPotions();

        // Orbital strike â€” fires stab shot if target is stuck in cobweb (optional mod)
        tickOrbitalStrike(target);

        // Cobweb trap â€” place webs at target's feet then retreat for orbital shot
        tickCobwebTrap(target);

        // Mace/falling-player shield â€” mainhand block
        if (!hasTotem) {
            // Only raise mainhand shield if no totem protecting us
            updateMaceBlock(target);
        }
        if (shieldLockout > 0) { shieldLockout--; return; }

        // SMP shield predict (offhand-style, low priority)
        if (cfg.mode == BotConfig.BotMode.SMP && !hasTotem) {
            tickSmpShieldPredict(target);
            if (smpShieldTimer > 0) { smpShieldTimer--; return; }
        }

        // Axe shield-disable
        if (axeSwapCooldown > 0) {
            axeSwapCooldown--;
        } else {
            checkAndDisableTargetShield(target);
        }

        // Sprint reset cooldown
        if (sprintResetTimer > 0) {
            sprintResetTimer--;
            if (sprintResetTimer == 0) movement.resumeSprint();
            return;
        }

        // Attack cooldown
        if (attackCooldown > 0) { attackCooldown--; return; }

        // Range check â€” must be outside backaway zone AND within reach.
        // MIN_COMBAT_RANGE in MovementController is 1.4, so 1.4Â² = 1.96.
        // Using 1.96 here ensures the bot never tries to attack while
        // simultaneously backing away â€” which caused the inconsistent freeze.
        // Use horizontal distance for melee gating. Full 3D distance can be inflated by
        // eye-height differences between fake players, which caused bot-vs-bot duels
        // to devolve into body-pushing without swings.
        double dx = target.getX() - fp.getX();
        double dz = target.getZ() - fp.getZ();
        double distSq = dx * dx + dz * dz;

        // Keep the existing spacing rule for normal targets, but allow a tighter
        // minimum when fighting another fake player so collision doesn't create a
        // dead-zone where both bots only keep moving.
        double minRangeSq = (target instanceof EntityPlayerMPFake) ? 0.64 : 1.96;
        double maxRangeSq = cfg.attackReach * cfg.attackReach;
        if (distSq < minRangeSq || distSq > maxRangeSq) return;

        // Weapon cooldown check
        if (fp.getAttackCooldownProgress(0) < 0.9f) return;

        // Accuracy check
        if (!target.isOnGround() && rng.nextDouble() < cfg.accuracyReduction) return;

        // Waiting for crit arc (descending)
        if (waitingForCrit) {
            if (fp.getVelocity().y < -0.08) {
                waitingForCrit = false;
                swingAt(target);
            }
            return;
        }

        // Execute combo pattern
        executeComboStep(target);
    }

    // =========================================================================
    // Retreat tick
    // =========================================================================

    public void tickRetreat() {
        ServerPlayerEntity target = targeting.getCombatTarget();
        EntityPlayerMPFake fp = bot.getFakePlayer();

        retreatTimer--;
        if (target != null) movement.retreatFrom(target);
        inventory.checkAndHeal();

        boolean healthOk  = fp.getHealth() >= fp.getMaxHealth() - 2.0f;
        boolean timerDone = retreatTimer <= 0;

        if (timerDone) {
            movement.stop();
            if (target != null && target.isAlive()) {
                // Re-engage regardless of hunger â€” hunger shouldn't lock a bot
                // out of combat indefinitely. If health is still low, extend retreat.
                if (healthOk) {
                    bot.setState(PvPBotEntity.BotState.ATTACK);
                } else {
                    retreatTimer = 20; // extend by 1 second and keep healing
                }
            } else {
                bot.setState(PvPBotEntity.BotState.IDLE);
            }
        }
    }

    private void enterRetreat() {
        retreatTimer = RETREAT_TICKS;
        lowerBlock();
        resetPartial();
        bot.setState(PvPBotEntity.BotState.RETREAT);
    }

    // =========================================================================
    // Totem pop detection
    // =========================================================================

    private float lastHealthForTotem = -1f;

    /**
     * Detect when a Totem of Undying was consumed.
     *
     * A totem fires when damage would kill the player â€” the server applies the
     * damage, finds health would go to <=0, activates the totem, and sets health
     * to exactly 1.0f. On the SAME tick we see the health snap from whatever
     * the previous-tick value was (could still be >1) up to exactly 1.0.
     *
     * However, a player can also simply have 1.0f health from normal damage.
     * The reliable signal is: previous health was LESS than 1.0f (i.e., the
     * damage tick pushed it below 1) AND current health is exactly 1.0f
     * (totem rescued it). Alternatively: health INCREASED to exactly 1.0
     * without the bot eating (eating gives back more than 1.0 HP).
     *
     * We also guard against firing when bot just spawned (lastHealth == -1).
     */
    private void checkTotemPop(EntityPlayerMPFake fp) {
        float current = fp.getHealth();
        if (lastHealthForTotem > 0
                && lastHealthForTotem < 1.0f   // was below 1 HP last tick
                && current == 1.0f) {           // snapped back to exactly 1
            totemJustPopped = true;
        }
        lastHealthForTotem = current;
    }

    // =========================================================================
    // Cobweb trap + orbital retreat
    //
    // When the bot has cobwebs and the target is within melee range:
    //  1. Place 2 cobwebs at target's feet (feet + feet+1 = cobweb bubble)
    //  2. Sprint 10-12 blocks directly away from the target
    //  3. Once at distance, face the target and fire orbital strike
    //
    // Only triggers if OrbitalStrike+ is loaded AND bot has 2+ cobwebs.
    // Uses a 20-second cooldown so it doesn't spam.
    // =========================================================================

    // =========================================================================
    // Cobweb trap
    //
    // Trigger: target within 4 blocks, bot has 2+ cobwebs, cooldown expired.
    //
    // ULTRA_HARD only:
    //   - Bot webs the player, retreats 11 blocks, fires orbital strike.
    //   - Cooldown: 600 ticks (30 seconds). Orbital fires max once per engage.
    //   - 40% random chance per trigger so multiple bots don't all orbital at once.
    //
    // Other difficulties (EASY / MEDIUM / HARD):
    //   - Bot webs the player then immediately does a breach swap crit on them.
    //   - The webbed player can't dodge â€” guaranteed crit landing.
    //   - Cooldown: 400 ticks (20 seconds).
    // =========================================================================

    private static final int COBWEB_COOLDOWN_ORBITAL     = 600; // 30s â€” ultra hard
    private static final int COBWEB_COOLDOWN_BREACH       = 400; // 20s â€” other difficulties
    private static final int ORBITAL_TRIGGER_CHANCE_PCT   = 40;  // % chance per check

    private void tickCobwebTrap(ServerPlayerEntity target) {
        if (cobwebCooldown > 0) return;
        if (!inventory.hasCobwebs(2)) return;

        if (cobwebTrapActive) {
            tickCobwebRetreat(target);
            return;
        }

        // Only trigger when target is within 4 blocks
        double distSq = targeting.distanceToTargetSq();
        if (distSq > 16.0) return;

        // Place cobweb bubble at target's feet and one block up
        var targetFeet = target.getBlockPos();
        boolean placedFeet = inventory.placeCobwebAt(targetFeet);
        boolean placedHead = inventory.placeCobwebAt(targetFeet.up());
        if (!placedFeet && !placedHead) return;

        boolean isUltraHard = cfg.difficulty == BotConfig.Difficulty.ULTRA_HARD;

        if (isUltraHard && isOrbitalModLoaded()
                && rng.nextInt(100) < ORBITAL_TRIGGER_CHANCE_PCT) {
            // ULTRA HARD: retreat and fire orbital
            EntityPlayerMPFake fp = bot.getFakePlayer();
            double dx = fp.getX() - target.getX();
            double dz = fp.getZ() - target.getZ();
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len < 0.001) { dx = 1; dz = 0; } else { dx /= len; dz /= len; }

            orbitalRetreatTarget = new Vec3d(
                    fp.getX() + dx * RETREAT_BLOCKS,
                    fp.getY(),
                    fp.getZ() + dz * RETREAT_BLOCKS
            );
            orbitalRetreatTimer = ORBITAL_RETREAT_TICKS;
            cobwebTrapActive    = true;
            cobwebCooldown      = COBWEB_COOLDOWN_ORBITAL;
        } else {
            // ALL OTHER DIFFICULTIES: immediate breach swap crit on webbed player
            // Player is stuck â€” guaranteed crit opportunity
            if (inventory.hasBreachMace()) {
                currentPattern = ComboPattern.BREACH_SWAP;
                comboStep      = 0; // full sequence: sword hit then mace crit
            }
            cobwebCooldown = COBWEB_COOLDOWN_BREACH;
        }
    }

    private void tickCobwebRetreat(ServerPlayerEntity target) {
        orbitalRetreatTimer--;
        movement.sprintTo(orbitalRetreatTarget);

        Vec3d botPos = bot.getFakePlayer().getPos();
        double dxR = botPos.x - orbitalRetreatTarget.x;
        double dzR = botPos.z - orbitalRetreatTarget.z;
        double distToRetreat = Math.sqrt(dxR * dxR + dzR * dzR);
        double distToTarget  = Math.sqrt(targeting.distanceToTargetSq());

        boolean reachedSpot  = distToRetreat < 1.5;
        boolean farEnough    = distToTarget >= RETREAT_BLOCKS - 2;
        boolean timerExpired = orbitalRetreatTimer <= 0;

        if (reachedSpot || farEnough || timerExpired) {
            movement.lookAtTarget(target);
            cobwebTrapActive     = false;
            orbitalRetreatTarget = null;

            if (isOrbitalModLoaded()) {
                var world = (ServerWorld) bot.getFakePlayer().getWorld();
                fireOrbitalStabReflection(target, world);
            }
        }
    }


    //
    // REWORK: The previous /ob stab command approach failed because fake players
    // don't route commands correctly as a source. We now call
    // OrbitalStrikeLogic.summonStab() directly via reflection â€” this is the
    // exact same method the fishing-rod item triggers internally when you cast it
    // at a block. Same effect, no command routing, no fake-player permission
    // issues, works reliably.
    //
    // If OrbitalStrike+ is not installed: isOrbitalModLoaded() returns false,
    // the whole block is skipped, and bot plays normally. Zero coupling.
    //
    // Trigger conditions:
    //  1. OrbitalStrike+ is loaded
    //  2. Target has cobweb at feet OR feet+1 (cobweb bubble)
    //  3. Target confirmed stuck for WEB_CONFIRM_TICKS consecutive ticks
    //  4. Orbital cooldown has expired (100 ticks = 5 seconds)
    // =========================================================================

    private void tickOrbitalStrike(ServerPlayerEntity target) {
        if (orbitalStrikeCooldown > 0) orbitalStrikeCooldown--;
        if (cfg.difficulty != BotConfig.Difficulty.ULTRA_HARD) {
            targetInWebTicks = 0;
            return;
        }
        if (!isOrbitalModLoaded()) return;

        BlockPos feetPos  = target.getBlockPos();
        ServerWorld world = (ServerWorld) bot.getFakePlayer().getWorld();

        boolean feetInWeb = world.getBlockState(feetPos).isOf(Blocks.COBWEB);
        boolean headInWeb = world.getBlockState(feetPos.up()).isOf(Blocks.COBWEB);
        boolean inWeb     = feetInWeb || headInWeb;
        boolean isBubble  = feetInWeb && headInWeb; // cobweb bubble â€” truly trapped

        if (inWeb) {
            targetInWebTicks++;
        } else {
            targetInWebTicks = 0;
            return;
        }

        int required = isBubble ? WEB_CONFIRM_TICKS_BUBBLE : WEB_CONFIRM_TICKS;
        if (targetInWebTicks < required) return;
        if (orbitalStrikeCooldown > 0) return;

        fireOrbitalStabReflection(target, world);
    }

    /**
     * Fires stab via StabShot mod (our own mod â€” com.pvpbot.stabshot.logic.StabLogic).
     * summonStab IS static â€” invoke(null, ...) is correct.
     * Using reflection keeps PvPBot compilable without StabShot as hard dep.
     */
    private void fireOrbitalStabReflection(ServerPlayerEntity target, ServerWorld world) {
        try {
            Class<?> logicClass = Class.forName("com.pvpbot.stabshot.logic.StabLogic");
            java.lang.reflect.Method summonStab = logicClass.getDeclaredMethod(
                    "summonStab", ServerWorld.class, int.class, int.class, int.class);
            summonStab.invoke(null,
                    world,
                    target.getBlockX(),
                    target.getBlockY(),
                    target.getBlockZ()
            );
            orbitalStrikeCooldown = ORBITAL_COOLDOWN;
            targetInWebTicks      = 0;
        } catch (Exception ignored) {
            // StabShot not installed or reflection failed â€” silently skip
        }
    }

    private static boolean isOrbitalModLoaded() {
        return net.fabricmc.loader.api.FabricLoader
                .getInstance().isModLoaded("stabshot");
    }


    private void tickPotions() {
        potionCheckTimer++;
        if (potionCheckTimer < cfg.potionCheckIntervalTicks) return;
        potionCheckTimer = 0;
        inventory.throwPotionIfNeeded();
    }

    // =========================================================================
    // SMP shield prediction (offhand)
    // =========================================================================

    private void tickSmpShieldPredict(ServerPlayerEntity target) {
        float targetCooldown = target.getAttackCooldownProgress(0);
        if (targetCooldown > 0.95f && !shielding && rng.nextInt(100) < 30) {
            raiseSmpShield();
            smpShieldTimer = 6 + rng.nextInt(4);
        }
    }

    // =========================================================================
    // Axe shield-disable
    // =========================================================================

    private void checkAndDisableTargetShield(ServerPlayerEntity target) {
        if (!target.isBlocking()) return;
        double distSq = targeting.distanceToTargetSq();
        if (distSq > cfg.attackReach * cfg.attackReach) return;
        if (bot.getFakePlayer().getAttackCooldownProgress(0) < 0.9f) return;

        boolean hasAxe = inventory.equipBestAxe();
        if (!hasAxe) return;

        bot.getFakePlayer().setCurrentHand(Hand.MAIN_HAND);
        bot.getFakePlayer().attack(target);
        setNextAttackCooldown();

        axeSwapCooldown = 20;
        inventory.scheduleSwapBackToSword();
    }

    // =========================================================================
    // Mace / mainhand shield block
    //
    // FIXED:
    //  1. Timer-based drop removed. Shield now stays raised for as long as the
    //     mace threat is detected. Drops after an 8-tick grace period when the
    //     threat clears â€” no more dropping at 2 blocks as timer runs out.
    //  2. Velocity threshold lowered from -0.4 to -0.08 so the shield raises
    //     on the very first tick of descent, not after the player builds speed.
    //  3. Height threshold lowered from 3.0 to 1.5 blocks so close-overhead
    //     attacks also trigger the block.
    //  4. shieldLockout no longer interferes with updateMaceBlock â€” the lockout
    //     only delays combat attacks, while shield logic runs independently.
    // =========================================================================

    /** Ticks to keep shield after threat disappears (grace period). */
    private int maceThreatGraceTicks = 0;
    private static final int MACE_THREAT_GRACE = 8;

    private void updateMaceBlock(ServerPlayerEntity target) {
        double heightDiff = target.getY() - bot.getFakePlayer().getY();
        double targetVelY = target.getVelocity().y;
        boolean hasMace   = target.getMainHandStack().isOf(Items.MACE);

        // Threat active when:
        //  - Target has mace, is at least 1.5 blocks above, and has started falling
        //  - OR target is falling fast (any weapon) from 1.5+ blocks above
        boolean threatActive =
                (hasMace   && heightDiff > 1.5 && targetVelY < -0.08)
                || (heightDiff > 1.5 && targetVelY < -0.3);

        if (threatActive) {
            maceThreatGraceTicks = MACE_THREAT_GRACE;
            if (!shielding) {
                raiseMaceBlock(target);
            } else {
                // Refresh the hand use so the game doesn't drop the block
                bot.getFakePlayer().setCurrentHand(Hand.MAIN_HAND);
            }
        } else if (shielding && shieldInMainhand) {
            // Threat gone â€” grace period before lowering
            if (maceThreatGraceTicks > 0) {
                maceThreatGraceTicks--;
                bot.getFakePlayer().setCurrentHand(Hand.MAIN_HAND);
            } else {
                lowerBlock();
            }
        }
    }

    private void raiseMaceBlock(ServerPlayerEntity incomingAttacker) {
        if (shielding) return;
        inventory.ensureShieldInMainhand();
        movement.lookAtTarget(incomingAttacker);
        bot.getFakePlayer().setCurrentHand(Hand.MAIN_HAND);
        shielding        = true;
        shieldInMainhand = true;
        // No countdown timer â€” threat detection owns the duration.
        // shieldLockout still delays attack combos, but NOT shield logic.
        shieldLockout    = cfg.shieldLockoutTicks;
    }

    private void raiseSmpShield() {
        if (shielding) return;
        inventory.ensureShieldInOffhand();
        bot.getFakePlayer().setCurrentHand(Hand.OFF_HAND);
        shielding        = true;
        shieldInMainhand = false;
        shieldTimer      = cfg.shieldHoldTicks;
        shieldLockout    = cfg.shieldLockoutTicks;
    }

    private void lowerBlock() {
        bot.getFakePlayer().clearActiveItem();
        shielding = false;
        shieldTimer = 0;
        // If the shield was in the main hand (slot 8), swap the weapon back
        // so the bot doesn't stay stuck holding a shield instead of a sword
        if (shieldInMainhand) {
            inventory.scheduleSwapBackToSword();
            shieldInMainhand = false;
        }
    }

    // =========================================================================
    // Combo patterns
    // =========================================================================

    private void executeComboStep(ServerPlayerEntity target) {
        if (cfg.mode == BotConfig.BotMode.CRIT) {
            doCritModeStep(target);
            return;
        }
        switch (currentPattern) {
            case SPRINT_HITS   -> doSprintHits(target);
            case DOUBLE_STRAFE -> doDoubleStrafe(target);
            case JUMP_RESET    -> doJumpReset(target);
            case SHIELD_BAIT   -> doShieldBait(target);
            case BREACH_SWAP   -> doBreachSwap(target);
        }
    }

    private void doCritModeStep(ServerPlayerEntity target) {
        switch (comboStep) {
            case 0 -> { queueJumpCrit(); comboStep++; }
            case 1 -> { if (!waitingForCrit) comboStep++; }
            case 2, 3 -> {
                movement.resumeSprint();
                swingAt(target);
                comboStep++;
                if (comboStep > 3) finishCombo();
            }
        }
    }

    private void doSprintHits(ServerPlayerEntity target) {
        switch (comboStep) {
            case 0, 1, 2 -> { movement.resumeSprint(); swingAt(target); comboStep++; }
            case 3       -> { queueJumpCrit(); finishCombo(); }
        }
    }

    private void doDoubleStrafe(ServerPlayerEntity target) {
        switch (comboStep) {
            case 0, 1 -> { swingAt(target); comboStep++; }
            case 2 -> {
                if (cfg.mode != BotConfig.BotMode.SMP || rng.nextDouble() > cfg.strafeFrequency) {
                    movement.sprintReset();
                    sprintResetTimer = 5 + rng.nextInt(4);
                }
                comboStep++;
                setNextAttackCooldown();
            }
            case 3 -> { queueJumpCrit(); finishCombo(); }
        }
    }

    private void doJumpReset(ServerPlayerEntity target) {
        switch (comboStep) {
            case 0 -> { queueJumpCrit(); comboStep++; }
            case 1 -> { if (!waitingForCrit) comboStep++; }
            case 2, 3 -> {
                movement.resumeSprint();
                swingAt(target);
                comboStep++;
                if (comboStep > 3) finishCombo();
            }
        }
    }

    private void doShieldBait(ServerPlayerEntity target) {
        switch (comboStep) {
            case 0 -> {
                raiseSmpShield();
                comboStep++;
                attackCooldown = 12 + rng.nextInt(8);
            }
            case 1 -> {
                lowerBlock();
                inventory.equipBestAxe();
                bot.getFakePlayer().setCurrentHand(Hand.MAIN_HAND);
                swingAt(target);
                finishCombo();
            }
        }
    }

    // Timeout for breach swap step 1 â€” force swing after this many ticks if neither falling nor ground resolves
    private int breachSwapStep1Timeout = 0;
    private static final int BREACH_SWAP_TIMEOUT = 10;

    /**
     * BREACH_SWAP:
     *   Step 0 â€” sword hit, short cooldown override.
     *   Step 1 â€” equip mace. If falling â†’ crit now. If on ground â†’ jump and wait.
     *            Timeout after BREACH_SWAP_TIMEOUT ticks to prevent holding mace forever.
     *   Step 2 â€” wait for descent arc, then swing.
     */
    private void doBreachSwap(ServerPlayerEntity target) {
        switch (comboStep) {
            case 0 -> {
                inventory.equipBestWeapon(false);
                bot.getFakePlayer().setCurrentHand(Hand.MAIN_HAND);
                bot.getFakePlayer().attack(target);
                attackCooldown = 2 + rng.nextInt(2);
                breachSwapStep1Timeout = 0;
                comboStep = 1;
            }
            case 1 -> {
                if (!inventory.equipBreachMace()) { finishCombo(); return; }
                bot.getFakePlayer().setCurrentHand(Hand.MAIN_HAND);

                breachSwapStep1Timeout++;

                boolean isFalling  = bot.getFakePlayer().getVelocity().y < -0.05;
                boolean isOnGround = bot.getFakePlayer().isOnGround();
                boolean timedOut   = breachSwapStep1Timeout >= BREACH_SWAP_TIMEOUT;

                if (isFalling || timedOut) {
                    // Falling naturally = crit. Timed out = force swing anyway.
                    bot.getFakePlayer().attack(target);
                    setNextAttackCooldown();
                    inventory.scheduleSwapBackToSword();
                    breachSwapStep1Timeout = 0;
                    finishCombo();
                } else if (isOnGround) {
                    movement.jumpForCrit();
                    waitingForCrit = true;
                    breachSwapStep1Timeout = 0;
                    comboStep = 2;
                }
                // still rising from wind burst â€” wait, timeout will catch it
            }
            case 2 -> {
                // Waiting for descent
                if (bot.getFakePlayer().getVelocity().y < -0.05) {
                    waitingForCrit = false;
                    bot.getFakePlayer().setCurrentHand(Hand.MAIN_HAND);
                    bot.getFakePlayer().attack(target);
                    setNextAttackCooldown();
                    inventory.scheduleSwapBackToSword();
                    finishCombo();
                }
            }
        }
    }

    /**
     * Detects wind burst airborne state and opportunistically starts a
     * breach swap. Called at the top of tick() before normal combo logic.
     * When a wind charge launches the bot upward, the bot waits for the
     * descent arc and executes swordâ†’mace breach swap as a crit.
     */
    private void tickWindBurstBreachSwap(ServerPlayerEntity target) {
        // Only trigger if:
        //  - Bot has a breach mace
        //  - Currently airborne with significant upward velocity (wind burst)
        //  - Not already mid-combo or mid-crit
        //  - Not shielding, not on cooldown
        if (!inventory.hasBreachMace()) return;
        if (!movement.isWindBurstAirborne()) return;
        if (currentPattern == ComboPattern.BREACH_SWAP && comboStep > 0) return;
        if (attackCooldown > 0) return;
        if (shielding) return;

        // Switch to breach swap pattern immediately
        currentPattern = ComboPattern.BREACH_SWAP;
        comboStep = 1; // skip step 0 (sword hit) â€” go straight to mace for the crit
        // We're rising â€” step 1 will wait until we start falling, then swing
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void swingAt(ServerPlayerEntity target) {
        if (shielding) return;
        bot.getFakePlayer().attack(target);
        setNextAttackCooldown();
    }

    private void queueJumpCrit() {
        if (rng.nextInt(100) >= cfg.critChancePercent) return;
        movement.jumpForCrit();
        waitingForCrit = true;
    }

    private void setNextAttackCooldown() {
        int jitter = cfg.attackTimingJitter > 0
                ? rng.nextInt(cfg.attackTimingJitter * 2) - cfg.attackTimingJitter : 0;
        attackCooldown = Math.max(1, cfg.attackCooldownTicks + jitter);
    }

    private void finishCombo() {
        comboStep      = 0;
        waitingForCrit = false;
        if (rng.nextInt(100) < cfg.sprintResetChancePercent) {
            movement.sprintReset();
            sprintResetTimer = 3 + rng.nextInt(5);
        }
        pickNewPattern();
    }

    private void pickNewPattern() {
        comboStep = 0;
        boolean hasBreachMace = inventory.hasBreachMace();

        if (cfg.mode == BotConfig.BotMode.SMP) {
            if (hasBreachMace) {
                ComboPattern[] p = { ComboPattern.SPRINT_HITS, ComboPattern.JUMP_RESET,
                                     ComboPattern.SHIELD_BAIT, ComboPattern.BREACH_SWAP };
                currentPattern = p[rng.nextInt(p.length)];
            } else {
                ComboPattern[] p = { ComboPattern.SPRINT_HITS, ComboPattern.JUMP_RESET,
                                     ComboPattern.SHIELD_BAIT };
                currentPattern = p[rng.nextInt(p.length)];
            }
        } else {
            if (hasBreachMace) {
                // Weighted: breach swap gets 2 slots so it fires ~33% of the time
                // instead of 1-in-5 (20%) â€” makes the technique more visible
                ComboPattern[] p = { ComboPattern.SPRINT_HITS, ComboPattern.DOUBLE_STRAFE,
                                     ComboPattern.JUMP_RESET,  ComboPattern.SHIELD_BAIT,
                                     ComboPattern.BREACH_SWAP, ComboPattern.BREACH_SWAP };
                currentPattern = p[rng.nextInt(p.length)];
            } else {
                ComboPattern[] p = { ComboPattern.SPRINT_HITS, ComboPattern.DOUBLE_STRAFE,
                                     ComboPattern.JUMP_RESET,  ComboPattern.SHIELD_BAIT };
                currentPattern = p[rng.nextInt(p.length)];
            }
        }
    }

    private void resetPartial() {
        comboStep              = 0;
        attackCooldown         = 0;
        sprintResetTimer       = 0;
        waitingForCrit         = false;
        axeSwapCooldown        = 0;
        smpShieldTimer         = 0;
        orbitalStrikeCooldown  = 0;
        targetInWebTicks       = 0;
        cobwebTrapActive       = false;
        orbitalRetreatTarget   = null;
        orbitalRetreatTimer    = 0;
        breachSwapStep1Timeout = 0;
        pickNewPattern();
    }

    public void reset() {
        lowerBlock();
        shieldLockout          = 0;
        shieldInMainhand       = false;
        maceThreatGraceTicks   = 0;
        retreatTimer           = 0;
        potionCheckTimer       = 0;
        totemJustPopped        = false;
        lastHealthForTotem     = -1f;
        cobwebTrapActive       = false;
        orbitalRetreatTarget   = null;
        orbitalRetreatTimer    = 0;
        // cobwebCooldown intentionally NOT reset â€” persists through stop/restart
        resetPartial();
    }

    public boolean isShielding()          { return shielding; }
    public String getCurrentPatternName() { return currentPattern != null ? currentPattern.name() : "NONE"; }
}
