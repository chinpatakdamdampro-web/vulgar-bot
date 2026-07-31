package com.pvpbot.entity;

import carpet.patches.EntityPlayerMPFake;
import com.pvpbot.ai.combat.CombatController;
import com.pvpbot.ai.inventory.InventoryManager;
import com.pvpbot.ai.movement.MovementController;
import com.pvpbot.ai.targeting.TargetingSystem;
import com.pvpbot.config.BotConfig;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

public class PvPBotEntity {

    private final String name;
    private final EntityPlayerMPFake fakePlayer;
    private final BotConfig config;
    private final TargetingSystem targetingSystem;
    private final MovementController movementController;
    private final CombatController combatController;
    private final InventoryManager inventoryManager;

    /** Which faction this bot belongs to, or null. */
    private String faction = null;

    private BotState state = BotState.IDLE;
    private int tickCounter = 0;

    /** Tracks last known health for revenge detection. */
    private float lastHealth = -1f;

    /**
     * The last real player that damaged this bot.
     * Used by revenge mode to target the actual attacker instead of
     * just the nearest player, which could be an unrelated bot.
     */
    private ServerPlayerEntity lastAttacker = null;

    public PvPBotEntity(EntityPlayerMPFake fakePlayer, BotConfig config) {
        this.fakePlayer = fakePlayer;
        this.name = fakePlayer.getName().getString();
        this.config = config;
        this.targetingSystem    = new TargetingSystem(this);
        this.movementController = new MovementController(this);
        this.inventoryManager   = new InventoryManager(this);
        this.combatController   = new CombatController(this, targetingSystem, movementController, inventoryManager);
    }

    // =========================================================================
    // Tick
    // =========================================================================

    public void tick(MinecraftServer server) {
        if (!isAlive()) return;
        tickCounter++;

        // Movement tick first — ensures knockback suppression always counts down
        movementController.tick();
        inventoryManager.tick();
        targetingSystem.tick();

        // Apply passive difficulty effects (e.g. Strength III for Ultra Hard)
        applyDifficultyEffects();

        // Prune lastAttacker if they disconnected or died
        if (lastAttacker != null && (!lastAttacker.isAlive() || lastAttacker.isRemoved())) {
            lastAttacker = null;
        }

        // Revenge: check every tick regardless of state
        checkRevenge();

        switch (state) {
            case IDLE    -> tickIdle();
            case FOLLOW  -> tickFollow();
            case ATTACK  -> tickAttack();
            case RETREAT -> tickRetreat();
        }
    }

    private void tickIdle() {
        // Keep idle bots still. This clears leftover jump/strafe velocity from
        // cancelled combat so stopped bots do not hop around randomly.
        movementController.stop();
    }

    private void tickFollow() {
        ServerPlayerEntity followTarget = targetingSystem.getFollowTarget();
        if (followTarget == null || !followTarget.isAlive()) {
            setState(BotState.IDLE);
            return;
        }
        // followMode=true → stops at 2.5 blocks, slows down when within 5 blocks
        movementController.moveTo(followTarget.getPos(), true);
        movementController.lookAtTarget(followTarget);
    }

    private void tickAttack() {
        ServerPlayerEntity combatTarget = targetingSystem.getCombatTarget();
        if (combatTarget == null || !combatTarget.isAlive()) {
            setState(BotState.IDLE);
            return;
        }
        // No max-chase-distance cap — bot chases the target until it dies or is stopped.
        // Removing the cap was intentional: the player should have to fight or die,
        // not simply outrun the bot. Use /pb stop to cancel.
        combatController.tick();
    }

    private void tickRetreat() {
        combatController.tickRetreat();
    }

    // =========================================================================
    // Revenge
    // =========================================================================

    /**
     * If revengeMode is enabled, detects health drops and retaliates.
     *
     * Key fixes over the original:
     *  1. Fires in ALL states (IDLE, FOLLOW, RETREAT) — not just IDLE.
     *     Previously a bot being hit during retreat would never re-engage.
     *  2. Targets lastAttacker (the player who most recently hit this bot)
     *     rather than the nearest player, which could be a different bot.
     *  3. In RETREAT: records the attacker but doesn't interrupt the retreat —
     *     bot will re-engage once healthy again via tickRetreat's state transition.
     *  4. In FOLLOW: transitions to ATTACK (follow command is superseded by combat).
     */
    private void checkRevenge() {
        float currentHealth = fakePlayer.getHealth();

        // First tick — initialise, don't trigger
        if (lastHealth < 0) {
            lastHealth = currentHealth;
            return;
        }

        // Detect health drop (threshold 0.5 to avoid float noise)
        boolean wasHit = currentHealth < lastHealth - 0.5f;
        lastHealth = currentHealth;

        if (!wasHit) return;
        if (!config.revengeMode) return;

        // Find the actual attacker — prefer the player who most recently dealt damage.
        // ServerPlayerEntity.getAttacker() / getRecentDamageSource() is the cleanest
        // approach in Fabric 1.21.1.
        ServerPlayerEntity attacker = findAttacker();
        if (attacker != null) {
            lastAttacker = attacker;
        }

        switch (state) {
            case IDLE, FOLLOW -> {
                // Immediately engage — attack the one who hit us
                ServerPlayerEntity target = lastAttacker;
                if (target == null) target = findNearestRealPlayer();
                if (target != null) startAttacking(target);
            }
            case RETREAT -> {
                // Don't interrupt retreat — but remember the attacker.
                // When retreat ends and health is restored, tickRetreat's
                // state transition will re-engage via the stored combatTarget.
                if (targetingSystem.getCombatTarget() == null && lastAttacker != null) {
                    targetingSystem.setCombatTarget(lastAttacker);
                }
            }
            case ATTACK -> {
                // Already fighting — update target to current attacker if different
                if (lastAttacker != null
                        && lastAttacker != targetingSystem.getCombatTarget()) {
                    // Only switch target if the new attacker is closer
                    double currentTargetDistSq = targetingSystem.distanceToTargetSq();
                    double attackerDistSq = fakePlayer.squaredDistanceTo(lastAttacker);
                    if (attackerDistSq < currentTargetDistSq * 0.5) {
                        startAttacking(lastAttacker);
                    }
                }
            }
        }
    }

    /**
     * Attempts to find the player who most recently damaged this bot using
     * Minecraft's damage tracking. Includes both real players and fake players
     * so bot-vs-bot revenge works.
     */
    private ServerPlayerEntity findAttacker() {
        // Use the recent attacker from Minecraft's built-in damage tracking
        var attacker = fakePlayer.getAttacker();
        if (attacker instanceof ServerPlayerEntity sp && sp != fakePlayer) {
            return sp;
        }
        // Try recent damage source
        var recentSource = fakePlayer.getRecentDamageSource();
        if (recentSource != null && recentSource.getAttacker() instanceof ServerPlayerEntity sp
                && sp != fakePlayer) {
            return sp;
        }
        return null;
    }

    /** Fallback: find the nearest player (real or fake), excluding self. */
    private ServerPlayerEntity findNearestRealPlayer() {
        ServerPlayerEntity nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (ServerPlayerEntity p : fakePlayer.getServerWorld().getPlayers()) {
            if (p == fakePlayer) continue;
            // Include fake players too so revenge can chain into bot-vs-bot fights.
            double d = fakePlayer.squaredDistanceTo(p);
            if (d < nearestDistSq) { nearestDistSq = d; nearest = p; }
        }
        return nearest;
    }

    // =========================================================================
    // Difficulty effects
    // =========================================================================

    /**
     * Applied every tick. For ULTRA_HARD: refreshes Strength III (amplifier 2)
     * with a 5-second duration so it never expires during combat.
     * All other difficulties: ensures no leftover Strength effect remains
     * if difficulty was changed mid-session.
     */
    private void applyDifficultyEffects() {
        if (config.difficulty == BotConfig.Difficulty.ULTRA_HARD) {
            var strength = fakePlayer.getStatusEffect(StatusEffects.STRENGTH);
            var resistance = fakePlayer.getStatusEffect(StatusEffects.RESISTANCE);
            // Refresh every 60 ticks (3 seconds) when duration drops below 100 ticks (5s)
            if (strength == null || strength.getDuration() < 100) {
                // amplifier 2 = Strength III (0-indexed: 0=I, 1=II, 2=III)
                fakePlayer.addStatusEffect(
                    new StatusEffectInstance(StatusEffects.STRENGTH, 200, 2, false, false, false)
                );
            }
            if (resistance == null || resistance.getDuration() < 100) {
                // amplifier 0 = Resistance I
                fakePlayer.addStatusEffect(
                    new StatusEffectInstance(StatusEffects.RESISTANCE, 200, 0, false, false, false)
                );
            }
        } else {
            // Remove any lingering Ultra Hard effects from a previous session
            if (fakePlayer.hasStatusEffect(StatusEffects.STRENGTH)) {
                fakePlayer.removeStatusEffect(StatusEffects.STRENGTH);
            }
            if (fakePlayer.hasStatusEffect(StatusEffects.RESISTANCE)) {
                fakePlayer.removeStatusEffect(StatusEffects.RESISTANCE);
            }
        }
    }

    /**
     * Applies a new difficulty to this bot at runtime.
     * Reconfigures all BotConfig values and immediately updates passive effects.
     */

    public void setCombatEngine(BotConfig.CombatEngine engine) {
        config.combatEngine = engine;
    }

    public void setDifficulty(BotConfig.Difficulty difficulty) {
        config.difficulty = difficulty;
        config.applyDifficulty();
        // Re-apply mode on top of new difficulty baseline
        config.applyMode();
        applyDifficultyEffects();
    }



    public void startAttacking(ServerPlayerEntity target) {
        targetingSystem.setCombatTarget(target);
        targetingSystem.setFollowTarget(null); // cancel any follow command
        setState(BotState.ATTACK);
    }

    public void startFollowing(ServerPlayerEntity target) {
        targetingSystem.setFollowTarget(target);
        targetingSystem.setCombatTarget(null); // cancel any attack command
        setState(BotState.FOLLOW);
    }

    public void stop() {
        targetingSystem.clearAll();
        combatController.reset();
        movementController.stop();
        lastAttacker = null;
        // Remove Ultra Hard strength effect when bot is stopped
        if (fakePlayer.hasStatusEffect(StatusEffects.STRENGTH)) {
            fakePlayer.removeStatusEffect(StatusEffects.STRENGTH);
        }
        if (fakePlayer.hasStatusEffect(StatusEffects.RESISTANCE)) {
            fakePlayer.removeStatusEffect(StatusEffects.RESISTANCE);
        }
        setState(BotState.IDLE);
    }

    public void setState(BotState newState) { this.state = newState; }

    // =========================================================================
    // Accessors
    // =========================================================================

    public String getName()                      { return name; }
    public EntityPlayerMPFake getFakePlayer()    { return fakePlayer; }
    public BotConfig getConfig()                 { return config; }
    public BotState getState()                   { return state; }
    public int getTickCounter()                  { return tickCounter; }
    public TargetingSystem getTargetingSystem()  { return targetingSystem; }
    public MovementController getMovement()      { return movementController; }
    public CombatController getCombat()          { return combatController; }
    public InventoryManager getInventory()       { return inventoryManager; }
    public String getFaction()                   { return faction; }
    public void setFaction(String faction)       { this.faction = faction; }
    public ServerPlayerEntity getLastAttacker()  { return lastAttacker; }

    public ServerWorld getWorld() {
        return (ServerWorld) fakePlayer.getWorld();
    }

    public boolean isAlive() {
        return fakePlayer != null && !fakePlayer.isRemoved() && fakePlayer.isAlive();
    }

    public enum BotState {
        IDLE, FOLLOW, ATTACK, RETREAT
    }
}
