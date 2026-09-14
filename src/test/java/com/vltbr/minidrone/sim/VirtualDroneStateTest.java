package com.vltbr.minidrone.sim;

import com.vltbr.minidrone.mavlink.MavlinkProtocol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualDroneStateTest {
    @Test
    void requiresGuidedModeBeforeArmingAndTakeoff() {
        VirtualDroneState drone = new VirtualDroneState(54, 1, "minecraft_drone_01");

        assertFalse(drone.setArmed(true));
        assertTrue(drone.setMode(MavlinkProtocol.ARDUCOPTER_MODE_GUIDED));
        assertTrue(drone.setArmed(true));
        assertTrue(drone.takeoff(1.0));

        for (int tick = 0; tick < 30; tick++) {
            drone.tick();
        }

        VirtualDroneSnapshot airborne = drone.snapshot();
        assertTrue(airborne.armed());
        assertTrue(airborne.airborne());
        assertFalse(airborne.takingOff());
        assertEquals(-1.0, airborne.downM(), 0.0001);
    }

    @Test
    void landingReachesGroundAndDisarms() {
        VirtualDroneState drone = new VirtualDroneState(54, 1, "minecraft_drone_01");
        drone.setMode(MavlinkProtocol.ARDUCOPTER_MODE_GUIDED);
        drone.setArmed(true);
        drone.takeoff(0.5);
        for (int tick = 0; tick < 20; tick++) {
            drone.tick();
        }

        assertTrue(drone.land());
        for (int tick = 0; tick < 30; tick++) {
            drone.tick();
        }

        VirtualDroneSnapshot landed = drone.snapshot();
        assertFalse(landed.armed());
        assertFalse(landed.airborne());
        assertEquals(0.0, landed.downM(), 0.0001);
        assertEquals(9, landed.customMode());
    }

    @Test
    void followsPositionTargetAtBoundedHorizontalSpeed() {
        VirtualDroneState drone = new VirtualDroneState(54, 1, "minecraft_drone_01");
        drone.setMode(MavlinkProtocol.ARDUCOPTER_MODE_GUIDED);
        drone.setArmed(true);
        drone.takeoff(1.0);
        for (int tick = 0; tick < 30; tick++) {
            drone.tick();
        }

        assertTrue(drone.setPositionTarget(2.0, 1.0, -1.0));
        drone.tick();
        VirtualDroneSnapshot moving = drone.snapshot();
        assertTrue(Math.hypot(moving.northM(), moving.eastM()) > 0.0);
        assertEquals(1.4, Math.hypot(moving.velocityNorthMps(), moving.velocityEastMps()), 0.0001);
        assertEquals(0.0, moving.velocityDownMps(), 0.0001);

        for (int tick = 0; tick < 40; tick++) {
            drone.tick();
        }
        VirtualDroneSnapshot reached = drone.snapshot();
        assertEquals(2.0, reached.northM(), 0.0001);
        assertEquals(1.0, reached.eastM(), 0.0001);
        assertEquals(-1.0, reached.downM(), 0.0001);
        assertEquals(0.0, reached.velocityNorthMps(), 0.0001);
        assertEquals(0.0, reached.velocityEastMps(), 0.0001);
    }

    @Test
    void resetsLocalPositionOnlyAfterLandingAndDisarm() {
        VirtualDroneState drone = new VirtualDroneState(54, 1, "minecraft_drone_01");
        drone.setMode(MavlinkProtocol.ARDUCOPTER_MODE_GUIDED);
        drone.setArmed(true);
        drone.takeoff(0.5);
        for (int tick = 0; tick < 20; tick++) {
            drone.tick();
        }

        assertFalse(drone.resetLocalPosition());
        drone.land();
        for (int tick = 0; tick < 30; tick++) {
            drone.tick();
        }
        assertTrue(drone.resetLocalPosition());
        assertEquals(0.0, drone.snapshot().northM(), 0.0001);
        assertEquals(0.0, drone.snapshot().eastM(), 0.0001);
        assertEquals(0.0, drone.snapshot().downM(), 0.0001);
    }

    @Test
    void followsACommandedVelocityWhenNoPositionIsGiven() {
        VirtualDroneState drone = airborne();
        assertTrue(drone.setLocalSetpoint(velocityOnly(0.5, 0.0, 0.0)));

        for (int tick = 0; tick < 20; tick++) {
            drone.tick();
        }

        VirtualDroneSnapshot snapshot = drone.snapshot();
        assertEquals(0.5, snapshot.velocityNorthMps(), 0.0001);
        assertEquals(0.0, snapshot.velocityEastMps(), 0.0001);
        // One second at 0.5 m/s, minus the ramp-up under the acceleration limit.
        assertEquals(0.45, snapshot.northM(), 0.001);
    }

    @Test
    void rampsVelocityUnderTheAccelerationLimit() {
        VirtualDroneState drone = airborne();
        assertTrue(drone.setLocalSetpoint(velocityOnly(1.4, 0.0, 0.0)));

        drone.tick();
        // 2.0 m/s^2 over one 50 ms tick.
        assertEquals(0.1, drone.snapshot().velocityNorthMps(), 0.0001);

        drone.tick();
        assertEquals(0.2, drone.snapshot().velocityNorthMps(), 0.0001);
    }

    @Test
    void integratesACommandedAccelerationIntoTheVelocity() {
        VirtualDroneState drone = airborne();
        assertTrue(drone.setLocalSetpoint(new LocalSetpoint(
            accelerationAxis(1.0), LocalSetpoint.Axis.unset(), LocalSetpoint.Axis.unset(),
            false, 0.0, false, 0.0)));

        for (int tick = 0; tick < 3; tick++) {
            drone.tick();
        }

        // 1.0 m/s^2 for 150 ms.
        assertEquals(0.15, drone.snapshot().velocityNorthMps(), 0.0001);
    }

    @Test
    void slewsYawToTheCommandedHeadingAndReportsTheRate() {
        VirtualDroneState drone = airborne();
        assertTrue(drone.setLocalSetpoint(new LocalSetpoint(
            LocalSetpoint.Axis.unset(), LocalSetpoint.Axis.unset(), LocalSetpoint.Axis.unset(),
            true, 0.5, false, 0.0)));

        drone.tick();
        VirtualDroneSnapshot turning = drone.snapshot();
        assertEquals(1.5 * 0.05, turning.yawRad(), 0.0001);
        assertEquals(1.5, turning.yawRateRadS(), 0.0001);

        for (int tick = 0; tick < 10; tick++) {
            drone.tick();
        }
        VirtualDroneSnapshot settled = drone.snapshot();
        assertEquals(0.5, settled.yawRad(), 0.0001);
        assertEquals(0.0, settled.yawRateRadS(), 0.0001);
    }

    @Test
    void holdsTheAxesANewerFrameDoesNotCommand() {
        VirtualDroneState drone = airborne();
        assertTrue(drone.setPositionTarget(2.0, 0.0, -1.0));
        for (int tick = 0; tick < 5; tick++) {
            drone.tick();
        }
        VirtualDroneSnapshot tracking = drone.snapshot();
        assertTrue(tracking.northM() > 0.0);

        // A later frame that only commands yaw replaces the setpoint, so the
        // horizontal axes have no command any more and the vehicle holds them.
        assertTrue(drone.setLocalSetpoint(new LocalSetpoint(
            LocalSetpoint.Axis.unset(), LocalSetpoint.Axis.unset(), LocalSetpoint.Axis.unset(),
            true, 0.3, false, 0.0)));
        double northWhenYawWasCommanded = tracking.northM();
        for (int tick = 0; tick < 10; tick++) {
            drone.tick();
        }

        VirtualDroneSnapshot held = drone.snapshot();
        assertEquals(northWhenYawWasCommanded, held.northM(), 0.0001);
        assertEquals(0.0, held.velocityNorthMps(), 0.0001);
        assertEquals(0.3, held.yawRad(), 0.0001);
    }

    @Test
    void rejectsASetpointThatCommandsNoChannel() {
        VirtualDroneState drone = airborne();
        assertFalse(drone.setLocalSetpoint(new LocalSetpoint(
            LocalSetpoint.Axis.unset(), LocalSetpoint.Axis.unset(), LocalSetpoint.Axis.unset(),
            false, 0.0, false, 0.0)));
    }

    @Test
    void keepsTheVirtualPlantEnvelopeWhenVelocityAndAccelerationAreFedForward() {
        VirtualDroneState drone = airborne();
        assertTrue(drone.setLocalSetpoint(new LocalSetpoint(
            new LocalSetpoint.Axis(true, 60.0, true, 5.0, true, 5.0),
            new LocalSetpoint.Axis(true, 60.0, true, 5.0, true, 5.0),
            LocalSetpoint.Axis.unset(),
            false, 0.0, false, 0.0)));

        for (int tick = 0; tick < 20; tick++) {
            drone.tick();
            VirtualDroneSnapshot snapshot = drone.snapshot();
            assertTrue(
                Math.hypot(snapshot.velocityNorthMps(), snapshot.velocityEastMps()) <= 1.400001,
                "horizontal speed left the virtual plant envelope"
            );
        }
    }

    private static VirtualDroneState airborne() {
        VirtualDroneState drone = new VirtualDroneState(54, 1, "minecraft_drone_01");
        drone.setMode(MavlinkProtocol.ARDUCOPTER_MODE_GUIDED);
        drone.setArmed(true);
        drone.takeoff(1.0);
        for (int tick = 0; tick < 30; tick++) {
            drone.tick();
        }
        return drone;
    }

    private static LocalSetpoint velocityOnly(double north, double east, double down) {
        return new LocalSetpoint(
            velocityAxis(north), velocityAxis(east), velocityAxis(down),
            false, 0.0, false, 0.0);
    }

    private static LocalSetpoint.Axis velocityAxis(double velocity) {
        return new LocalSetpoint.Axis(false, 0.0, true, velocity, false, 0.0);
    }

    private static LocalSetpoint.Axis accelerationAxis(double acceleration) {
        return new LocalSetpoint.Axis(false, 0.0, false, 0.0, true, acceleration);
    }
}
