package com.vltbr.minidrone.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NedWorldTransformTest {
    private static final double EPSILON = 0.0001;

    @Test
    void mapsLocalNedToMinecraftCoordinates() {
        NedWorldTransform transform = new NedWorldTransform(10.0, 64.0, 20.0);

        WorldPose pose = transform.toWorldPose(2.0, 3.0, -4.0, 0.0, 0.0, 0.0);

        assertEquals(7.0, pose.x(), EPSILON);
        assertEquals(68.0, pose.y(), EPSILON);
        assertEquals(18.0, pose.z(), EPSILON);
    }

    @Test
    void mapsNedHeadingToMinecraftYaw() {
        NedWorldTransform transform = new NedWorldTransform(0.0, 0.0, 0.0);

        assertEquals(-180.0F, transform.toWorldPose(0, 0, 0, 0, 0, 0).yawDegrees(), EPSILON);
        assertEquals(90.0F, transform.toWorldPose(0, 0, 0, 0, 0, Math.PI / 2).yawDegrees(), EPSILON);
        assertEquals(0.0F, transform.toWorldPose(0, 0, 0, 0, 0, Math.PI).yawDegrees(), EPSILON);
        assertEquals(-90.0F, transform.toWorldPose(0, 0, 0, 0, 0, -Math.PI / 2).yawDegrees(), EPSILON);
    }

    /**
     * The rendered attitude must agree with the monitoring view.
     *
     * <p>The scene is the documented mirror of NED (world x = -east, z = -north), and in that
     * frame the heading comes back as {@code 180 - yaw} while pitch and roll keep their signs.
     * Negating the pitch with the yaw made a vehicle accelerating forward render nose-up next
     * to a monitoring view showing it nose-down: the operator saw the two as opposite.
     */
    @Test
    void keepsTheAttitudeSignsTheMonitoringViewUses() {
        NedWorldTransform transform = new NedWorldTransform(0.0, 0.0, 0.0);

        // Forward flight: the nose is down, i.e. negative pitch in NED.
        WorldPose forward = transform.toWorldPose(0.0, 0.0, 0.0, 0.0, -0.3, 0.0);
        assertTrue(forward.pitchDegrees() < 0.0f,
            "a nose-down attitude has to stay nose-down, got " + forward.pitchDegrees());
        assertEquals(-0.3, Math.toRadians(forward.pitchDegrees()), EPSILON);

        // Climbing: nose up, positive in NED.
        WorldPose climbing = transform.toWorldPose(0.0, 0.0, 0.0, 0.0, 0.3, 0.0);
        assertEquals(0.3, Math.toRadians(climbing.pitchDegrees()), EPSILON);

        // A right bank keeps its own sign as well.
        WorldPose banked = transform.toWorldPose(0.0, 0.0, 0.0, 0.25, 0.0, 0.0);
        assertEquals(0.25, Math.toRadians(banked.rollDegrees()), EPSILON);
    }

    /**
     * With an arena the origin has to be the arena centre, because the backend
     * draws its field centred on the local NED origin: the drone's position
     * relative to the field is only meaningful when those two agree.
     *
     * <p>The pad layer sits at {@code topY}, so a drone resting on it has its NED
     * down = 0 one block higher, and the block centres put the origin half a block
     * inside the arena's X/Z bounds.
     */
    @Test
    void placesTheArenaOriginOnTheCentreOfTheLandingPad() {
        NedWorldTransform transform = ArenaOrigin.centeredTransform(-1, -60, 17);

        assertEquals(-0.5, transform.originX(), EPSILON);
        assertEquals(-59.0, transform.originY(), EPSILON);
        assertEquals(17.5, transform.originZ(), EPSILON);

        WorldPose parked = transform.toWorldPose(0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        assertEquals(-0.5, parked.x(), EPSILON);
        assertEquals(-59.0, parked.y(), EPSILON);
        assertEquals(17.5, parked.z(), EPSILON);
    }

    /** The arena footprint is what the beacon advertises; the default is 13 m square. */
    @Test
    void reportsTheArenaFootprintInMetres() {
        TrainingArenaLayout square = TrainingArenaLayout.centered(0, 0, 0);
        assertEquals(13, square.widthM());
        assertEquals(13, square.depthM());
        assertEquals(2 * TrainingArenaLayout.RADIUS + 1, square.widthM());

        // The two axes are independent, so a longer runway along one of them is a
        // layout change and not a protocol change: field_size_m is [width, depth].
        TrainingArenaLayout runway = TrainingArenaLayout.centered(-1, -60, 17, 10, 4);
        assertEquals(21, runway.widthM());
        assertEquals(9, runway.depthM());
        assertEquals(10, runway.radiusX());
        assertEquals(4, runway.radiusZ());
        // Surface blocks plus one marker above each of the four corners.
        assertEquals(21 * 9 + 4, runway.blocks().size());
        assertEquals(4, runway.blocks().stream()
            .filter(block -> block.kind() == TrainingArenaLayout.Kind.CORNER_MARKER)
            .count());
        for (TrainingArenaLayout.RelativeBlock block : runway.blocks()) {
            assertTrue(Math.abs(block.dx()) <= 10 && Math.abs(block.dz()) <= 4);
        }
    }

    /** A rectangular arena still centres its pad, which is where the origin goes. */
    @Test
    void keepsTheLandingPadAtTheCentreOfARectangularArena() {
        TrainingArenaLayout runway = TrainingArenaLayout.centered(0, 64, 0, 10, 4);

        TrainingArenaLayout.RelativeBlock centre = runway.blocks().stream()
            .filter(block -> block.dx() == 0 && block.dz() == 0)
            .findFirst()
            .orElseThrow();

        assertEquals(TrainingArenaLayout.Kind.LANDING_CENTER, centre.kind());
        NedWorldTransform origin = ArenaOrigin.centeredTransform(0, 64, 0);
        assertEquals(0.5, origin.originX(), EPSILON);
        assertEquals(65.0, origin.originY(), EPSILON);
        assertEquals(0.5, origin.originZ(), EPSILON);
    }
}

