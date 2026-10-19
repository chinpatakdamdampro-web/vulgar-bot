package com.pvpbot.ai.combat;

import com.pvpbot.config.BotConfig;

import java.util.Random;

/**
 * StrafeController — burst strafing with per-attack yaw offsets.
 *
 * Ported from VexBot's StrafeController, adapted for our difficulty system.
 * Pure logic — no velocity calls, no setVelocity, no sprint changes.
 * Provides a yaw angle offset that MovementController.combatMoveTo() applies
 * as a strafe direction. This does NOT replace combatMoveTo() — it only
 * influences the angle the bot circles around the target.
 *
 * Integration: call getYawOffset() each tick and pass to movement,
 * call onAttack() after each successful hit to trigger a burst.
 */
public class StrafeController {

    // Tick jitter on strafe direction (randomises when direction flips)
    private static final int TICK_JITTER   = 3;
    // Min/max ticks before a direction flip
    private static final int FLIP_MIN      = 8;
    private static final int FLIP_MAX      = 22;
    // Yaw offset during a burst (degrees either side)
    private static final float BURST_ANGLE = 28.0f;
    // Min/max ticks a burst lasts
    private static final int BURST_MIN     = 3;
    private static final int BURST_MAX     = 7;

    private final Random rng = new Random();
    private final BotConfig cfg;

    private int   direction   = 1;   // 1 = left, -1 = right
    private int   remaining   = 12;  // ticks until next direction flip
    private boolean inBurst   = false;
    private int   burstTicks  = 0;

    public StrafeController(BotConfig cfg) {
        this.cfg = cfg;
    }

    /**
     * Returns the yaw offset (degrees) to apply to the strafe direction this tick.
     * Pass this into movement as an additional angle on top of the base facing.
     *
     * @param tickCount   current tick (used for jitter)
     * @param baseAngle   the current facing yaw toward the target
     */
    public float getYawOffset(int tickCount, float baseAngle) {
        // Flip direction timer
        remaining--;
        if (remaining <= 0) {
            direction  = rng.nextBoolean() ? 1 : -1;
            remaining  = FLIP_MIN + rng.nextInt(FLIP_MAX - FLIP_MIN)
                       + (tickCount % TICK_JITTER);
        }

        // During a burst, apply a wider angle offset in the strafe direction
        if (inBurst && burstTicks > 0) {
            burstTicks--;
            if (burstTicks == 0) inBurst = false;
            return direction * BURST_ANGLE * (1.0f + rng.nextFloat() * 0.2f);
        }

        // Normal strafe — small jitter angle
        return direction * (6.0f + rng.nextFloat() * 8.0f) * (float) cfg.strafeFrequency;
    }

    /**
     * Call after each successful attack to trigger a burst strafe.
     * This matches vexbot's attackTickStrafe pattern — after landing a hit
     * the bot briefly strafes hard to make the next hit harder to block.
     */
    public void onAttack() {
        inBurst    = true;
        burstTicks = BURST_MIN + rng.nextInt(BURST_MAX - BURST_MIN);
        direction  = rng.nextBoolean() ? 1 : -1;
    }

    /** True if currently in an attack burst. */
    public boolean isBursting() { return inBurst; }

    public void reset() {
        inBurst   = false;
        burstTicks = 0;
        remaining  = FLIP_MIN + rng.nextInt(FLIP_MAX - FLIP_MIN);
    }
}
