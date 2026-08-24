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
}

