package com.pvpbot.ai.combat;

import com.pvpbot.config.BotConfig;
import com.pvpbot.ai.movement.MovementController;

/**
 * WTapController — coordinates sprint-stop timing with weapon cooldown.
 *
 * Ported from VexBot's WTapController, adapted for our difficulty system.
 *
 * The key fix from the previous version:
 *   shouldStopSprint() now calls movement.sprintStop() (sprint flag off, no
 *   velocity change) instead of movement.sprintReset() (sprint off + velocity
 *   bleed). Calling setVelocity() during the cooldown wait window resets the
 *   server-side attack cooldown tracker to near-zero, causing every hit to
 *   land at minimal charge = ~1.4 damage. The velocity bleed now happens in
 *   onAttack(), at the exact tick the hit lands, which is correct.
 */
public class WTapController {

    private final BotConfig cfg;
    private final MovementController movement;
    private boolean sprintStopped = false;

    public WTapController(BotConfig cfg, MovementController movement) {
        this.cfg      = cfg;
        this.movement = movement;
    }

    /**
     * Returns true the first time charge crosses wTapStopAt this cycle.
     * Stops sprint (flag only, no velocity change) so the upcoming hit
     * registers as a w-tap knockback when it lands.
     */
    public boolean shouldStopSprint(float chargeProgress) {
        if (!sprintStopped && chargeProgress >= cfg.wTapStopAt) {
            sprintStopped = true;
            movement.sprintStop();
            return true;
        }
        return false;
    }

    /**
     * Returns true when the weapon is charged enough to attack.
     * Skip this check for special attacks (breach swap, webbed pressure)
     * that have their own timing logic.
     */
    public boolean canAttack(float chargeProgress) {
        return chargeProgress >= cfg.wTapAttackAt;
    }

    /**
     * Call immediately after the attack lands.
     * Bleeds velocity NOW (at hit time) for the knockback feel, then resets
     * the cycle so the next cooldown arc gets a fresh sprint-stop.
     */
    public void onAttack() {
        movement.sprintReset();
        sprintStopped = false;
    }
}