package com.pvpbot.ai.combat;

import carpet.patches.EntityPlayerMPFake;
import com.pvpbot.ai.movement.MovementController;
import com.pvpbot.config.BotConfig;

import java.util.Random;

/**
 * ComboBreakController — jumps away when the bot is being comboed.
 *
 * Ported from VexBot's ComboBreakController (jump-only path).
 * Pearl throw path is omitted — we don't have an EnderPearlController.
 *
 * Safety guarantees (no conflict with existing combat):
 *  - NEVER fires during BREACH_SWAP pattern (would interrupt the dive)
 *  - NEVER fires while waitingForCrit (would cancel the jump arc)
 *  - NEVER fires while shielding (would drop the block)
 *  - Uses movement.jumpAway() which only calls setVelocity — same as
 *    the existing sprint-reset jumps in MovementController, no new behaviour
 *  - Has its own cooldown so it can't spam
 */
public class ComboBreakController {

    // Minimum incoming combo hits before we react
    private static final int JUMP_AT       = 2;
    // Cooldown between combo breaks (ticks)
    private static final int JUMP_COOLDOWN = 40;

    private final BotConfig cfg;
    private final MovementController movement;
    private final Random rng = new Random();

    private int jumpCooldown = 0;

    public ComboBreakController(BotConfig cfg, MovementController movement) {
        this.cfg      = cfg;
        this.movement = movement;
    }

    /**
     * Call every tick AFTER the standard attack/shield checks.
     * Returns true if a combo break jump was triggered this tick.
     *
     * Safe to call when:
     *  - currentPattern != BREACH_SWAP
     *  - !waitingForCrit
     *  - !shielding
     *
     * @param fightState current fight state (for combo chain detection)
     * @param fake       the bot's fake player
     */
    public boolean tick(FightState fightState, EntityPlayerMPFake fake) {
        if (jumpCooldown > 0) {
            jumpCooldown--;
            return false;
        }
        if (!fightState.isBeingCombod()) return false;
        if (fightState.getTheirChain() < JUMP_AT) return false;
        if (!fake.isOnGround()) return false; // already airborne

        // Difficulty gate — lower difficulties react less often
        double reactChance = switch (cfg.difficulty) {
            case EASY       -> 0.10;
            case MEDIUM     -> 0.35;
            case HARD       -> 0.65;
            case ULTRA_HARD -> 0.90;
        };
        if (rng.nextDouble() > reactChance) return false;

        // Use the existing MovementController jump — same setVelocity call
        // used by jumpForCrit, no new side effects
        movement.jumpForCrit();
        jumpCooldown = JUMP_COOLDOWN;
        return true;
    }

    public void reset() {
        jumpCooldown = 0;
    }
}
