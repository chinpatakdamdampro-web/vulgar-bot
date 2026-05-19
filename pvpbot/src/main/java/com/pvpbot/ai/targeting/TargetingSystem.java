package com.pvpbot.ai.targeting;

import com.pvpbot.entity.PvPBotEntity;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * TargetingSystem — manages combat and follow targets.
 */
public class TargetingSystem {

    private final PvPBotEntity bot;

    private ServerPlayerEntity combatTarget;
    private ServerPlayerEntity followTarget;

    public TargetingSystem(PvPBotEntity bot) {
        this.bot = bot;
    }

    // -------------------------------------------------------------------------
    // Tick — prune dead/disconnected targets
    // -------------------------------------------------------------------------

    public void tick() {
        if (combatTarget != null && (!combatTarget.isAlive() || combatTarget.isRemoved())) {
            combatTarget = null;
        }
        if (followTarget != null && (!followTarget.isAlive() || followTarget.isRemoved())) {
            followTarget = null;
        }
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public ServerPlayerEntity getCombatTarget() { return combatTarget; }
    public ServerPlayerEntity getFollowTarget()  { return followTarget; }

    public void setCombatTarget(ServerPlayerEntity target) { this.combatTarget = target; }
    public void setFollowTarget(ServerPlayerEntity target) { this.followTarget = target; }

    public void clearAll() {
        combatTarget = null;
        followTarget = null;
    }

    public double distanceToTargetSq() {
        if (combatTarget == null) return Double.MAX_VALUE;
        return bot.getFakePlayer().squaredDistanceTo(combatTarget);
    }

    /**
     * Simple distance-based line-of-sight approximation.
     * Avoids calling canSee() which has version-specific signatures.
     * Returns true if the target is within attack range.
     */
    public boolean hasLineOfSight() {
        if (combatTarget == null) return false;
        // Use distance check as a safe proxy — actual ray cast isn't critical
        // for basic combat functionality
        double distSq = distanceToTargetSq();
        double reach  = bot.getConfig().attackReach;
        return distSq <= reach * reach * 4; // allow up to 2x reach
    }
}
