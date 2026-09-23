package com.vltbr.minidrone.sim;

import java.util.function.Predicate;

/**
 * Moves an axis-aligned box through the world without letting it pass through
 * anything.
 *
 * <p>The world is asked one question, "is the box free here?", and this decides how
 * far the box may go. That split is deliberate: the rule that a vehicle stops at a
 * wall, slides along it and comes to rest on the floor is the part worth testing, and
 * it is tested without a world. Vanilla answers the question in game, using the
 * shapes of the blocks that overlap the box, so the vehicle collides with exactly
 * what the game says is solid.
 *
 * <p>Motion is resolved one axis at a time (the classic order x, z, y), which is what
 * makes sliding work: a box that cannot move north can still move up.
 *
 * <p>The search is a subdivision rather than a swept ray. Per-tick steps are
 * centimetres - 1.4 m/s is 7 cm, and the fastest fall is 40 cm - and the caller caps
 * anything larger, so a coarse subdivision is exact enough while staying simple.
 */
public final class PhysicsStep {
    /** How finely a blocked axis is backed off before giving up. */
    static final int SUBDIVISIONS = 16;

    /** Where the box ended up, and what stopped it. */
    public record Result(double x, double y, double z, boolean blockedX, boolean blockedY, boolean blockedZ) {
        public boolean blockedHorizontally() {
            return blockedX || blockedZ;
        }

        public boolean blockedVertically() {
            return blockedY;
        }
    }

    /** A world-space box: {@code [minX, minY, minZ, maxX, maxY, maxZ]}. */
    public static double[] boxAt(double x, double y, double z, double halfWidth, double halfHeight) {
        return new double[] {
            x - halfWidth, y - halfHeight, z - halfWidth,
            x + halfWidth, y + halfHeight, z + halfWidth
        };
    }

    private PhysicsStep() {
    }

    /**
     * Resolves a step from {@code (x, y, z)} by {@code (dx, dy, dz)}.
     *
     * <p>{@code isFree} answers whether the box is clear at a candidate position; it
     * is asked about the candidate, never about the path, so the caller must keep the
     * steps small (see the class comment).
     */
    public static Result resolve(
        double x,
        double y,
        double z,
        double dx,
        double dy,
        double dz,
        double halfWidth,
        double halfHeight,
        Predicate<double[]> isFree
    ) {
        boolean blockedX = false;
        boolean blockedY = false;
        boolean blockedZ = false;

        double resolvedX = x + dx;
        if (dx != 0.0) {
            if (isFree.test(boxAt(resolvedX, y, z, halfWidth, halfHeight))) {
                // free
            } else {
                resolvedX = backOff(x, dx, (candidate) ->
                    isFree.test(boxAt(candidate, y, z, halfWidth, halfHeight)));
                blockedX = true;
            }
        }
        // The later passes close over the settled positions, so they have to be final.
        final double settledX = resolvedX;

        double resolvedZ = z + dz;
        if (dz != 0.0) {
            if (isFree.test(boxAt(settledX, y, resolvedZ, halfWidth, halfHeight))) {
                // free
            } else {
                resolvedZ = backOff(z, dz, (candidate) ->
                    isFree.test(boxAt(settledX, y, candidate, halfWidth, halfHeight)));
                blockedZ = true;
            }
        }
        final double settledZ = resolvedZ;

        double resolvedY = y + dy;
        if (dy != 0.0) {
            if (isFree.test(boxAt(settledX, resolvedY, settledZ, halfWidth, halfHeight))) {
                // free
            } else {
                resolvedY = backOff(y, dy, (candidate) ->
                    isFree.test(boxAt(settledX, candidate, settledZ, halfWidth, halfHeight)));
                blockedY = true;
            }
        }

        return new Result(resolvedX, resolvedY, resolvedZ, blockedX, blockedY, blockedZ);
    }

    /**
     * Resolves a step of any size by sweeping it in pieces no longer than
     * {@code maxStepLength}.
     *
     * <p>{@link #resolve} is only exact for small steps, and the caller used to apply its cap
     * by *skipping the world entirely* once a step was big enough - which turned a fast fall
     * into a teleport. It is not a teleport: it is a fall, and it has to meet the floor.
     * Falling at the terminal 8 m/s in a world of 0.25 m blocks is 32 blocks per second, 1.6
     * blocks a tick, comfortably past the old 1.5 cap: the vehicle went through the ground
     * and kept going.
     *
     * <p>A step longer than {@code maxStepLength * maxSubSteps} is treated as a carry again -
     * that is a spawn or a hand placement, which really is a teleport - so a nonsensical
     * delta cannot turn into an unbounded loop.
     */
    public static Result resolveSwept(
        double x,
        double y,
        double z,
        double dx,
        double dy,
        double dz,
        double halfWidth,
        double halfHeight,
        Predicate<double[]> isFree,
        double maxStepLength,
        int maxSubSteps
    ) {
        final double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length <= 0.0) {
            return new Result(x, y, z, false, false, false);
        }
        if (maxStepLength <= 0.0 || length <= maxStepLength) {
            return resolve(x, y, z, dx, dy, dz, halfWidth, halfHeight, isFree);
        }
        final int pieces = (int) Math.ceil(length / maxStepLength);
        if (pieces > maxSubSteps) {
            // Beyond the budget this is a carry (spawn, placement): applied as asked.
            return new Result(x + dx, y + dy, z + dz, false, false, false);
        }

        double currentX = x;
        double currentY = y;
        double currentZ = z;
        boolean blockedX = false;
        boolean blockedY = false;
        boolean blockedZ = false;
        double remaining = length;
        for (int piece = 0; piece < pieces && remaining > 0.0; piece++) {
            final double covered = Math.min(maxStepLength, remaining);
            final double portion = covered / length;
            final Result step = resolve(
                currentX, currentY, currentZ,
                dx * portion, dy * portion, dz * portion,
                halfWidth, halfHeight, isFree);
            currentX = step.x();
            currentY = step.y();
            currentZ = step.z();
            blockedX |= step.blockedX();
            blockedY |= step.blockedY();
            blockedZ |= step.blockedZ();
            remaining -= covered;
        }
        return new Result(currentX, currentY, currentZ, blockedX, blockedY, blockedZ);
    }

    /**
     * Finds the furthest fraction of a blocked axis the box can still take.
     *
     * <p>Returns the last position the caller's own start point allows: a box that
     * begins embedded in a block is left where it is rather than being pushed out,
     * which keeps a badly placed vehicle recoverable instead of ejected.
     */
    private static double backOff(
        double start,
        double delta,
        Predicate<Double> isFreeAt
    ) {
        for (int step = SUBDIVISIONS - 1; step >= 1; step--) {
            double candidate = start + delta * step / (double) SUBDIVISIONS;
            if (isFreeAt.test(candidate)) {
                return candidate;
            }
        }
        return start;
    }
}
