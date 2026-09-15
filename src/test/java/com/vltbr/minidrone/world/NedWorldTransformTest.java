package com.vltbr.minidrone.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    /** The arena is square, and its footprint is what the beacon advertises. */
    @Test
    void reportsTheArenaFootprintInMetres() {
        assertEquals(13, TrainingArenaLayout.sizeM());
        assertEquals(2 * TrainingArenaLayout.RADIUS + 1, TrainingArenaLayout.sizeM());
    }
}

