package com.vltbr.minidrone.mavlink;

import com.vltbr.minidrone.sim.LocalSetpoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The adapter between the wire frame and the flight model. A channel that is
 * mapped to the wrong axis would look like "the virtual vehicle ignores the
 * command", which is exactly what this guards against.
 */
class VirtualAutopilotSetpointTest {
    @Test
    void mapsEveryChannelOfAFullPvaFrameOntoItsOwnAxis() {
        LocalSetpoint setpoint = VirtualAutopilot.toLocalSetpoint(new MavlinkMessages.PositionTargetLocalNed(
            0L,
            1.0f, 2.0f, -3.0f,
            0.11f, 0.22f, -0.33f,
            0.44f, 0.55f, -0.66f,
            0.77f, -0.88f,
            0x0000,
            54, 1, MavlinkProtocol.MAV_FRAME_LOCAL_NED
        ));

        assertTrue(setpoint.north().positionSet());
        assertEquals(1.0, setpoint.north().position(), 1e-6);
        assertEquals(0.11, setpoint.north().velocity(), 1e-6);
        assertEquals(0.44, setpoint.north().acceleration(), 1e-6);

        assertTrue(setpoint.east().positionSet());
        assertEquals(2.0, setpoint.east().position(), 1e-6);
        assertEquals(0.22, setpoint.east().velocity(), 1e-6);
        assertEquals(0.55, setpoint.east().acceleration(), 1e-6);

        assertTrue(setpoint.down().positionSet());
        assertEquals(-3.0, setpoint.down().position(), 1e-6);
        assertEquals(-0.33, setpoint.down().velocity(), 1e-6);
        assertEquals(-0.66, setpoint.down().acceleration(), 1e-6);

        assertTrue(setpoint.yawSet());
        assertEquals(0.77, setpoint.yawRad(), 1e-6);
        assertTrue(setpoint.yawRateSet());
        assertEquals(-0.88, setpoint.yawRateRadS(), 1e-6);
        assertTrue(setpoint.anyCommanded());
    }

    @Test
    void dropsTheChannelsTheSenderIgnored() {
        LocalSetpoint setpoint = VirtualAutopilot.toLocalSetpoint(new MavlinkMessages.PositionTargetLocalNed(
            0L,
            5.0f, 6.0f, -7.0f,
            0.11f, 0.22f, 0.33f,
            0.44f, 0.55f, 0.66f,
            0.77f, 0.88f,
            0x09FF,
            54, 1, MavlinkProtocol.MAV_FRAME_LOCAL_NED
        ));

        assertFalse(setpoint.north().positionSet());
        assertFalse(setpoint.north().velocitySet());
        assertFalse(setpoint.north().accelerationSet());
        assertFalse(setpoint.east().anyCommanded());
        assertFalse(setpoint.down().anyCommanded());
        assertTrue(setpoint.yawSet());
        assertFalse(setpoint.yawRateSet());
    }

    @Test
    void keepsPositionZWhileTrackingHorizontalVelocity() {
        LocalSetpoint setpoint = VirtualAutopilot.toLocalSetpoint(new MavlinkMessages.PositionTargetLocalNed(
            0L,
            1.0f, 2.0f, -3.0f,
            0.11f, 0.22f, 0.33f,
            0.0f, 0.0f, 0.0f,
            0.0f, 0.0f,
            0x0DC3,
            54, 1, MavlinkProtocol.MAV_FRAME_LOCAL_NED
        ));

        assertFalse(setpoint.north().positionSet());
        assertTrue(setpoint.north().velocitySet());
        assertEquals(0.11, setpoint.north().velocity(), 1e-6);
        assertTrue(setpoint.east().velocitySet());
        // 0x0DC3 keeps the vertical position while commanding velocity on all
        // three axes, so the height is held explicitly rather than by inertia.
        assertTrue(setpoint.down().positionSet());
        assertEquals(-3.0, setpoint.down().position(), 1e-6);
        assertTrue(setpoint.down().velocitySet());
        assertEquals(0.33, setpoint.down().velocity(), 1e-6);
        assertFalse(setpoint.yawSet());
    }
}
