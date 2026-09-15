package com.vltbr.minidrone.sim;

import com.vltbr.minidrone.mavlink.MavlinkMessages;
import com.vltbr.minidrone.mavlink.MavlinkProtocol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualDroneStateTest {
    /** The plant's control period, mirrored so the expectations can be derived from it. */
    private static final double TICK_SECONDS = 0.05;

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

    /**
     * A position target is tracked at the speed the flight controller may ask for, and
     * the airframe cannot jump to that speed: thrust takes time to build, so the first
     * tick buys one tick of the first-order response, the approach is monotone while
     * the vehicle is still on its way, and the horizontal bound holds on every tick of
     * it - that bound is the envelope this plant exists to keep.
     */
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
        double maxSpeed = VehicleModel.DEFAULTS.maxHorizontalSpeedMps();
        drone.tick();
        VirtualDroneSnapshot moving = drone.snapshot();
        double firstSpeed = Math.hypot(moving.velocityNorthMps(), moving.velocityEastMps());
        assertTrue(Math.hypot(moving.northM(), moving.eastM()) > 0.0, "the target did not move it");
        assertTrue(firstSpeed > 0.0, "the vehicle did not start moving");
        // One tick of the response to the 1.4 m/s the controller asked for, and not the
        // whole of it: (target - current) * tick / response, with current at rest.
        assertTrue(
            firstSpeed <= maxSpeed * TICK_SECONDS / responseSeconds(),
            "the airframe reached the commanded speed in a single tick");
        assertEquals(0.0, moving.velocityDownMps(), 0.0001);

        double distanceBefore = Math.hypot(2.0 - moving.northM(), 1.0 - moving.eastM());
        boolean settled = false;
        for (int tick = 1; tick <= 80 && !settled; tick++) {
            drone.tick();
            VirtualDroneSnapshot snapshot = drone.snapshot();
            double speed = Math.hypot(snapshot.velocityNorthMps(), snapshot.velocityEastMps());
            assertTrue(
                speed <= maxSpeed + 1.0e-9,
                "horizontal speed left the virtual plant envelope on tick " + tick + ": " + speed);
            double distance = Math.hypot(2.0 - snapshot.northM(), 1.0 - snapshot.eastM());
            if (distance > 0.1) {
                assertTrue(distance < distanceBefore, "the vehicle wandered on its way to the target");
            }
            distanceBefore = distance;
            // Settled is "on the target and slow enough to stay on it": the plant calls
            // a centimetre arrival, so a speed inside that per tick is a stop.
            settled = distance <= 0.01 && speed <= 0.01;
        }
        assertTrue(settled, "the vehicle never settled on its target");

        VirtualDroneSnapshot reached = drone.snapshot();
        assertEquals(2.0, reached.northM(), 0.0001);
        assertEquals(1.0, reached.eastM(), 0.0001);
        assertEquals(-1.0, reached.downM(), 0.0001);

        // Nothing is driving it any more, so it stays there instead of creeping off.
        for (int tick = 0; tick < 10; tick++) {
            drone.tick();
        }
        assertEquals(2.0, drone.snapshot().northM(), 0.0001);
        assertEquals(1.0, drone.snapshot().eastM(), 0.0001);
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

    /**
     * A commanded velocity with no position to track is approached through the
     * airframe's response rather than applied in one tick: the speed builds up over the
     * response time, it never passes the command, and the position is exactly the
     * integral of the speed the telemetry reported - not one second at the full
     * command, which is what an instant plant would have delivered.
     */
    @Test
    void followsACommandedVelocityWhenNoPositionIsGiven() {
        VirtualDroneState drone = airborne();
        double commanded = 0.5;
        assertTrue(drone.setLocalSetpoint(velocityOnly(commanded, 0.0, 0.0)));

        double previous = 0.0;
        double travelled = 0.0;
        double perTickLimit = thrustAccelerationMps2() * TICK_SECONDS;
        for (int tick = 1; tick <= 40; tick++) {
            drone.tick();
            VirtualDroneSnapshot snapshot = drone.snapshot();
            double speed = snapshot.velocityNorthMps();
            assertTrue(speed >= previous, "the response has to approach the command monotonically");
            assertTrue(speed <= commanded, "the response must not overshoot the command");
            assertTrue(speed - previous <= perTickLimit, "a tick beat the airframe acceleration limit");
            previous = speed;
            travelled += speed * TICK_SECONDS;
        }

        VirtualDroneSnapshot flying = drone.snapshot();
        // Two seconds is well past the response time, so the command is reached - inside
        // one percent of it, because a first-order response gets close and then closer.
        assertEquals(commanded, flying.velocityNorthMps(), commanded * 0.01);
        assertEquals(0.0, flying.velocityEastMps(), 0.0001);
        // The vehicle really moved, and by exactly what the ramp delivered.
        assertEquals(travelled, flying.northM(), 1.0e-9);
        assertTrue(flying.northM() > 0.5, "the velocity channel did not move the vehicle");
        // An instant plant would have covered 0.5 m/s for the whole run.
        assertTrue(flying.northM() < commanded * 40 * TICK_SECONDS, "the ramp was skipped");
    }

    /**
     * The same command at the top of the envelope. The ramp is the airframe's answer to
     * a step demand: one tick buys one tick of the first-order response, no tick beats
     * the acceleration the tilted thrust can produce, and two seconds in the vehicle is
     * still climbing towards the command instead of sitting on it.
     */
    @Test
    void rampsVelocityUnderTheAccelerationLimit() {
        VirtualDroneState drone = airborne();
        double topSpeed = VehicleModel.DEFAULTS.maxHorizontalSpeedMps();
        assertTrue(drone.setLocalSetpoint(velocityOnly(topSpeed, 0.0, 0.0)));

        drone.tick();
        double firstSpeed = drone.snapshot().velocityNorthMps();
        assertTrue(firstSpeed > 0.0, "the command was dropped");
        assertTrue(
            firstSpeed <= topSpeed * TICK_SECONDS / responseSeconds(),
            "one tick closed the whole gap to the command");

        double previous = firstSpeed;
        double perTickLimit = thrustAccelerationMps2() * TICK_SECONDS;
        for (int tick = 2; tick <= 40; tick++) {
            drone.tick();
            double speed = drone.snapshot().velocityNorthMps();
            assertTrue(speed > previous, "the ramp stopped closing the gap");
            assertTrue(speed <= topSpeed, "the ramp passed the command");
            assertTrue(speed - previous <= perTickLimit, "a tick beat the airframe acceleration limit");
            previous = speed;
        }

        // Two seconds of ramp against a 1.4 m/s command: real progress, and clearly not
        // the instantaneous kinematics this plant used to have.
        assertTrue(previous < topSpeed, "a step to top speed cannot arrive in two seconds");
        assertTrue(previous > topSpeed * 0.6, "the ramp is slower than the airframe response");
    }

    /**
     * The acceleration channel is not dropped: it keeps adding speed, so the reported
     * velocity climbs tick after tick. The airframe lags the request, so 150 ms of
     * 1.0 m/s^2 has not delivered the whole 0.15 m/s yet - the velocity builds up
     * instead of appearing.
     */
    @Test
    void integratesACommandedAccelerationIntoTheVelocity() {
        VirtualDroneState drone = airborne();
        double acceleration = 1.0;
        assertTrue(drone.setLocalSetpoint(new LocalSetpoint(
            accelerationAxis(acceleration), LocalSetpoint.Axis.unset(), LocalSetpoint.Axis.unset(),
            false, 0.0, false, 0.0)));

        double previous = 0.0;
        for (int tick = 1; tick <= 3; tick++) {
            drone.tick();
            double speed = drone.snapshot().velocityNorthMps();
            assertTrue(speed > previous, "the acceleration channel stopped integrating");
            previous = speed;
        }
        // 1.0 m/s^2 for 150 ms is 0.15 m/s of velocity, and the airframe is still
        // catching up to that ramp.
        assertTrue(
            previous < acceleration * 3 * TICK_SECONDS,
            "the commanded acceleration was applied instantly");

        // It keeps integrating for as long as the command stands.
        for (int tick = 0; tick < 40; tick++) {
            drone.tick();
        }
        VirtualDroneSnapshot flying = drone.snapshot();
        assertTrue(
            flying.velocityNorthMps() > previous,
            "the acceleration channel stopped driving the vehicle after three ticks");
        assertTrue(flying.northM() > 0.0, "the velocity the channel produced did not move the vehicle");
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

    /**
     * A later frame that only commands yaw replaces the setpoint, so the horizontal
     * axes have no command any more. What the vehicle does then is coast: nothing is
     * asking for speed, so the airframe takes the remaining speed off over its response
     * time. It does not keep flying, and it does not stop dead either - it comes to
     * rest within one response time's worth of travel of where the command left it.
     */
    @Test
    void holdsTheAxesANewerFrameDoesNotCommand() {
        VirtualDroneState drone = airborne();
        assertTrue(drone.setPositionTarget(2.0, 0.0, -1.0));
        for (int tick = 0; tick < 5; tick++) {
            drone.tick();
        }
        VirtualDroneSnapshot tracking = drone.snapshot();
        assertTrue(tracking.northM() > 0.0);
        assertTrue(tracking.velocityNorthMps() > 0.0, "the north axis was not being tracked");
        double northWhenYawWasCommanded = tracking.northM();
        double speedWhenYawWasCommanded = tracking.velocityNorthMps();

        assertTrue(drone.setLocalSetpoint(new LocalSetpoint(
            LocalSetpoint.Axis.unset(), LocalSetpoint.Axis.unset(), LocalSetpoint.Axis.unset(),
            true, 0.3, false, 0.0)));

        // The coast: every tick is the previous one minus one tick's share of the
        // response, so the speed falls monotonically to a stop.
        double previous = speedWhenYawWasCommanded;
        for (int tick = 1; tick <= 40; tick++) {
            drone.tick();
            double speed = drone.snapshot().velocityNorthMps();
            assertTrue(speed < previous, "the axis a newer frame did not command kept being driven");
            previous = speed;
        }

        VirtualDroneSnapshot held = drone.snapshot();
        assertEquals(0.0, held.velocityNorthMps(), 0.001);
        assertTrue(
            held.northM() - northWhenYawWasCommanded <= speedWhenYawWasCommanded * responseSeconds(),
            "the vehicle coasted further than its response time allows");
        assertEquals(0.3, held.yawRad(), 0.0001);

        // Nothing drives it any more, so it stays where it came to rest.
        double restingNorth = held.northM();
        for (int tick = 0; tick < 10; tick++) {
            drone.tick();
        }
        assertEquals(restingNorth, drone.snapshot().northM(), 0.0001);
    }

    @Test
    void rejectsASetpointThatCommandsNoChannel() {
        VirtualDroneState drone = airborne();
        assertFalse(drone.setLocalSetpoint(new LocalSetpoint(
            LocalSetpoint.Axis.unset(), LocalSetpoint.Axis.unset(), LocalSetpoint.Axis.unset(),
            false, 0.0, false, 0.0)));
    }

    /**
     * The plant envelope is a hard bound, and it has to hold against the worst a PVA
     * frame can ask for: a velocity channel is feed-forward onto the tracked speed and
     * an acceleration channel is one tick ahead of it, so this frame demands more than
     * five metres per second on each horizontal axis. The airframe response may lag and
     * may cap what it can reach, but nothing a sender asks for may carry the vehicle
     * past the envelope - the bound is checked on every tick, not just at the end.
     */
    @Test
    void keepsTheVirtualPlantEnvelopeWhenVelocityAndAccelerationAreFedForward() {
        VirtualDroneState drone = airborne();
        assertTrue(drone.setLocalSetpoint(new LocalSetpoint(
            new LocalSetpoint.Axis(true, 60.0, true, 5.0, true, 5.0),
            new LocalSetpoint.Axis(true, 60.0, true, 5.0, true, 5.0),
            LocalSetpoint.Axis.unset(),
            false, 0.0, false, 0.0)));

        double maxSpeed = VehicleModel.DEFAULTS.maxHorizontalSpeedMps();
        for (int tick = 1; tick <= 20; tick++) {
            drone.tick();
            VirtualDroneSnapshot snapshot = drone.snapshot();
            double speed = Math.hypot(snapshot.velocityNorthMps(), snapshot.velocityEastMps());
            assertTrue(
                speed <= maxSpeed + 1.0e-9,
                "horizontal speed left the virtual plant envelope on tick " + tick
                    + ": " + speed + " m/s, limit " + maxSpeed);
        }
    }

    /** Seconds the airframe takes to answer a change of demand, from the vehicle model. */
    private static double responseSeconds() {
        return VehicleModel.DEFAULTS.motorTimeConstantS()
            + VehicleModel.DEFAULTS.attitudeTimeConstantS();
    }

    /**
     * The horizontal acceleration the tilted thrust can produce, in m/s^2.
     *
     * <p>Only the part of the thrust that can be pointed sideways accelerates the
     * vehicle, so no tick of the response may beat this.
     */
    private static double thrustAccelerationMps2() {
        return VehicleModel.DEFAULTS.maxThrustN() * Math.sin(VehicleModel.DEFAULTS.maxTiltRad())
            / VehicleModel.DEFAULTS.massKg();
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
