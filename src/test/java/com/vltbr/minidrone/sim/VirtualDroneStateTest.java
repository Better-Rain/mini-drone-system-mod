package com.vltbr.minidrone.sim;

import com.vltbr.minidrone.mavlink.MavlinkMessages;
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

    /**
     * A forced disarm in flight is what an emergency stop does
     * (MAV_CMD_COMPONENT_ARM_DISARM with param1 = 0). The virtual plant used to
     * keep such a vehicle hovering forever, and the backend refuses to release an
     * emergency stop until the flight controller reports ON_GROUND - so the
     * release never became possible. The vehicle has to come down on its own.
     */
    @Test
    void fallsToTheGroundWhenDisarmedInFlight() {
        VirtualDroneState drone = new VirtualDroneState(54, 1, "minecraft_drone_01");
        drone.setMode(MavlinkProtocol.ARDUCOPTER_MODE_GUIDED);
        drone.setArmed(true);
        drone.takeoff(3.0);
        for (int tick = 0; tick < 100; tick++) {
            drone.tick();
        }
        assertEquals(-3.0, drone.snapshot().downM(), 0.0001);
        assertTrue(drone.snapshot().airborne());

        assertTrue(drone.setArmed(false));
        assertFalse(drone.snapshot().armed());

        int ticksToGround = 0;
        while (drone.snapshot().airborne() && ticksToGround < 200) {
            drone.tick();
            ticksToGround++;
        }

        assertTrue(ticksToGround < 200, "the disarmed vehicle never reached the ground");
        // The reported landed state flips on the same centimetre-scale epsilon
        // LANDING uses, so the plant settles onto the ground a couple of ticks
        // later; that window is exactly why the release path waits for a fresh
        // report instead of trusting the first one.
        for (int tick = 0; tick < 20; tick++) {
            drone.tick();
        }
        VirtualDroneSnapshot landed = drone.snapshot();
        assertFalse(landed.airborne());
        assertEquals(0.0, landed.downM(), 0.0001);
        assertEquals(0.0, landed.velocityDownMps(), 0.0001);
        // The backend reads this byte for its ON_GROUND precondition.
        assertEquals(1, MavlinkMessages.extendedSysState(landed)[1]);

        // ... and the operator can fly again after that.
        assertTrue(drone.setArmed(true));
        assertTrue(drone.takeoff(1.0));
        for (int tick = 0; tick < 40; tick++) {
            drone.tick();
        }
        assertTrue(drone.snapshot().airborne());
    }

    /**
     * Hand placement: the operator carries the vehicle to a spot, so its local NED
     * position becomes an offset rather than zero. On the ground it just rests there;
     * placed in the air it is an unpowered vehicle, which falls - the same rule a
     * forced disarm follows, so the two cannot disagree.
     */
    @Test
    void placingByHandMovesTheLocalPosition() {
        VirtualDroneState drone = new VirtualDroneState(54, 1, "minecraft_drone_01");
        drone.setMode(MavlinkProtocol.ARDUCOPTER_MODE_GUIDED);

        assertTrue(drone.setLocalPosition(2.0, -3.5, 0.0));
        VirtualDroneSnapshot placed = drone.snapshot();
        assertEquals(2.0, placed.northM(), 0.0001);
        assertEquals(-3.5, placed.eastM(), 0.0001);
        assertFalse(placed.airborne());
        assertEquals(1, MavlinkMessages.extendedSysState(placed)[1]);

        // Placed in the air, it comes down on its own.
        assertTrue(drone.setLocalPosition(1.0, 1.0, -4.0));
        assertTrue(drone.snapshot().airborne());
        for (int tick = 0; tick < 200 && drone.snapshot().airborne(); tick++) {
            drone.tick();
        }
        assertFalse(drone.snapshot().airborne(), "a hand-placed drone in the air never landed");
        assertEquals(1.0, drone.snapshot().northM(), 0.0001);
        assertEquals(1.0, drone.snapshot().eastM(), 0.0001);
    }

    /** Carrying an armed vehicle around would be a surprise, so it is refused. */
    @Test
    void refusesToPlaceAnArmedDrone() {
        VirtualDroneState drone = new VirtualDroneState(54, 1, "minecraft_drone_01");
        drone.setMode(MavlinkProtocol.ARDUCOPTER_MODE_GUIDED);
        drone.setArmed(true);

        assertFalse(drone.setLocalPosition(1.0, 1.0, -1.0));
    }

    /**
     * The world has the last word: whatever position it resolved is the one the
     * telemetry reports, and a blocked axis must not keep claiming speed.
     */
    /**
     * The bug the operator found: a disarmed vehicle that nothing is holding up must
     * fall, however it got there. The support flag comes from the world, so a vehicle
     * that loses its ground - or whose origin moved out from under it - falls too,
     * instead of hanging in the air because no disarm event ever arrived.
     */
    @Test
    void aDisarmedVehicleThatNothingSupportsFalls() {
        VirtualDroneState drone = new VirtualDroneState(54, 1, "minecraft_drone_01");
        drone.setMode(MavlinkProtocol.ARDUCOPTER_MODE_GUIDED);
        assertFalse(drone.snapshot().airborne());

        // The world reports the vehicle five metres up with nothing under it.
        drone.adoptExternalPosition(0.0, 0.0, -5.0, false, false, false);
        drone.tick();

        assertTrue(drone.snapshot().airborne(), "it should be falling, not hanging there");
        for (int tick = 0; tick < 200 && drone.snapshot().airborne(); tick++) {
            // The world holds it up once it is back on the ground.
            drone.adoptExternalPosition(
                drone.snapshot().northM(), drone.snapshot().eastM(),
                Math.max(drone.snapshot().downM(), -0.0), false, true, true);
            drone.tick();
        }
        assertFalse(drone.snapshot().airborne());
        assertEquals(1, MavlinkMessages.extendedSysState(drone.snapshot())[1]);
    }

    /** An impact destroys the flight: disarm, fall, and latch until reset. */
    @Test
    void anImpactLatchesTheVehicleUntilItIsReset() {
        VirtualDroneState drone = new VirtualDroneState(54, 1, "minecraft_drone_01");
        drone.setMode(MavlinkProtocol.ARDUCOPTER_MODE_GUIDED);
        drone.setArmed(true);
        drone.takeoff(2.0);
        for (int tick = 0; tick < 60; tick++) {
            drone.tick();
        }
        assertTrue(drone.snapshot().armed());
        assertFalse(drone.safetyLatched());

        drone.crash();

        assertFalse(drone.snapshot().armed(), "a crashed vehicle is not armed");
        assertTrue(drone.safetyLatched(), "the crash has to be reported to the monitoring side");
        assertTrue(drone.snapshot().airborne(), "a crashed vehicle falls");

        drone.clearSafetyLatch();
        assertFalse(drone.safetyLatched());
    }

    @Test
    void adoptsThePositionTheWorldAllowed() {
        VirtualDroneState drone = new VirtualDroneState(54, 1, "minecraft_drone_01");
        drone.setMode(MavlinkProtocol.ARDUCOPTER_MODE_GUIDED);
        drone.setArmed(true);
        drone.takeoff(2.0);
        for (int tick = 0; tick < 60; tick++) {
            drone.tick();
        }
        assertTrue(drone.snapshot().airborne());

        // A wall stopped it 30 cm into the metre it asked for, one metre up.
        drone.adoptExternalPosition(0.3, 0.0, -1.0, true, false, false);

        VirtualDroneSnapshot pressed = drone.snapshot();
        assertEquals(0.3, pressed.northM(), 0.0001);
        assertEquals(-1.0, pressed.downM(), 0.0001);
        assertEquals(0.0, pressed.velocityNorthMps(), 0.0001);
        assertEquals(0.0, pressed.velocityEastMps(), 0.0001);
        assertTrue(pressed.airborne());
    }

    /** Landing on a block finishes the descent instead of hovering above it. */
    @Test
    void finishingOnSolidGroundEndsTheFall() {
        VirtualDroneState drone = new VirtualDroneState(54, 1, "minecraft_drone_01");
        drone.setMode(MavlinkProtocol.ARDUCOPTER_MODE_GUIDED);
        assertTrue(drone.setLocalPosition(0.0, 0.0, -2.0));
        assertTrue(drone.snapshot().airborne());

        // The world reports the vehicle resting on the floor.
        drone.adoptExternalPosition(0.0, 0.0, -0.0, false, true, true);

        VirtualDroneSnapshot landed = drone.snapshot();
        assertFalse(landed.airborne());
        assertEquals(0.0, landed.downM(), 0.0001);
        assertEquals(0.0, landed.velocityDownMps(), 0.0001);
        assertEquals(1, MavlinkMessages.extendedSysState(landed)[1]);
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
