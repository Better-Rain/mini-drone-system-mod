package com.vltbr.minidrone.world;

/**
 * Converts between a world point and the local NED offset of the virtual origin.
 *
 * <p>It is the same conversion {@link NedWorldTransform} does, written out for the
 * one direction the transform does not cover: going from a world point back to NED.
 * The mapping is
 *
 * <pre>
 * world x = origin x - east
 * world y = origin y - down
 * world z = origin z - north
 * </pre>
 *
 * <p>Placing the drone by hand needs it: the operator points at a block, the drone
 * goes there, and the flight controller's local position has to become the offset
 * from the origin rather than the origin itself. Kept as plain arithmetic so the
 * sign conventions are unit-tested instead of being checked by flying into a wall.
 */
public final class DronePlacement {
    private DronePlacement() {
    }

    /** The NED offset {@code [north, east, down]} of a world point from the origin. */
    public static double[] nedOffsetFor(
        NedWorldTransform origin, double worldX, double worldY, double worldZ
    ) {
        return new double[] {
            origin.originZ() - worldZ,
            origin.originX() - worldX,
            origin.originY() - worldY
        };
    }

    /** The world point a NED offset lands on, given the origin. */
    public static double[] worldPositionFor(
        NedWorldTransform origin, double north, double east, double down
    ) {
        return new double[] {
            origin.originX() - east,
            origin.originY() - down,
            origin.originZ() - north
        };
    }
}
