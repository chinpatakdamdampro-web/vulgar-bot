package com.pvpbot.config;

/**
 * BotConfig — all tunable AI parameters per bot instance.
 *
 * Difficulty system:
 *  EASY       — slow attacks, no crits, no potions, no shield logic
 *  MEDIUM     — default balanced behaviour
 *  HARD       — fast attacks, high crits, aggressive retreat threshold
 *  ULTRA_HARD — HARD stats + permanent Strength III (applied each tick as
 *               a status effect so it never expires mid-fight)
 */
public class BotConfig {

    // -------------------------------------------------------------------------
    // Combat timing
    // -------------------------------------------------------------------------

    public int attackCooldownTicks       = 11;
    public int attackTimingJitter        = 3;
    public int strafeChangeTicks         = 12;
    public int critChancePercent         = 55;
    public int sprintResetChancePercent  = 50;
    public float minCps                  = 7.0f;
    public float maxCps                  = 13.0f;

    // -------------------------------------------------------------------------
    // Movement
    // -------------------------------------------------------------------------

    public double preferredRange    = 2.5;
    public double maxChaseDistance  = 32.0; // kept for BotConfig completeness; not enforced in tickAttack
    public double attackReach       = 3.2;

    /**
     * 0.0 = strafe every opportunity, 1.0 = never strafe.
     * Default 0.35 = moderate strafing.
     */
    public double strafeFrequency = 0.35;

    // -------------------------------------------------------------------------
    // Accuracy
    // -------------------------------------------------------------------------

    /**
     * Reduces hit chance when target is airborne.
     * 0.0 = perfect, 1.0 = always misses airborne targets.
     */
    public double accuracyReduction = 0.15;

    // -------------------------------------------------------------------------
    // Healing
    // -------------------------------------------------------------------------

    public float gappleThreshold       = 14.0f;
    public float notchAppleThreshold   = 8.0f;
    public int hungerThreshold         = 8;

    // -------------------------------------------------------------------------
    // Shield / mace prediction
    // -------------------------------------------------------------------------

    public double maceShieldVelocityThreshold = -0.4;
    public double maceShieldHeightThreshold   = 3.0;
    public int shieldHoldTicks                = 30;
    public int shieldLockoutTicks             = 35;

    // -------------------------------------------------------------------------
    // Potions
    // -------------------------------------------------------------------------

    /** Ticks between potion effect checks (200 = every 10 seconds). */
    public int potionCheckIntervalTicks = 200;

    // -------------------------------------------------------------------------
    // Behaviour flags
    // -------------------------------------------------------------------------

    /** If true, bot retaliates when hit by a player. */
    public boolean revengeMode = false;

    /** Current combat mode — affects crit chance, strafe, accuracy. */
    public BotMode mode = BotMode.COMBO;

    // Safe rollout: keep legacy combat by default, allow opt-in v2.
    public enum CombatEngine { LEGACY, V2 }
    public CombatEngine combatEngine = CombatEngine.LEGACY;

    /** Current difficulty — affects stats AND applies passive effects. */
    public Difficulty difficulty = Difficulty.MEDIUM;

    // -------------------------------------------------------------------------
    // Difficulty enum
    // -------------------------------------------------------------------------

    public enum Difficulty {
        /**
         * Slow, inaccurate, no crits, no potion use.
         * Good for beginners or very casual practice.
         */
        EASY,

        /**
         * Default balanced behaviour.
         */
        MEDIUM,

        /**
         * Fast attacks, high crit rate, aggressive retreat threshold.
         * Full potion usage, shield baiting, breach swaps.
         */
        HARD,

        /**
         * Everything HARD has + permanent Strength III applied as a
         * status effect every tick (never expires). Hits like a truck.
         * Full AI: breach swap, shield bait, totem, potions, revenge.
         */
        ULTRA_HARD
    }

    // -------------------------------------------------------------------------
    // Mode enum
    // -------------------------------------------------------------------------

    public enum BotMode {
        /** More crits, less strafing. */
        CRIT,
        /** Balanced combos + crits. Default. */
        COMBO,
        /** Less strafe, shield-predict hits, more deliberate. */
        SMP
    }

    // -------------------------------------------------------------------------
    // applyDifficulty — call after setting difficulty to update all derived values
    // -------------------------------------------------------------------------

    public void applyDifficulty() {
        switch (difficulty) {
            case EASY -> {
                attackCooldownTicks      = 22;
                attackTimingJitter       = 8;
                critChancePercent        = 10;
                sprintResetChancePercent = 10;
                minCps                   = 2.0f;
                maxCps                   = 5.0f;
                accuracyReduction        = 0.40;
                gappleThreshold          = 6.0f;   // heals late
                notchAppleThreshold      = 2.0f;
                potionCheckIntervalTicks = 9999;   // effectively disabled
                strafeFrequency          = 0.75;   // almost never strafes
                preferredRange           = 2.8;
            }
            case MEDIUM -> {
                attackCooldownTicks      = 11;
                attackTimingJitter       = 3;
                critChancePercent        = 55;
                sprintResetChancePercent = 50;
                minCps                   = 7.0f;
                maxCps                   = 13.0f;
                accuracyReduction        = 0.15;
                gappleThreshold          = 14.0f;
                notchAppleThreshold      = 8.0f;
                potionCheckIntervalTicks = 200;
                strafeFrequency          = 0.35;
                preferredRange           = 2.5;
            }
            case HARD -> {
                attackCooldownTicks      = 9;
                attackTimingJitter       = 2;
                critChancePercent        = 72;
                sprintResetChancePercent = 65;
                minCps                   = 10.0f;
                maxCps                   = 15.0f;
                accuracyReduction        = 0.05;
                gappleThreshold          = 16.0f;
                notchAppleThreshold      = 10.0f;
                potionCheckIntervalTicks = 160;
                strafeFrequency          = 0.30;
                preferredRange           = 2.2;
            }
            case ULTRA_HARD -> {
                // Same timing stats as HARD
                attackCooldownTicks      = 8;
                attackTimingJitter       = 1;
                critChancePercent        = 80;
                sprintResetChancePercent = 70;
                minCps                   = 12.0f;
                maxCps                   = 18.0f;
                accuracyReduction        = 0.02;
                gappleThreshold          = 18.0f;
                notchAppleThreshold      = 12.0f;
                potionCheckIntervalTicks = 120;
                strafeFrequency          = 0.25;
                preferredRange           = 2.0;
                // Strength III is applied as a live status effect in PvPBotEntity
                // every tick — not via config values. No further config needed here.
            }
        }
    }

    // -------------------------------------------------------------------------
    // applyMode — call after setting mode to update derived values
    // -------------------------------------------------------------------------

    public void applyMode() {
        switch (mode) {
            case CRIT -> {
                critChancePercent        = Math.min(95, critChancePercent + 20);
                sprintResetChancePercent = Math.max(10, sprintResetChancePercent - 15);
                strafeFrequency          = Math.min(0.9, strafeFrequency + 0.25);
                accuracyReduction        = Math.max(0.01, accuracyReduction - 0.05);
                attackCooldownTicks      = Math.max(6, attackCooldownTicks - 1);
            }
            case COMBO -> {
                // No adjustment — combo is the baseline mode, difficulty drives values
            }
            case SMP -> {
                critChancePercent        = Math.max(10, critChancePercent - 15);
                sprintResetChancePercent = Math.max(10, sprintResetChancePercent - 20);
                strafeFrequency          = Math.min(0.9, strafeFrequency + 0.30);
                accuracyReduction        = Math.max(0.01, accuracyReduction - 0.07);
                attackCooldownTicks      = Math.min(20, attackCooldownTicks + 2);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Convenience factory methods
    // -------------------------------------------------------------------------

    public static BotConfig easy() {
        BotConfig c = new BotConfig();
        c.difficulty = Difficulty.EASY;
        c.applyDifficulty();
        return c;
    }

    public static BotConfig medium() {
        BotConfig c = new BotConfig();
        c.difficulty = Difficulty.MEDIUM;
        c.applyDifficulty();
        return c;
    }

    public static BotConfig hard() {
        BotConfig c = new BotConfig();
        c.difficulty = Difficulty.HARD;
        c.applyDifficulty();
        return c;
    }

    public static BotConfig ultraHard() {
        BotConfig c = new BotConfig();
        c.difficulty = Difficulty.ULTRA_HARD;
        c.applyDifficulty();
        return c;
    }

    /** Create a BotConfig for the given difficulty string (case-insensitive). */
    public static BotConfig forDifficulty(String name) {
        return switch (name.toLowerCase()) {
            case "easy"                   -> easy();
            case "hard"                   -> hard();
            case "ultrahard", "ultra_hard"-> ultraHard();
            default                       -> medium();
        };
    }

    /** Returns the display name for the current difficulty. */
    public String difficultyDisplayName() {
        return switch (difficulty) {
            case EASY       -> "§aEASY";
            case MEDIUM     -> "§eMEDIUM";
            case HARD       -> "§cHARD";
            case ULTRA_HARD -> "§4§lULTRA HARD";
        };
    }
}
