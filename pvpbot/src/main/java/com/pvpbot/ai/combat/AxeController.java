package com.pvpbot.ai.combat;

import com.pvpbot.config.BotConfig;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Random;

/**
 * AxeController — smarter shield-disable timing.
 *
 * Ported from VexBot's AxeController, adapted for our difficulty system.
 * Replaces the flat checkAndDisableTargetShield() logic with a reaction-delay
 * system: when the bot first sees the target raise a shield, it rolls a random
 * reaction delay (based on difficulty) before punishing. This makes the shield
 * disable feel human rather than instant.
 *
 * No velocity changes. No damage changes. No sprint changes.
 * Only affects WHEN the axe hit fires, not how hard it hits.
 */
public class AxeController {

    // How long to hold a "punish window" after rolling reaction delay
    private static final int SHIELD_PUNISH_TICKS = 12;

    private final BotConfig cfg;
    private final Random rng = new Random();

    private boolean seesShieldNow       = false;
    private int     shieldReactionCountdown = 0; // ticks until we can punish
    private int     shieldPunishWindow   = 0;    // ticks remaining to land the hit
    private boolean punishActive         = false;

    public AxeController(BotConfig cfg) {
        this.cfg = cfg;
    }

    /**
     * Call every tick when the target is within axe range.
     * Returns true if we should attempt the axe hit this tick.
     *
     * @param target the current combat target
     * @param charge current weapon cooldown progress (0.0–1.0)
     */
    public boolean shouldDisableNow(ServerPlayerEntity target, float charge) {
        boolean targetBlocking = target.isBlocking();

        if (targetBlocking) {
            if (!seesShieldNow) {
                // Just saw the shield raise — roll reaction delay
                seesShieldNow = true;
                shieldReactionCountdown = rollReactionTicks();
                punishActive  = false;
            }
            if (shieldReactionCountdown > 0) {
                shieldReactionCountdown--;
                return false; // still in reaction delay
            }
            if (!punishActive) {
                // Reaction delay elapsed — open punish window
                punishActive       = true;
                shieldPunishWindow = SHIELD_PUNISH_TICKS;
            }
            if (shieldPunishWindow > 0) {
                shieldPunishWindow--;
                // Only fire when weapon is charged enough
                return charge >= 0.9f;
            }
            // Punish window expired — reset until next shield raise
            seesShieldNow = false;
            punishActive  = false;
            return false;
        } else {
            // Shield lowered — reset
            seesShieldNow = false;
            punishActive  = false;
            return false;
        }
    }

    /** Call after the axe hit lands successfully. */
    public void onDisableSwung() {
        seesShieldNow  = false;
        punishActive   = false;
        shieldPunishWindow = 0;
    }

    private int rollReactionTicks() {
        return switch (cfg.difficulty) {
            case EASY       -> 8  + rng.nextInt(10); // 8–17 ticks (~0.4–0.85s)
            case MEDIUM     -> 4  + rng.nextInt(6);  // 4–9 ticks (~0.2–0.45s)
            case HARD       -> 2  + rng.nextInt(3);  // 2–4 ticks (~0.1–0.2s)
            case ULTRA_HARD -> rng.nextInt(2);        // 0–1 ticks (near-instant)
        };
    }

    public void reset() {
        seesShieldNow           = false;
        shieldReactionCountdown = 0;
        shieldPunishWindow      = 0;
        punishActive            = false;
    }
}
