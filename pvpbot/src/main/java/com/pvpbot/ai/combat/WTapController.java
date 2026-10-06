package com.pvpbot.ai.combat;

import com.pvpbot.config.BotConfig;

/**
 * WTapController — coordinates sprint-stop timing with weapon cooldown.
 *
 * Ported from VexBot's WTapController and adapted for our difficulty system.
 *
 * How it works:
 *   Every attack cycle the bot watches the weapon's charge progress (0.0–1.0).
 *   When charge reaches cfg.wTapStopAt, the bot stops sprinting — this bleeds
 *   forward momentum and primes knockback for the next hit.
 *   When charge reaches cfg.wTapAttackAt, the bot is cleared to swing.
 *
 *   The sprint-stop and the swing happen on the same cooldown arc, so the
 *   target gets knockback from the momentum bleed AND takes near-full damage
 *   from the charged hit. This is what makes every hit feel impactful.
 *
 * Difficulty values are read live from BotConfig so runtime difficulty changes
 * (e.g. via /pb difficulty) take effect without recreating the controller.
 *
 * Note: attacking below 1.0 charge still deals near-full damage due to:
 *   damage = base * (0.2 + charge² * 0.8)
 * At 0.88 charge this is ~82% of base — still significant, and more than
 * compensated by better knockback timing and attack cadence.
 */
public class WTapController {

    private final BotConfig cfg;
    private boolean sprintStopped = false;

    public WTapController(BotConfig cfg) {
        this.cfg = cfg;
    }

    /**
     * Returns true the first time charge crosses wTapStopAt this cycle.
     * Call every tick during the attack cooldown wait.
     */
    public boolean shouldStopSprint(float chargeProgress) {
        if (!sprintStopped && chargeProgress >= cfg.wTapStopAt) {
            sprintStopped = true;
            return true;
        }
        return false;
    }

    /**
     * Returns true when the weapon is charged enough to attack.
     */
    public boolean canAttack(float chargeProgress) {
        return chargeProgress >= cfg.wTapAttackAt;
    }

    /**
     * Call this after every successful attack to reset the cycle.
     */
    public void onAttack() {
        sprintStopped = false;
    }
}