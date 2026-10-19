package com.pvpbot.ai.combat;

import carpet.patches.EntityPlayerMPFake;
import com.pvpbot.config.BotConfig;
import net.minecraft.block.Blocks;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.math.Direction;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/**
 * CobwebEscapeController — bot mines cobwebs with its sword when trapped.
 *
 * Ported from VexBot's CobwebEscapeController (sword path only).
 * Water bucket, shears, and pearl escape paths are omitted.
 *
 * Safety guarantees (no conflict with existing combat):
 *  - Returns true from tick() while escaping — CombatController uses this
 *    as an early-return gate BEFORE any attack or movement code runs
 *  - Does NOT call setVelocity, attack(), or sprintReset()
 *  - Only touches the bot's held item (equips sword, restores after)
 *  - Uses ServerPlayerInteractionManager for block breaking — same as a
 *    real player would, no fake/direct block removal
 */
public class CobwebEscapeController {

    private enum WebState { IDLE, MINING }

    private final BotConfig cfg;

    private WebState state        = WebState.IDLE;
    private BlockPos miningPos    = null;
    private int      miningTicks  = 0;
    private float    mineProgress = 0.0f;
    private int      reactTicks   = 0; // reaction delay before starting to mine

    public CobwebEscapeController(BotConfig cfg) {
        this.cfg = cfg;
    }

    /**
     * Call at the very start of CombatController.tick(), before any other logic.
     * Returns true while the bot is trapped and mining — CombatController
     * should return immediately when this is true.
     *
     * @param fake  the bot's fake player
     * @param world the server world
     */
    public boolean tick(EntityPlayerMPFake fake, ServerWorld world) {
        boolean trapped = isTrappedInCobweb(fake, world);

        if (!trapped && state == WebState.IDLE) {
            return false; // not trapped, nothing to do
        }

        if (!trapped && state == WebState.MINING) {
            // Cobweb is gone — done
            endMine(fake);
            return false;
        }

        // Bot is trapped
        if (state == WebState.IDLE) {
            // Roll reaction delay before starting to mine
            reactTicks = rollReactTicks();
            state = WebState.MINING;
            miningPos = findCobwebToMine(fake, world);
            if (miningPos == null) {
                state = WebState.IDLE;
                return false;
            }
        }

        if (reactTicks > 0) {
            reactTicks--;
            return true; // trapped but still reacting
        }

        // Equip sword and mine
        tickSwordMine(fake, world);
        return true;
    }

    private void tickSwordMine(EntityPlayerMPFake fake, ServerWorld world) {
        if (miningPos == null) return;

        // Make sure we're still targeting a cobweb
        if (!world.getBlockState(miningPos).isOf(Blocks.COBWEB)) {
            // That cobweb is gone — find the next one
            miningPos = findCobwebToMine(fake, world);
            mineProgress = 0.0f;
            miningTicks  = 0;
            if (miningPos == null) { endMine(fake); return; }
        }

        // Equip sword (best tool for cobwebs in vanilla = sword)
        equipSword(fake);

        // Aim at the cobweb centre
        var blockCentre = miningPos.toCenterPos();
        fake.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES,
                    new net.minecraft.util.math.Vec3d(blockCentre.x, blockCentre.y, blockCentre.z));

        // Progress block breaking through the interaction manager

        var state = world.getBlockState(miningPos);
        float delta = state.calcBlockBreakingDelta(fake, world, miningPos);

        mineProgress += delta;
        miningTicks++;

        // processBlockBreakingAction is the public entry point for block breaking;
        // worldHeight is used for the anti-cheat bounds check — world.getHeight() is correct
        fake.interactionManager.processBlockBreakingAction(
                miningPos,
                PlayerActionC2SPacket.Action.START_DESTROY_BLOCK,
                Direction.UP,
                world.getHeight(),
                0);

        if (mineProgress >= 1.0f) {
            // Block should break — force remove if still there
            if (world.getBlockState(miningPos).isOf(Blocks.COBWEB)) {
                world.removeBlock(miningPos, false);
            }
            endMine(fake);
        }
    }

    private void endMine(EntityPlayerMPFake fake) {
        state        = WebState.IDLE;
        miningPos    = null;
        mineProgress = 0.0f;
        miningTicks  = 0;
    }

    private boolean isTrappedInCobweb(EntityPlayerMPFake fake, ServerWorld world) {
        BlockPos feet = fake.getBlockPos();
        BlockPos head = feet.up();
        return world.getBlockState(feet).isOf(Blocks.COBWEB)
            || world.getBlockState(head).isOf(Blocks.COBWEB);
    }

    /** Find the cobweb block closest to the bot's feet. */
    private BlockPos findCobwebToMine(EntityPlayerMPFake fake, ServerWorld world) {
        BlockPos feet = fake.getBlockPos();
        // Check feet first, then head, then adjacent
        for (BlockPos candidate : new BlockPos[]{ feet, feet.up(),
                feet.north(), feet.south(), feet.east(), feet.west() }) {
            if (world.getBlockState(candidate).isOf(Blocks.COBWEB)) {
                return candidate;
            }
        }
        return null;
    }

    private void equipSword(EntityPlayerMPFake fake) {
        var inv = fake.getInventory();
        for (int i = 0; i < 9; i++) {
            var stack = inv.getStack(i);
            if (!stack.isEmpty() && isSword(stack.getItem())) {
                inv.selectedSlot = i;
                return;
            }
        }
    }

    private boolean isSword(net.minecraft.item.Item item) {
        return item == Items.DIAMOND_SWORD
            || item == Items.NETHERITE_SWORD
            || item == Items.IRON_SWORD
            || item == Items.GOLDEN_SWORD
            || item == Items.STONE_SWORD
            || item == Items.WOODEN_SWORD;
    }

    private int rollReactTicks() {
        return switch (cfg.difficulty) {
            case EASY       -> 10 + (int)(Math.random() * 8);
            case MEDIUM     -> 5  + (int)(Math.random() * 5);
            case HARD       -> 2  + (int)(Math.random() * 3);
            case ULTRA_HARD -> (int)(Math.random() * 2);
        };
    }

    public void reset() {
        state        = WebState.IDLE;
        miningPos    = null;
        mineProgress = 0.0f;
        miningTicks  = 0;
        reactTicks   = 0;
    }
}