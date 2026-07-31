package com.pvpbot.ai.movement;

import carpet.patches.EntityPlayerMPFake;
import com.pvpbot.config.BotConfig;
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

    // Follow stops this many blocks from the target — feels natural, not "touching"
    private static final double FOLLOW_STOP_DIST    = 2.5;
    private static final double FOLLOW_WALK_DIST    = 5.0;

    private static final double WALK_SPEED   = 0.15;
    private static final double SPRINT_SPEED = 0.26;
    private static final int SAFE_DROP_LIMIT = 2;
    private static final int SAFE_CHASE_DROP_LIMIT = 4;

    public MovementController(PvPBotEntity bot) {
        this.bot = bot;
        int roll = rng.nextInt(3);
        this.strafeDir = roll == 0 ? -1 : roll == 1 ? 1 : 0;
    }

    // -------------------------------------------------------------------------
    // Tick
    // -------------------------------------------------------------------------

    public void tick() {
        EntityPlayerMPFake fp = bot.getFakePlayer();
        if (knockbackSuppressTicks > 0) knockbackSuppressTicks--;
        tickLedgeLatch(fp);
        tickStuckRecovery(fp);
    }

    // -------------------------------------------------------------------------
    // Follow movement — stops FOLLOW_STOP_DIST blocks away, slows when close
    // -------------------------------------------------------------------------

    public void moveTo(Vec3d destination, boolean followMode) {
        EntityPlayerMPFake fp = bot.getFakePlayer();
        double dist = horizDist(fp.getPos(), destination);

        double stopDist = followMode ? FOLLOW_STOP_DIST : 0.5;
        if (dist < stopDist) {
            bleedHorizontalVelocity(fp, 0.4);
            setSprinting(false);
            return;
        }
        if (knockbackSuppressTicks > 0) return;

        double[] dir = directionTo(fp.getPos(), destination);
        if (dir == null) return;

        boolean shouldSprint = followMode ? (dist > FOLLOW_WALK_DIST) : false;
        double speed = shouldSprint ? SPRINT_SPEED : WALK_SPEED;

        applyNavigatedHorizontalVelocity(destination, dir[0], dir[1], speed, shouldSprint, false);
    }

    // -------------------------------------------------------------------------
    // Sprint directly to a position (used by orbital retreat manoeuvre)
    // -------------------------------------------------------------------------

    public void sprintTo(Vec3d destination) {
        EntityPlayerMPFake fp = bot.getFakePlayer();
        double dist = horizDist(fp.getPos(), destination);

        if (dist < 0.6) {
            bleedHorizontalVelocity(fp, 0.3);
            setSprinting(false);
            return;
        }
        if (knockbackSuppressTicks > 0) return;

        double[] dir = directionTo(fp.getPos(), destination);
        if (dir == null) return;

        applyNavigatedHorizontalVelocity(destination, dir[0], dir[1], SPRINT_SPEED, true, true);
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
            moveTowardsCombat(target.getPos(), true);
        } else if (distSq > preferredSq) {
            moveTowardsCombat(target.getPos(), true);
            maybeStrafe(target);
        } else {
            moveTowardsCombat(target.getPos(), false);
            maybeStrafe(target);
        }

        strafeTimer++;
        if (strafeTimer >= bot.getConfig().strafeChangeTicks) {
            strafeTimer = 0;
            int roll = rng.nextInt(3);
            strafeDir = roll == 0 ? -1 : roll == 1 ? 1 : 0;
        }
    }

    private void moveTowardsCombat(Vec3d dest, boolean sprint) {
        EntityPlayerMPFake fp = bot.getFakePlayer();
        double dist = horizDist(fp.getPos(), dest);
        if (dist < 0.5) return;

        double[] dir = directionTo(fp.getPos(), dest);
        if (dir == null) return;

        double speed = sprint ? SPRINT_SPEED : WALK_SPEED;
        applyNavigatedHorizontalVelocity(dest, dir[0], dir[1], speed, sprint, false);
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
        double strafeZ =  dx * (strafeDir != 0 ? strafeDir : 1) * 0.08;
        applyNavigatedHorizontalVelocity(target.getPos(), dx + strafeX, dz + strafeZ, WALK_SPEED, false, true);
    }

    private void maybeStrafe(ServerPlayerEntity target) {
        if (rng.nextDouble() < bot.getConfig().strafeFrequency) return;
        applyStrafe(strafeDir, target.getPos());
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
        applyNavigatedHorizontalVelocity(target.getPos(), dx, dz, SPRINT_SPEED, true, true);
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

    /** True when airborne and falling (any cause — jump, wind burst, etc.) */
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
        blockedTicks = 0;
        stuckTicks = 0;
        lastPosForStuckCheck = fp.getPos();
    }

    // -------------------------------------------------------------------------
    // Navigation helpers
    // -------------------------------------------------------------------------

    private boolean applyNavigatedHorizontalVelocity(Vec3d destination, double dirX, double dirZ,
                                                     double speed, boolean sprint, boolean retreating) {
        EntityPlayerMPFake fp = bot.getFakePlayer();
        double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (len < 0.001) return false;
        double nx = dirX / len;
        double nz = dirZ / len;

        if (bot.getConfig().pathMode == BotConfig.PathMode.LEGACY) {
            applyRawVelocity(fp, nx, nz, speed, sprint);
            tryJumpOverObstacle(fp, nx, nz);
            return true;
        }

        boolean targetBelow = destination.y < fp.getY() - 1.25;
        int dropLimit = targetBelow && !retreating ? SAFE_CHASE_DROP_LIMIT : SAFE_DROP_LIMIT;

        if (tryMoveDirection(fp, nx, nz, speed, sprint, dropLimit)) return true;

        // If the target is below, actively search for a nearby safe drop/entrance instead
        // of freezing above them. This is intentionally local and conservative so combat
        // movement stays predictable.
        if (targetBelow && tryDownhillPath(fp, destination, speed, sprint)) return true;

        double leftX = -nz, leftZ = nx;
        double rightX = nz, rightZ = -nx;
        boolean leftFirst = (blockedTicks % 2 == 0);

        if (leftFirst) {
            if (tryMoveDirection(fp, 0.75 * nx + 0.65 * leftX, 0.75 * nz + 0.65 * leftZ, speed, sprint, dropLimit)) return true;
            if (tryMoveDirection(fp, 0.75 * nx + 0.65 * rightX, 0.75 * nz + 0.65 * rightZ, speed, sprint, dropLimit)) return true;
        } else {
            if (tryMoveDirection(fp, 0.75 * nx + 0.65 * rightX, 0.75 * nz + 0.65 * rightZ, speed, sprint, dropLimit)) return true;
            if (tryMoveDirection(fp, 0.75 * nx + 0.65 * leftX, 0.75 * nz + 0.65 * leftZ, speed, sprint, dropLimit)) return true;
        }

        if (tryMoveDirection(fp, leftX, leftZ, WALK_SPEED, false, dropLimit)) return true;
        if (tryMoveDirection(fp, rightX, rightZ, WALK_SPEED, false, dropLimit)) return true;

        blockedTicks++;
        bleedHorizontalVelocity(fp, 0.2);
        setSprinting(false);
        return false;
    }

    private boolean tryMoveDirection(EntityPlayerMPFake fp, double dx, double dz, double speed,
                                     boolean sprint, int dropLimit) {
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001) return false;
        double nx = dx / len;
        double nz = dz / len;

        if (!canMoveThroughOrStepUp(fp, nx, nz)) return false;
        if (!isSafeGroundAhead(fp, nx, nz, dropLimit)) return false;

        applyRawVelocity(fp, nx, nz, speed, sprint);
        tryJumpOverObstacle(fp, nx, nz);
        blockedTicks = 0;
        return true;
    }

    private boolean tryDownhillPath(EntityPlayerMPFake fp, Vec3d destination, double speed, boolean sprint) {
        double bestScore = Double.MAX_VALUE;
        double bestX = 0;
        double bestZ = 0;

        for (int i = 0; i < 16; i++) {
            double angle = (Math.PI * 2.0 * i) / 16.0;
            double dx = Math.cos(angle);
            double dz = Math.sin(angle);
            if (!canMoveThroughOrStepUp(fp, dx, dz)) continue;
            if (!isSafeGroundAhead(fp, dx, dz, SAFE_CHASE_DROP_LIMIT)) continue;

            int landingY = landingSupportY(fp, dx, dz, SAFE_CHASE_DROP_LIMIT);
            if (landingY >= Math.floor(fp.getY())) continue;

            Vec3d projected = new Vec3d(fp.getX() + dx, landingY + 1.0, fp.getZ() + dz);
            double score = horizDist(projected, destination) + Math.abs(projected.y - destination.y) * 0.75;
            if (score < bestScore) {
                bestScore = score;
                bestX = dx;
                bestZ = dz;
            }
        }

        if (bestScore == Double.MAX_VALUE) return false;
        applyRawVelocity(fp, bestX, bestZ, speed, sprint);
        blockedTicks = 0;
        return true;
    }

    private boolean canMoveThroughOrStepUp(EntityPlayerMPFake fp, double dx, double dz) {
        var world = fp.getWorld();
        int y = (int) Math.floor(fp.getY());
        int x = (int) Math.floor(fp.getX() + dx * 0.85);
        int z = (int) Math.floor(fp.getZ() + dz * 0.85);

        BlockPos feet = new BlockPos(x, y, z);
        BlockPos head = feet.up();
        BlockState feetState = world.getBlockState(feet);
        BlockState headState = world.getBlockState(head);

        if (feetState.isAir() && headState.isAir()) return true;

        // One-block step-up is fine if there is head clearance; two-block walls/trees
        // are not, so steering picks a side instead of ramming the obstacle.
        BlockPos stepClear = head.up();
        boolean solidStep = !feetState.isAir() && feetState.isSolidBlock(world, feet);
        return solidStep && headState.isAir() && world.getBlockState(stepClear).isAir() && fp.isOnGround();
    }

    private void tryJumpOverObstacle(EntityPlayerMPFake fp, double dx, double dz) {
        if (!fp.isOnGround()) return;
        if (fp.getVelocity().y > 0.01) return;

        var world = fp.getWorld();
        int bx = (int) Math.floor(fp.getX() + dx * 0.85);
        int by = (int) Math.floor(fp.getY());
        int bz = (int) Math.floor(fp.getZ() + dz * 0.85);

        BlockPos feetAhead = new BlockPos(bx, by, bz);
        BlockPos headAhead = feetAhead.up();
        BlockPos clearAbove = headAhead.up();

        BlockState feetState = world.getBlockState(feetAhead);
        boolean blockedAtFeet = !feetState.isAir() && feetState.isSolidBlock(world, feetAhead);
        boolean openAtHead = world.getBlockState(headAhead).isAir();
        boolean openAboveJump = world.getBlockState(clearAbove).isAir();

        if (blockedAtFeet && openAtHead && openAboveJump) {
            Vec3d vel = fp.getVelocity();
            fp.setVelocity(vel.x, 0.42, vel.z);
        }
    }

    private boolean isSafeGroundAhead(EntityPlayerMPFake fp, double dx, double dz, int dropLimit) {
        var world = fp.getWorld();
        int footY = (int) Math.floor(fp.getY());
        double[] probes = {0.65, 1.05};
        for (double d : probes) {
            int x = (int) Math.floor(fp.getX() + dx * d);
            int z = (int) Math.floor(fp.getZ() + dz * d);

            BlockPos feet = new BlockPos(x, footY, z);
            BlockPos head = feet.up();
            BlockState feetState = world.getBlockState(feet);
            BlockState headState = world.getBlockState(head);

            if (!feetState.isAir()) {
                if (feetState.isSolidBlock(world, feet) && headState.isAir() && world.getBlockState(head.up()).isAir()) {
                    continue; // climbable one-block step
                }
                return false;
            }
            if (!headState.isAir()) return false;

            boolean foundSupport = false;
            for (int drop = 1; drop <= dropLimit; drop++) {
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

    private int landingSupportY(EntityPlayerMPFake fp, double dx, double dz, int dropLimit) {
        var world = fp.getWorld();
        int footY = (int) Math.floor(fp.getY());
        int x = (int) Math.floor(fp.getX() + dx * 1.05);
        int z = (int) Math.floor(fp.getZ() + dz * 1.05);
        BlockPos feet = new BlockPos(x, footY, z);
        for (int drop = 1; drop <= dropLimit; drop++) {
            BlockPos below = feet.down(drop);
            BlockState belowState = world.getBlockState(below);
            if (!belowState.isAir() && belowState.isSolidBlock(world, below)) {
                return below.getY();
            }
        }
        return footY;
    }

    private void tickLedgeLatch(EntityPlayerMPFake fp) {
        if (!bot.getConfig().ledgeLatchEnabled) return;
        if (fp.isOnGround() || fp.getVelocity().y > -0.35) return;

        var world = fp.getWorld();
        int y = (int) Math.floor(fp.getY());
        double bestDistSq = Double.MAX_VALUE;
        BlockPos best = null;

        for (int dy = -1; dy <= 1; dy++) {
            for (int ox = -1; ox <= 1; ox++) {
                for (int oz = -1; oz <= 1; oz++) {
                    if (ox == 0 && oz == 0) continue;
                    BlockPos ledge = new BlockPos(
                            (int) Math.floor(fp.getX()) + ox,
                            y + dy,
                            (int) Math.floor(fp.getZ()) + oz
                    );
                    BlockState support = world.getBlockState(ledge.down());
                    if (support.isAir() || !support.isSolidBlock(world, ledge.down())) continue;
                    if (!world.getBlockState(ledge).isAir()) continue;
                    if (!world.getBlockState(ledge.up()).isAir()) continue;

                    double cx = ledge.getX() + 0.5;
                    double cz = ledge.getZ() + 0.5;
                    double dx = cx - fp.getX();
                    double dz = cz - fp.getZ();
                    double d2 = dx * dx + dz * dz;
                    if (d2 < bestDistSq) {
                        bestDistSq = d2;
                        best = ledge;
                    }
                }
            }
        }

        if (best == null || bestDistSq > 2.25) return;

        double dx = (best.getX() + 0.5) - fp.getX();
        double dz = (best.getZ() + 0.5) - fp.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001) return;

        Vec3d vel = fp.getVelocity();
        double slowedFall = Math.max(vel.y, -0.22);
        fp.setVelocity(dx / len * 0.18, slowedFall, dz / len * 0.18);
    }

    private void tickStuckRecovery(EntityPlayerMPFake fp) {
        if (bot.getState() == PvPBotEntity.BotState.IDLE) {
            stuckTicks = 0;
            lastPosForStuckCheck = fp.getPos();
            return;
        }

        Vec3d pos = fp.getPos();
        if (lastPosForStuckCheck == null) {
            lastPosForStuckCheck = pos;
            return;
        }
        double movedSq = pos.squaredDistanceTo(lastPosForStuckCheck);
        lastPosForStuckCheck = pos;

        if (blockedTicks > 4 || (movedSq < 0.0025 && horizontalSpeed(fp) < 0.04)) {
            stuckTicks++;
            if (stuckTicks > 18) {
                double angle = rng.nextDouble() * Math.PI * 2.0;
                Vec3d vel = fp.getVelocity();
                fp.setVelocity(Math.cos(angle) * 0.12, vel.y, Math.sin(angle) * 0.12);
                if (fp.isOnGround() && blockedTicks > 8) {
                    fp.setVelocity(fp.getVelocity().x, 0.42, fp.getVelocity().z);
                }
                stuckTicks = 0;
                blockedTicks = 0;
            }
        } else {
            stuckTicks = 0;
        }
    }

    private void applyStrafe(int direction, Vec3d destination) {
        if (direction == 0) return;
        EntityPlayerMPFake fp = bot.getFakePlayer();
        double radYaw = Math.toRadians(fp.getYaw() + 90f * direction);
        double sx = Math.sin(radYaw);
        double sz = -Math.cos(radYaw);
        applyNavigatedHorizontalVelocity(destination, sx, sz, 0.12, false, false);
    }

    private void applyRawVelocity(EntityPlayerMPFake fp, double nx, double nz, double speed, boolean sprint) {
        Vec3d vel = fp.getVelocity();
        fp.setVelocity(nx * speed, vel.y, nz * speed);
        setSprinting(sprint);
    }

    private void bleedHorizontalVelocity(EntityPlayerMPFake fp, double factor) {
        Vec3d vel = fp.getVelocity();
        fp.setVelocity(vel.x * factor, vel.y, vel.z * factor);
    }

    private void setSprinting(boolean sprint) {
        this.sprinting = sprint;
        bot.getFakePlayer().setSprinting(sprint);
    }

    private double[] directionTo(Vec3d from, Vec3d to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.001) return null;
        return new double[] { dx / len, dz / len };
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
