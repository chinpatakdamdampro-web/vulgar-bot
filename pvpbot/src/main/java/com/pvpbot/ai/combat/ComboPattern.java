package com.pvpbot.ai.combat;

/**
 * ComboPattern — the different fighting styles the bot can cycle through.
 *
 *  SPRINT_HITS   – Aggressive: 3 sprint hits then a jump crit.
 *  DOUBLE_STRAFE – Mobile: 2 hits, strafe reposition, crit.
 *  JUMP_RESET    – Classic: jump crit followed by sprint combo.
 *  SHIELD_BAIT   – Tactical: raise shield, bait, drop and axe swing.
 *  BREACH_SWAP   – Technique: sword hit → instant swap to breach mace → mace hit.
 *                  Only selected if the bot has a Breach-enchanted mace.
 */
public enum ComboPattern {
    SPRINT_HITS,
    DOUBLE_STRAFE,
    JUMP_RESET,
    SHIELD_BAIT,
    BREACH_SWAP
}
