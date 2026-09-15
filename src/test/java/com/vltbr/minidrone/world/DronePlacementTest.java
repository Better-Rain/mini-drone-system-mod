package com.vltbr.minidrone.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Placing the drone by hand needs the inverse of {@link NedWorldTransform}: from a
 * world point back to a local NED offset. Getting a sign wrong here would put the
 * vehicle somewhere other than where the operator clicked, two blocks away and
 * mirrored, so the conventions are pinned against the forward transform.
 */
class DronePlacementTest {
    private static final double EPSILON = 0.0001;

    @Test
    void convertsAWorldPointToTheOffsetFromTheOrigin() {
        NedWorldTransform origin = new NedWorldTransform(10.0, 64.0, 20.0);

        // 2 m north of the origin (world z decreases), 3 m east (world x decreases),
        // 1.5 m up (world y increases).
        double[] ned = DronePlacement.nedOffsetFor(origin, 7.0, 62.5, 18.0);

        assertEquals(2.0, ned[0], EPSILON);
        assertEquals(3.0, ned[1], EPSILON);
        assertEquals(1.5, ned[2], EPSILON);
    }

    @Test
    void convertsAnOffsetBackToTheWorldPoint() {
        NedWorldTransform origin = new NedWorldTransform(10.0, 64.0, 20.0);

        double[] world = DronePlacement.worldPositionFor(origin, 2.0, 3.0, 1.5);

        assertEquals(7.0, world[0], EPSILON);
        assertEquals(62.5, world[1], EPSILON);
        assertEquals(18.0, world[2], EPSILON);
    }

    @Test
    void roundTripsThroughBothDirections() {
        NedWorldTransform origin = new NedWorldTransform(-0.5, -59.0, 17.5);

        double[] ned = DronePlacement.nedOffsetFor(origin, 3.25, -58.0, 12.75);
        double[] world = DronePlacement.worldPositionFor(origin, ned[0], ned[1], ned[2]);

        assertEquals(3.25, world[0], EPSILON);
        assertEquals(-58.0, world[1], EPSILON);
        assertEquals(12.75, world[2], EPSILON);
    }

    /**
     * The offset must describe the same point the flight-controller telemetry does:
     * the forward transform (NED to world) and this inverse are the two halves of one
     * mapping, and a mismatch shows up as a drone that lands next to where it was put.
     */
    @Test
    void agreesWithTheForwardTransformUsedForTelemetry() {
        NedWorldTransform origin = new NedWorldTransform(-0.5, -59.0, 17.5);
        double north = -1.25;
        double east = 2.5;
        double down = -0.75;

        WorldPose pose = origin.toWorldPose(north, east, down, 0.0, 0.0, 0.0);
        double[] back = DronePlacement.nedOffsetFor(origin, pose.x(), pose.y(), pose.z());

        assertEquals(north, back[0], EPSILON);
        assertEquals(east, back[1], EPSILON);
        assertEquals(down, back[2], EPSILON);
    }
}
