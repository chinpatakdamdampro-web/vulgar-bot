package com.pvpbot.ai.movement;

import carpet.patches.EntityPlayerMPFake;
import com.pvpbot.entity.PvPBotEntity;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

public class MovementController {

    private final PvPBotEntity bot;
    private final Random rng = new Random();

    private int strafeDir   = 1;
    private int strafeTimer = 0;
    private boolean sprinting       = false;
    private boolean jumpCritPending = false;
    private int blockedTicks = 0;
    private int stuckTicks = 0;
    private Vec3d lastPosForStuckCheck = null;

    private int knockbackSuppressTicks = 0;
    private static final int KNOCKBACK_SUPPRESS_TICKS = 6;

    // Combat backaway threshold
    private static final double MIN_COMBAT_RANGE    = 1.4;
    private static final double MIN_COMBAT_RANGE_SQ = MIN_COMBAT_RANGE * MIN_COMBAT_RANGE;

    // Follow stops this many blocks from the target â€” feels natural, not "touching"
    private static final double FOLLOW_STOP_DIST    = 2.5;
    private static final double FOLLOW_WALK_DIST    = 5.0; // walk when this close, sprint when farther

    private static final double WALK_SPEED   = 0.15;
    private static final double SPRINT_SPEED = 0.26;
    private static final int SAFE_DROP_LIMIT = 2;

    public MovementController(PvPBotEntity bot) {
        this.bot = bot;
        int roll = rng.nextInt(3);
        this.strafeDir = roll == 0 ? -1 : roll == 1 ? 1 : 0;
    }

    // -------------------------------------------------------------------------
    // Tick
    // -------------------------------------------------------------------------

    public void tick() {
        if (knockbackSuppressTicks > 0) knockbackSuppressTicks--;
        tickStuckRecovery(bot.getFakePlayer());
    }

    // -------------------------------------------------------------------------
    // Follow movement â€” stops FOLLOW_STOP_DIST blocks away, slows when close
    // FIX Bug 1: was stopping at 0.5 (touching). Now stops at 2.5 blocks.
    // -------------------------------------------------------------------------

    public void moveTo(Vec3d destination, boolean followMode) {
        EntityPlayerMPFake fp = bot.getFakePlayer();
        double dist = horizDist(fp.getPos(), destination);

        double stopDist = followMode ? FOLLOW_STOP_DIST : 0.5;

        if (dist < stopDist) {
            // Close enough â€” bleed off horizontal velocity naturally
            Vec3d vel = fp.getVelocity();
            fp.setVelocity(vel.x * 0.4, vel.y, vel.z * 0.4);
            setSprinting(false);
            return;
        }
        if (knockbackSuppressTicks > 0) return;

        double dx = destination.x - fp.getX();
        double dz = destination.z - fp.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001) return;
        dx /= len; dz /= len;

        // In follow mode: walk when already close, sprint when far
        boolean shouldSprint = followMode ? (dist > FOLLOW_WALK_DIST) : false;
        double speed = shouldSprint ? SPRINT_SPEED : WALK_SPEED;

        if (!applyVelocityOrSteer(fp, dx, dz, speed, shouldSprint)) return;

        // FIX Bug 3: jump over obstacles in the path
        tryJumpOverObstacle(fp, dx, dz);
    }

    // -------------------------------------------------------------------------
    // Sprint directly to a position (used by orbital retreat manoeuvre)
    // No follow dead-band â€” intended for precise tactical positioning
    // -------------------------------------------------------------------------

    public void sprintTo(Vec3d destination) {
        EntityPlayerMPFake fp = bot.getFakePlayer();
        double dist = horizDist(fp.getPos(), destination);

        if (dist < 0.6) {
            Vec3d vel = fp.getVelocity();
            fp.setVelocity(vel.x * 0.3, vel.y, vel.z * 0.3);
            setSprinting(false);
            return;
        }
        if (knockbackSuppressTicks > 0) return;

        double dx = destination.x - fp.getX();
        double dz = destination.z - fp.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001) return;
        dx /= len; dz /= len;

        if (!applyVelocityOrSteer(fp, dx, dz, SPRINT_SPEED, true)) return;
        tryJumpOverObstacle(fp, dx, dz);
    }

    // -------------------------------------------------------------------------
    // FIX Bug 3 â€” Jump over blocks in the path
    // Checks one block ahead in the movement direction. If there's a solid
    // block at foot level and open space above it, jump.
    // -------------------------------------------------------------------------

    private void tryJumpOverObstacle(EntityPlayerMPFake fp, double dx, double dz) {
        if (!fp.isOnGround()) return;
        if (fp.getVelocity().y > 0.01) return; // already jumping

        double checkX = fp.getX() + dx * 0.75;
        double checkZ = fp.getZ() + dz * 0.75;
        int bx = (int) Math.floor(checkX);
        int by = (int) Math.floor(fp.getY());
        int bz = (int) Math.floor(checkZ);

        var world = fp.getWorld();
        BlockPos feetAhead = new BlockPos(bx, by, bz);
        BlockPos headAhead = feetAhead.up();
        BlockPos clearAbove = headAhead.up(); // need clearance above jump destination

        BlockState feetState  = world.getBlockState(feetAhead);
        BlockState headState  = world.getBlockState(headAhead);
        BlockState clearState = world.getBlockState(clearAbove);

        // Jump if there's a solid block at our foot level ahead but open above
        boolean blockedAtFeet = !feetState.isAir() && feetState.isSolidBlock(world, feetAhead);
        boolean openAtHead    = headState.isAir();
        boolean openAboveJump = clearState.isAir();

        if (blockedAtFeet && openAtHead && openAboveJump) {
            Vec3d vel = fp.getVelocity();
            fp.setVelocity(vel.x, 0.42, vel.z);
        }
    }

    // -------------------------------------------------------------------------
    // Combat movement
    // -------------------------------------------------------------------------

    public void combatMoveTo(ServerPlayerEntity target) {
        EntityPlayerMPFake fp = bot.getFakePlayer();

        lookAtTarget(target);

        double hSpeed = horizontalSpeed(fp);
        if (hSpeed > 0.30 && !fp.isSprinting()) {
            knockbackSuppressTicks = KNOCKBACK_SUPPRESS_TICKS;
        }

        if (knockbackSuppressTicks > 0) return;

        double distSq      = fp.squaredDistanceTo(target);
        double preferredSq = bot.getConfig().preferredRange * bot.getConfig().preferredRange;
        double reachSq     = bot.getConfig().attackReach * bot.getConfig().attackReach;

        if (distSq < MIN_COMBAT_RANGE_SQ) {
            backAwayFrom(target);
        } else if (distSq > reachSq * 4) {
            // Far away â€” sprint in, using combat moveTo (no follow dead-band)
            moveTowardsCombat(target.getPos(), true);
        } else if (distSq > preferredSq) {
            moveTowardsCombat(target.getPos(), true);
            maybeStrafe();
        } else {
            moveTowardsCombat(target.getPos(), false);
            maybeStrafe();
        }

        strafeTimer++;
        if (strafeTimer >= bot.getConfig().strafeChangeTicks) {
            strafeTimer = 0;
            int roll = rng.nextInt(3);
            strafeDir = roll == 0 ? -1 : roll == 1 ? 1 : 0;
        }
    }

    // Internal combat movement â€” small stop distance (0.5), no follow dead-band
    private void moveTowardsCombat(Vec3d dest, boolean sprint) {
        EntityPlayerMPFake fp = bot.getFakePlayer();
        double dist = horizDist(fp.getPos(), dest);
        if (dist < 0.5) return;

        double dx = dest.x - fp.getX();
        double dz = dest.z - fp.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001) return;
        dx /= len; dz /= len;

        double speed = sprint ? SPRINT_SPEED : WALK_SPEED;
        if (!applyVelocityOrSteer(fp, dx, dz, speed, sprint)) return;
        tryJumpOverObstacle(fp, dx, dz);
    }

    private void backAwayFrom(ServerPlayerEntity target) {
        EntityPlayerMPFake fp = bot.getFakePlayer();
        double dx = fp.getX() - target.getX();
        double dz = fp.getZ() - target.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001) {
            double angle = rng.nextDouble() * Math.PI * 2;
            dx = Math.cos(angle); dz = Math.sin(angle);
        } else { dx /= len; dz /= len; }
        double strafeX = -dz * (strafeDir != 0 ? strafeDir : 1) * 0.08;
        double strafeZ  =  dx * (strafeDir != 0 ? strafeDir : 1) * 0.08;
        if (!applyVelocityOrSteer(fp, dx + strafeX, dz + strafeZ, WALK_SPEED, false)) return;
    }

    private void maybeStrafe() {
        if (rng.nextDouble() < bot.getConfig().strafeFrequency) return;
        applyStrafe(strafeDir);
    }

    public void lookAtTarget(ServerPlayerEntity target) {
        lookAt(target.getPos().add(0, target.getStandingEyeHeight(), 0));
    }

    public void lookAt(Vec3d pos) {
        EntityPlayerMPFake fp = bot.getFakePlayer();
        Vec3d diff = pos.subtract(fp.getEyePos());
        double horizDist = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        float yaw   = (float) Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90f;
        float pitch = (float) -Math.toDegrees(Math.atan2(diff.y, horizDist));
        fp.setHeadYaw(yaw); fp.setBodyYaw(yaw); fp.setYaw(yaw); fp.setPitch(pitch);
    }

    // -------------------------------------------------------------------------
    // Retreat
    // -------------------------------------------------------------------------

    public void retreatFrom(ServerPlayerEntity target) {
        EntityPlayerMPFake fp = bot.getFakePlayer();
        if (knockbackSuppressTicks > 0) return;

        double dx = fp.getX() - target.getX();
        double dz = fp.getZ() - target.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001) { dx = rng.nextDouble() * 2 - 1; dz = rng.nextDouble() * 2 - 1; len = 1; }
        dx /= len; dz /= len;
        if (!applyVelocityOrSteer(fp, dx, dz, SPRINT_SPEED, true)) return;
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
        fp.setHeadYaw(yaw); fp.setBodyYaw(yaw); fp.setYaw(yaw);
        fp.setPitch(0f);
    }

    // -------------------------------------------------------------------------
    // Jump crit
    // -------------------------------------------------------------------------

    public void jumpForCrit() {
        EntityPlayerMPFake fp = bot.getFakePlayer();
        if (!fp.isOnGround()) return;
        Vec3d vel = fp.getVelocity();
        fp.setVelocity(vel.x, 0.42, vel.z);
        jumpCritPending = true;
    }

    public boolean isDescendingFromCrit() {
        if (!jumpCritPending) return false;
        boolean falling = bot.getFakePlayer().getVelocity().y < -0.05;
        if (falling) jumpCritPending = false;
        return falling;
    }

    /** True when airborne and falling (any cause â€” jump, wind burst, etc.) */
    public boolean isAirborneFalling() {
        EntityPlayerMPFake fp = bot.getFakePlayer();
        return !fp.isOnGround() && fp.getVelocity().y < -0.05;
    }

    /** True when airborne with strong upward velocity (wind charge launch) */
    public boolean isWindBurstAirborne() {
        EntityPlayerMPFake fp = bot.getFakePlayer();
        return !fp.isOnGround() && fp.getVelocity().y > 0.25;
    }

    // -------------------------------------------------------------------------
    // Sprint reset (w-tap)
    // -------------------------------------------------------------------------

    public void sprintReset() {
        setSprinting(false);
        Vec3d vel = bot.getFakePlayer().getVelocity();
        bot.getFakePlayer().setVelocity(vel.x * 0.4, vel.y, vel.z * 0.4);
    }

    public void resumeSprint() { setSprinting(true); }

    // -------------------------------------------------------------------------
    // Stop
    // -------------------------------------------------------------------------

    public void stop() {
        EntityPlayerMPFake fp = bot.getFakePlayer();
        Vec3d vel = fp.getVelocity();
        fp.setVelocity(0, vel.y, 0);
        setSprinting(false);
        strafeDir = 0;
        jumpCritPending = false;
        knockbackSuppressTicks = 0;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void setSprinting(boolean sprint) {
        this.sprinting = sprint;
        bot.getFakePlayer().setSprinting(sprint);
    }

    private void applyStrafe(int direction) {
        if (direction == 0) return;
        EntityPlayerMPFake fp = bot.getFakePlayer();
        double radYaw = Math.toRadians(fp.getYaw() + 90f * direction);
        double sx = Math.sin(radYaw) * 0.12;
        double sz = -Math.cos(radYaw) * 0.12;
        Vec3d vel = fp.getVelocity();
        fp.setVelocity(vel.x + sx, vel.y, vel.z + sz);
    }


    private boolean applySafeHorizontalVelocity(double dirX, double dirZ, double speed, boolean sprint) {
        EntityPlayerMPFake fp = bot.getFakePlayer();
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (len < 0.001) return false;
        double nx = dirX / len;
        double nz = dirZ / len;

        if (bot.getConfig().pathMode == com.pvpbot.config.BotConfig.PathMode.SAFE
                && !isSafeGroundAhead(fp, nx, nz)) {
            setSprinting(false);
            Vec3d vel = fp.getVelocity();
            fp.setVelocity(vel.x * 0.2, vel.y, vel.z * 0.2);
            return false;
        }

        Vec3d vel = fp.getVelocity();
        fp.setVelocity(nx * speed, vel.y, nz * speed);
        setSprinting(sprint);
        return true;
    }

    private boolean isSafeGroundAhead(EntityPlayerMPFake fp, double dx, double dz) {
        var world = fp.getWorld();
        int footY = (int) Math.floor(fp.getY());
        double[] probes = {0.75, 1.1};
        for (double d : probes) {
            int x = (int) Math.floor(fp.getX() + dx * d);
            int z = (int) Math.floor(fp.getZ() + dz * d);

            BlockPos feet = new BlockPos(x, footY, z);
            BlockPos head = feet.up();
            if (!world.getBlockState(feet).isAir() || !world.getBlockState(head).isAir()) {
                continue;
            }

            boolean foundSupport = false;
            for (int drop = 1; drop <= SAFE_DROP_LIMIT; drop++) {
                BlockPos below = feet.down(drop);
                BlockState belowState = world.getBlockState(below);
                if (!belowState.isAir() && belowState.isSolidBlock(world, below)) {
                    foundSupport = true;
                    break;
                }
            }
            if (!foundSupport) return false;
        }
        return true;
    }


    private boolean isTraversableAt(EntityPlayerMPFake fp, int x, int y, int z) {
        var world = fp.getWorld();
        BlockPos feet = new BlockPos(x, y, z);
        BlockPos head = feet.up();
        return world.getBlockState(feet).isAir() && world.getBlockState(head).isAir();
    }

    private boolean applyVelocityOrSteer(EntityPlayerMPFake fp, double dx, double dz, double speed, boolean sprint) {
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001) return false;
        double nx = dx / len;
        double nz = dz / len;

        if (tryMoveDirection(fp, nx, nz, speed, sprint)) return true;

        // steer left/right around wall/tree
        double leftX = -nz, leftZ = nx;
        double rightX = nz, rightZ = -nx;

        // bias alternates by blockedTicks so it doesn't always choose same side
        boolean leftFirst = (blockedTicks % 2 == 0);

        if (leftFirst) {
            if (tryMoveDirection(fp, 0.75 * nx + 0.65 * leftX, 0.75 * nz + 0.65 * leftZ, speed, sprint)) return true;
            if (tryMoveDirection(fp, 0.75 * nx + 0.65 * rightX, 0.75 * nz + 0.65 * rightZ, speed, sprint)) return true;
        } else {
            if (tryMoveDirection(fp, 0.75 * nx + 0.65 * rightX, 0.75 * nz + 0.65 * rightZ, speed, sprint)) return true;
            if (tryMoveDirection(fp, 0.75 * nx + 0.65 * leftX, 0.75 * nz + 0.65 * leftZ, speed, sprint)) return true;
        }

        // fallback: pure strafe sidestep if front is blocked
        if (tryMoveDirection(fp, leftX, leftZ, WALK_SPEED, false)) return true;
        if (tryMoveDirection(fp, rightX, rightZ, WALK_SPEED, false)) return true;

        // fully blocked
        setSprinting(false);
        Vec3d vel = fp.getVelocity();
        fp.setVelocity(vel.x * 0.2, vel.y, vel.z * 0.2);
        blockedTicks++;
        return false;
    }

    private boolean tryMoveDirection(EntityPlayerMPFake fp, double dx, double dz, double speed, boolean sprint) {
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001) return false;
        double nx = dx / len;
        double nz = dz / len;

        int y = (int) Math.floor(fp.getY());
        int x1 = (int) Math.floor(fp.getX() + nx * 0.85);
        int z1 = (int) Math.floor(fp.getZ() + nz * 0.85);

        // avoid walking into solid wall/tree
        if (!isTraversableAt(fp, x1, y, z1)) return false;

        // keep SAFE drop behavior
        if (bot.getConfig().pathMode == com.pvpbot.config.BotConfig.PathMode.SAFE
                && !isSafeGroundAhead(fp, nx, nz)) return false;

        Vec3d vel = fp.getVelocity();
        fp.setVelocity(nx * speed, vel.y, nz * speed);
        setSprinting(sprint);
        blockedTicks = 0;
        return true;
    }

    private void tickStuckRecovery(EntityPlayerMPFake fp) {
        Vec3d pos = fp.getPos();
        if (lastPosForStuckCheck == null) {
            lastPosForStuckCheck = pos;
            return;
        }
        double d2 = pos.squaredDistanceTo(lastPosForStuckCheck);
        lastPosForStuckCheck = pos;

        if (d2 < 0.01 && horizontalSpeed(fp) < 0.05) {
            stuckTicks++;
            if (stuckTicks > 14) {
                // unstuck nudge + optional jump
                double ang = rng.nextDouble() * Math.PI * 2.0;
                Vec3d vel = fp.getVelocity();
                fp.setVelocity(Math.cos(ang) * 0.16, vel.y, Math.sin(ang) * 0.16);
                if (fp.isOnGround()) fp.setVelocity(fp.getVelocity().x, 0.42, fp.getVelocity().z);
                stuckTicks = 0;
            }
        } else {
            stuckTicks = 0;
        }
    }


    private double horizontalSpeed(EntityPlayerMPFake fp) {
        var vel = fp.getVelocity();
        return Math.sqrt(vel.x * vel.x + vel.z * vel.z);
    }

    /** XZ-only distance between two Vec3d positions (ignores Y). */
    private static double horizDist(Vec3d a, Vec3d b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public boolean isSprinting()                  { return sprinting; }
    public int getKnockbackSuppressTicks()        { return knockbackSuppressTicks; }
    public static double getFollowStopDist()      { return FOLLOW_STOP_DIST; }
    public static double getMinCombatRangeSq()    { return MIN_COMBAT_RANGE_SQ; }
}
