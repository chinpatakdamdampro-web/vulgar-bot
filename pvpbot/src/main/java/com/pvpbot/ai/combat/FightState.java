package com.pvpbot.ai.combat;

import net.minecraft.server.network.ServerPlayerEntity;

/**
 * FightState — tracks the current exchange between the bot and its target.
 *
 * Ported from VexBot's FightState, adapted for our difficulty system.
 * Pure logic — no velocity, no damage, no MC API side effects.
 *
 * Tracks:
 *  ourChain        — consecutive hits the bot has landed
 *  theirChain      — consecutive hits the target has landed on us
 *  ticksSinceOurHit  — ticks since the bot last hit the target
 *  ticksSinceHitTaken — ticks since the bot was last hit
 */
public class FightState {

    // How many ticks between hits before a combo chain resets
    private static final int COMBO_LINK_TICKS = 25;

    private int ourChain         = 0;
    private int theirChain       = 0;
    private int ourLastHitTick   = 0;
    private int theirLastHitTick = 0;
    private int tickCounter      = 0;

    public void tick() {
        tickCounter++;
        // Reset chains if too much time has passed
        if (tickCounter - ourLastHitTick > COMBO_LINK_TICKS)   ourChain   = 0;
        if (tickCounter - theirLastHitTick > COMBO_LINK_TICKS)  theirChain = 0;
    }

    /** Call when the bot successfully lands a hit. */
    public void onOurHit() {
        ourChain++;
        ourLastHitTick = tickCounter;
    }

    /** Call when the bot takes damage from the target. */
    public void onHitTaken() {
        theirChain++;
        theirLastHitTick = tickCounter;
    }

    /** How many consecutive hits the bot has landed this chain. */
    public int getOurChain() { return ourChain; }

    /** How many consecutive hits the target has landed this chain. */
    public int getTheirChain() { return theirChain; }

    /** Ticks since the bot last landed a hit. */
    public int getTicksSinceOurHit() { return tickCounter - ourLastHitTick; }

    /** Ticks since the bot last took a hit. */
    public int getTicksSinceHitTaken() { return tickCounter - theirLastHitTick; }

    /** True if the target has hit the bot recently and is in a combo. */
    public boolean isBeingCombod() {
        return theirChain >= 2 && (tickCounter - theirLastHitTick) < COMBO_LINK_TICKS;
    }

    public void reset() {
        ourChain         = 0;
        theirChain       = 0;
        ourLastHitTick   = 0;
        theirLastHitTick = 0;
    }
}
