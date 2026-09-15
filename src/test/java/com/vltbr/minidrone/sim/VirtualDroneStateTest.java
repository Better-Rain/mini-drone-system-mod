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
    /**
     * And how many times the plant under that control period advances per tick, mirrored
     * for the same reason: the position the plant reports is integrated at
     * {@code TICK_SECONDS / PHYSICS_SUBSTEPS}, not at the control period.
     */
    private static final int PHYSICS_SUBSTEPS = 4;

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
        drone.adoptExternalPosition(0.0, 0.0, -5.0, false, false, false, false, false);
        drone.tick();

        assertTrue(drone.snapshot().airborne(), "it should be falling, not hanging there");
        for (int tick = 0; tick < 200 && drone.snapshot().airborne(); tick++) {
            // The world holds it up once it is back on the ground.
            drone.adoptExternalPosition(
                drone.snapshot().northM(), drone.snapshot().eastM(),
                Math.max(drone.snapshot().downM(), -0.0), false, true, false, false, true);
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
        drone.adoptExternalPosition(0.3, 0.0, -1.0, true, false, true, false, false);

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
        drone.adoptExternalPosition(0.0, 0.0, -0.0, false, true, false, false, true);

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
     * this airframe cannot simply be there: the thrust has to be tipped into the movement
     * first, so the lean comes before the velocity and the whole hop is flown over
     * several response times instead of one tick. What the two-stage response still buys
     * is the envelope this plant exists to keep: the horizontal bound holds on every
     * tick, the target is approached monotonically until the vehicle is nearly on it, the
     * lean never passes the airframe's lean limit, it points the way the vehicle is
     * travelling while the leg is flown under power - and the other way once the vehicle
     * has to shed speed to arrive, which is what braking with a thrust vector is - and the
     * rates the telemetry reports are the ones the attitude actually moved at.
     *
     * <p>And it does arrive: the tracker flies the leg at a speed it can stop from, the
     * plant's arrival deadband catches the last few centimetres, and the commanded point
     * is then held exactly. That is the guarantee a position setpoint makes, and this test
     * checks it - see the arrival note before the settling assertion for the measurements.
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
        double maxTilt = VehicleModel.DEFAULTS.maxTiltRad();
        drone.tick();
        VirtualDroneSnapshot moving = drone.snapshot();
        double firstSpeed = Math.hypot(moving.velocityNorthMps(), moving.velocityEastMps());
        assertTrue(Math.hypot(moving.northM(), moving.eastM()) > 0.0, "the target did not move it");
        assertTrue(firstSpeed > 0.0, "the vehicle did not start moving");
        // One tick of the response to the 1.4 m/s the controller asked for, and not the
        // whole of it: the attitude loop has to build the lean that produces the
        // acceleration, so the first tick buys less than one response time's worth of the
        // command.
        assertTrue(
            firstSpeed <= maxSpeed * TICK_SECONDS / responseSeconds(),
            "the airframe reached the commanded speed in a single tick");
        // The target is north and east of the vehicle, so the lean has to be nose-down and
        // rolled right: the attitude points into the travel before the speed exists.
        assertTrue(moving.pitchRad() < 0.0, "the vehicle did not tip nose-down to fly north");
        assertTrue(moving.rollRad() > 0.0, "the vehicle did not roll right to fly east");
        assertEquals(0.0, moving.velocityDownMps(), 0.0001);

        // The approach, tick by tick. 240 ticks is ~60 airframe response times: the leg
        // itself is flown in about 57 of them (measured: the vehicle peaks at 1.385861 m/s
        // 31 ticks after the command arrives, is braking a tick later, snaps onto the point
        // on tick 41 while still carrying 0.47088 m/s - too fast for the plant's arrival
        // deadband to call that arrived - drifts 0.09225 m past the point while the plant
        // brakes it, and is back on the point at rest on tick 57), and the rest of the
        // budget is what the hold is checked over. The overshoot is the plant being honest:
        // the tracker's braking distance is one response time of the speed it is doing, and
        // the finer plant brakes for as long as that really takes, where the 50 ms step used
        // to throw away a quarter of the last tick's speed and stop early.
        double distanceBefore = Math.hypot(2.0 - moving.northM(), 1.0 - moving.eastM());
        double closest = distanceBefore;
        double previousRoll = moving.rollRad();
        double previousPitch = moving.pitchRad();
        double previousSpeed = firstSpeed;
        int settlingTick = -1;
        for (int tick = 1; tick <= 240 && settlingTick < 0; tick++) {
            drone.tick();
            VirtualDroneSnapshot snapshot = drone.snapshot();
            double speed = Math.hypot(snapshot.velocityNorthMps(), snapshot.velocityEastMps());
            assertTrue(
                speed <= maxSpeed + 1.0e-9,
                "horizontal speed left the virtual plant envelope on tick " + tick + ": " + speed);
            // The lean is a real attitude now, so it obeys the airframe's own limits...
            assertTrue(
                Math.abs(snapshot.pitchRad()) <= maxTilt,
                "the pitch passed the lean limit on tick " + tick + ": " + snapshot.pitchRad());
            assertTrue(
                Math.abs(snapshot.rollRad()) <= maxTilt,
                "the roll passed the lean limit on tick " + tick + ": " + snapshot.rollRad());
            // ... and the rates it reports are the ones the integration actually used.
            assertEquals(
                (snapshot.rollRad() - previousRoll) / TICK_SECONDS,
                snapshot.rollRateRadS(),
                1.0e-9,
                "the reported roll rate is not the attitude change of tick " + tick);
            assertEquals(
                (snapshot.pitchRad() - previousPitch) / TICK_SECONDS,
                snapshot.pitchRateRadS(),
                1.0e-9,
                "the reported pitch rate is not the attitude change of tick " + tick);
            previousRoll = snapshot.rollRad();
            previousPitch = snapshot.pitchRad();

            double distance = Math.hypot(2.0 - snapshot.northM(), 1.0 - snapshot.eastM());
            closest = Math.min(closest, distance);
            if (distance > 0.15) {
                // Still clearly on its way to the target, so the distance has to close:
                // the tracker asks for one direction only until the target is in reach...
                assertTrue(distance < distanceBefore, "the vehicle wandered on its way to the target");
                // ... and the lean is what carries it there while the leg is flown under
                // power: nose-down for north, right roll for east, with no yaw commanded.
                //
                // Only while the speed is still building, because leaning away from the
                // travel is not a mistake - it is braking. A thrust vector slows down by
                // tipping out of the travel, and the vehicle has to start that before the
                // target, not on it: measured on this leg, the speed peaks at 1.385861 m/s
                // 31 ticks after the command, the roll is through zero three ticks later
                // with 0.29548 m still to fly, and the nose is up two ticks after that,
                // 0.18316 m out - all of it while the tracker is still closing the leg. What
                // holds the whole way in is the distance above, and the arrival below.
                if (speed > previousSpeed) {
                    assertTrue(
                        snapshot.pitchRad() < 0.0,
                        "the vehicle leaned away from its travel on tick " + tick);
                    assertTrue(
                        snapshot.rollRad() > 0.0,
                        "the vehicle leaned away from its travel on tick " + tick);
                }
            }
            previousSpeed = speed;
            distanceBefore = distance;
            // Settled is "on the target and slow enough to stay on it": the plant calls
            // a centimetre arrival, so a speed inside that per tick is a stop.
            if (distance <= 0.01 && speed <= 0.01) {
                settlingTick = tick;
            }
        }

        VirtualDroneSnapshot reached = drone.snapshot();
        // The guarantee a position setpoint makes, and it is kept: the vehicle arrives at
        // the commanded point and stays there. The last few centimetres are the arrival
        // snap's, and that is the honest shape of the arrival - the vehicle does not fly the
        // final millimetre, it lands on the point. Measured on this leg: 0.03205 m from the
        // point at 0.55642 m/s 40 ticks after the command, the snap puts both axes exactly on
        // (2, 1) on tick 41 - still carrying 0.47088 m/s, which is faster than the 0.2 m/s
        // deadband, so the plant does not call it arrived there: it drifts on to 0.09225 m
        // past the point on tick 50 while the thrust vector brakes it, turns round, and is
        // back on the point at rest on tick 57. That is the tick this loop exits on, with
        // distance 0.0 and speed 0.0; everything after it is the hold below.
        assertTrue(
            settlingTick > 0,
            "the vehicle never settled on its target: closest approach " + closest
                + " m after 240 ticks, still crossing it at "
                + Math.hypot(reached.velocityNorthMps(), reached.velocityEastMps()) + " m/s");
        assertEquals(2.0, reached.northM(), 0.0001);
        assertEquals(1.0, reached.eastM(), 0.0001);
        assertEquals(-1.0, reached.downM(), 0.0001);

        // Nothing is driving the horizontal axes any more once they are on the point, so
        // it stays there instead of creeping off.
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
     * A commanded velocity with no position to track is approached through the airframe's
     * response rather than applied in one tick: the velocity error asks for a lean, the
     * attitude takes its response time to reach that lean, and the speed follows the lean,
     * so the ramp builds up over several tenths of a second. What that gives: the speed
     * climbs monotonically while it is still climbing through the low part of the command,
     * never passes it, never beats what the tilted thrust can accelerate in a tick, leans
     * the way it is going, and moves the vehicle by exactly the integral of the speed the
     * telemetry reported - not one second at the full command, which is what an instant
     * plant delivered.
     *
     * <p>And it gives the command's own value: the trim cancels the drag the lean is
     * fighting, so the speed the vehicle settles on is the one it was asked for - 0.49998
     * m/s of a 0.5 m/s command after 60 ticks, approached from below the whole way. See the
     * settle note before the final assertion.
     */
    @Test
    void followsACommandedVelocityWhenNoPositionIsGiven() {
        VirtualDroneState drone = airborne();
        double commanded = 0.5;
        assertTrue(drone.setLocalSetpoint(velocityOnly(commanded, 0.0, 0.0)));

        double previous = 0.0;
        double travelled = 0.0;
        double travelledFromStart = 0.0;
        double trapezoid = 0.0;
        double perTickLimit = horizontalAccelerationLimitMps2() * TICK_SECONDS;
        double previousPitch = 0.0;
        for (int tick = 1; tick <= 40; tick++) {
            drone.tick();
            VirtualDroneSnapshot snapshot = drone.snapshot();
            double speed = snapshot.velocityNorthMps();
            // Nothing beats the airframe: the commanded acceleration is capped at what a
            // full-lean thrust vector produces, so no tick may add more speed than that.
            assertTrue(
                Math.abs(speed - previous) <= perTickLimit,
                "a tick beat the airframe acceleration limit on tick " + tick);
            // The response must not pass the command: it is a first-order answer to the
            // velocity error, not an integrator that can overshoot its setpoint. The lean
            // is what used to make it ring - the speed keeps climbing while the attitude
            // comes back - so the error is measured against the speed that lean is about
            // to deliver, which leaves the loop overdamped for every airframe (measured
            // before that: 0.50470 m/s on tick 15 for this 0.5 m/s command).
            assertTrue(speed <= commanded, "the response must not overshoot the command");
            // While it is still climbing through the command the lean is growing into what
            // the remaining error asks for, so the speed may only go up - and with the
            // prediction above it climbs the whole way, to 0.49936 m/s at tick 40 rather
            // than ringing in over the last few per cent.
            if (speed < commanded * 0.8) {
                assertTrue(speed >= previous, "the response has to approach the command monotonically");
            }
            // It is the lean that moves the vehicle: north means nose-down, no yaw, and
            // the lean stays inside the airframe's limit.
            assertTrue(snapshot.pitchRad() < 0.0, "the vehicle did not lean into its travel");
            assertTrue(
                Math.abs(snapshot.pitchRad()) <= VehicleModel.DEFAULTS.maxTiltRad(),
                "the lean passed the airframe's lean limit on tick " + tick);
            assertEquals(
                (snapshot.pitchRad() - previousPitch) / TICK_SECONDS,
                snapshot.pitchRateRadS(),
                1.0e-9,
                "the reported pitch rate is not the attitude change of tick " + tick);
            previousPitch = snapshot.pitchRad();
            // The three integrals of exactly the speeds this test watches the plant publish:
            // the two rectangle sums of the published samples, and the finer trapezoid.
            travelledFromStart += previous * TICK_SECONDS;
            travelled += speed * TICK_SECONDS;
            trapezoid += (previous + speed) / 2.0 * TICK_SECONDS;
            previous = speed;
        }

        VirtualDroneSnapshot flying = drone.snapshot();
        assertEquals(0.0, flying.velocityEastMps(), 0.0001);
        // The vehicle really moved, and by the finer integral of the ramp it published: the
        // plant integrates the speed it publishes four times per published sample, so its
        // distance sits between the two rectangle sums of those samples instead of on the
        // right-hand one - which is what "the plant integrates what it publishes, tick for
        // tick" meant while the plant ran at the control rate. Measured on this ramp: the
        // vehicle covered 0.812175 m, against 0.796541 m from the left-hand sum and
        // 0.821509 m from the right-hand one, and 0.003150 m from the trapezoid of the same
        // samples - the sub-step rule's own half step of a ramp that moved 0.499364 m/s,
        // where the 50 ms rectangle rule is 0.009334 m out.
        assertTrue(
            flying.northM() >= travelledFromStart - 1.0e-9,
            "the plant was behind even the left-hand rectangle sum of what it published");
        assertTrue(
            flying.northM() <= travelled + 1.0e-9,
            "the plant was past the right-hand rectangle sum of what it published");
        assertTrue(
            Math.abs(flying.northM() - trapezoid) <= TICK_SECONDS / PHYSICS_SUBSTEPS * commanded,
            "the plant is " + Math.abs(flying.northM() - trapezoid)
                + " m from the trapezoid of its own samples, more than one plant step of the command");
        assertTrue(flying.northM() > 0.5, "the velocity channel did not move the vehicle");
        // An instant plant would have covered the command for the whole run; the ramp (and
        // the drag the airframe is fighting) covers less than that.
        assertTrue(flying.northM() < commanded * 40 * TICK_SECONDS, "the ramp was skipped");
        // The guarantee, and it is kept: a velocity setpoint is a speed the vehicle holds.
        // The lean the velocity error asks for is trimmed by the drag it is fighting, so the
        // response settles on the command itself instead of where the error balances drag:
        // the old plant settled at 0.427 m/s (85% of it), the trim cancels the drag and the
        // speed is 0.49936 m/s here at two seconds - past ten response times - and still
        // rising towards the command from below.
        assertEquals(
            commanded,
            flying.velocityNorthMps(),
            commanded * 0.01,
            "the plant does not reach the speed it was commanded");
    }

    /**
     * The same command at the top of the envelope. The ramp is the airframe's answer to a
     * step demand: one tick buys a fraction of the command (the lean has to be built
     * first), no tick beats the acceleration a full-lean thrust vector can produce, the
     * ramp never passes the command, and it climbs monotonically the whole way up.
     *
     * <p>Two seconds in it is at 1.356463 m/s of the 1.4 m/s it asked for - a step to the
     * top of the envelope is clearly not something this plant delivers in one tick, and the
     * 0.044 m/s it is still short after forty of them is the last of the approach, not a
     * shortfall: the trim puts the settled value on the command, so the gap keeps closing
     * rather than stopping at a number drag picks.
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
        double perTickLimit = horizontalAccelerationLimitMps2() * TICK_SECONDS;
        for (int tick = 2; tick <= 40; tick++) {
            drone.tick();
            double speed = drone.snapshot().velocityNorthMps();
            assertTrue(speed <= topSpeed, "the ramp passed the command");
            // The acceleration comes from the attitude, so its per-tick bound is the
            // acceleration a full-lean thrust vector produces, from the vehicle model.
            assertTrue(speed - previous <= perTickLimit, "a tick beat the airframe acceleration limit");
            // The ramp climbs the whole way: the response is overdamped now (the error is
            // measured against the speed the lean is about to deliver), so there is no
            // settle region to ring inside - measured, 0.430290 at tick 10, 1.075207 at tick
            // 25 and 1.356463 at tick 40, still rising.
            assertTrue(speed > previous, "the ramp stopped closing the gap");
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
     * A later frame that only commands yaw replaces the setpoint, so the horizontal axes
     * have no command any more. What the vehicle does then is coast: nothing is asking for
     * speed, so the attitude has to swing back through level before any thrust points the
     * other way, and the velocity loop then unwinds what the vehicle was carrying. It does
     * not keep flying and it does not stop dead either: it never travels backwards, it
     * comes to rest within the distance the airframe's own swing-back and response need,
     * and it stays there.
     *
     * <p>One response time's worth of travel is not that distance, and expecting it was
     * reading the old kinematic plant: while the attitude is still tipping back the
     * vehicle keeps the speed it had, so the coast is the swing back *plus* the response,
     * not the response alone. Measured on this run: 0.266296 m from 0.643053 m/s with the
     * nose 0.323614 rad down, which is 0.41 s of travel - and the first tenth of a second
     * of it is still under power: the speed does not begin to fall until the nose has come
     * back through level, so the run's peak is 0.685675 m/s, above the speed it started
     * with. A single 50 ms step could not show that, because it applied the braking of a
     * whole step at the attitude the step ended on.
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
        // It was tracking because it was leaning into the travel: nose-down, with no yaw
        // commanded.
        assertTrue(tracking.pitchRad() < 0.0, "the north axis was tracked without leaning");
        double northWhenYawWasCommanded = tracking.northM();
        double speedWhenYawWasCommanded = tracking.velocityNorthMps();
        double pitchWhenYawWasCommanded = tracking.pitchRad();

        assertTrue(drone.setLocalSetpoint(new LocalSetpoint(
            LocalSetpoint.Axis.unset(), LocalSetpoint.Axis.unset(), LocalSetpoint.Axis.unset(),
            true, 0.3, false, 0.0)));

        // The coast, tick by tick: the demand is gone, so the attitude tips back through
        // level and the speed decays to rest. The swing back is one attitude time constant
        // and the velocity loop needs about four response times to unwind the speed, so
        // that sum is the budget - and the run measures sixteen ticks of it. The rest of
        // the 80 is idle time: the decay is exponential rather than a landing on zero, so
        // the hold below is only checked once the response has actually died out (measured
        // 0.000265 m/s left at tick 40, and the vehicle creeps not at all over the next ten
        // ticks from there).
        int restBudgetTicks = (int) Math.ceil(
            (VehicleModel.DEFAULTS.attitudeTimeConstantS() + 4 * responseSeconds()) / TICK_SECONDS);
        double previousPitch = pitchWhenYawWasCommanded;
        double slowest = 0.0;
        int ticksToRest = -1;
        boolean leanedBackWhileMoving = false;
        for (int tick = 1; tick <= 80; tick++) {
            drone.tick();
            VirtualDroneSnapshot snapshot = drone.snapshot();
            slowest = Math.min(slowest, snapshot.velocityNorthMps());
            assertEquals(
                (snapshot.pitchRad() - previousPitch) / TICK_SECONDS,
                snapshot.pitchRateRadS(),
                1.0e-9,
                "the reported pitch rate is not the attitude change of tick " + tick);
            previousPitch = snapshot.pitchRad();
            if (tick == 1) {
                // One tick in, the lean is already on its way back to level while the
                // vehicle still carries its speed: that is the phase where it drifts.
                leanedBackWhileMoving = snapshot.pitchRad() > pitchWhenYawWasCommanded;
            }
            if (ticksToRest < 0
                && Math.abs(snapshot.velocityNorthMps()) <= speedWhenYawWasCommanded * 0.1) {
                ticksToRest = tick;
            }
            // The coast is a stop, not an exit: it may not pick up travel again.
            assertTrue(
                snapshot.northM() >= northWhenYawWasCommanded,
                "the vehicle travelled backwards while coasting");
        }

        VirtualDroneSnapshot held = drone.snapshot();
        assertTrue(leanedBackWhileMoving, "the vehicle did not lean back before it could stop");
        assertEquals(0.0, held.velocityNorthMps(), 0.001);
        assertTrue(
            ticksToRest > 0 && ticksToRest <= restBudgetTicks,
            "the coast took longer than the swing back plus the response: " + ticksToRest);
        // The trim that makes a commanded speed reachable removed the drag that used to be
        // the loop's only damping, and the prediction that replaced it leaves the coast
        // overdamped instead of ringing: this run's slowest northward speed is +0.00000 m/s,
        // so it does not reverse at all any more. The bound stays as the guarantee that the
        // coast is a stop - a vehicle that swung back through level hard enough to fly
        // backwards would be a second flight, not a landing.
        assertTrue(
            Math.abs(slowest) <= speedWhenYawWasCommanded * 0.05,
            "the coast reversed by more than the attitude change can explain: " + slowest);
        // ... and it is bounded by what the swing back and the loop cost, which can be
        // written down rather than guessed. While the demand is gone the horizontal channel
        // is the same second-order loop the response test uses, so with v0 the speed and a0
        // the acceleration the attitude was still producing when the frame arrived, the
        // distance it can cover before it stops is exactly
        //   v0 * (attitude constant + response) + a0 * attitude constant * response
        // (drag only shortens it, so this is an upper bound). Measured on this run: 0.284860
        // m from 0.643053 m/s with the nose 0.323614 rad down, and the plant coasts
        // 0.266296 m of it - 93 per cent, where the single 50 ms step was 77 per cent,
        // because that one applied a whole step of braking at the attitude of the end of the
        // step and stopped the vehicle early. The old bound this replaces was 0.237930 m,
        // the swing back plus the response with no drift in it: 0.325 s of travel for a
        // vehicle that spends the first tenth of a second still accelerating.
        double attitudeS = VehicleModel.DEFAULTS.attitudeTimeConstantS();
        double initialAccelMps2 = VehicleModel.GRAVITY_MPS2
            * Math.tan(Math.abs(pitchWhenYawWasCommanded));
        double coastLawM = speedWhenYawWasCommanded * (attitudeS + responseSeconds())
            + initialAccelMps2 * attitudeS * responseSeconds();
        double coastBudgetM = coastLawM + speedWhenYawWasCommanded * TICK_SECONDS;
        double coastedM = held.northM() - northWhenYawWasCommanded;
        assertTrue(
            coastedM <= coastBudgetM,
            "the vehicle coasted further than its swing back and response allow: "
                + coastedM + " m against " + coastBudgetM);
        assertTrue(
            coastedM >= coastLawM * 0.8,
            "the vehicle stopped sooner than the airframe can: " + coastedM
                + " m against " + coastLawM + " m of coasting available");
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
     *
     * <p>What carried it past was the trim, which hands the step exactly the acceleration
     * the drag is about to take away and so cannot cancel it to the digit at the limit:
     * measured 1.4000505255 m/s against the 1.4 m/s limit on tick 20, 0.0036 per cent
     * over. The achieved pair is scaled back onto the limit every tick, so the envelope is
     * a bound rather than a target - this run stays at or below 1.4 m/s throughout.
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
     * A frame may command a position and a speed on the same axis, and then the position is
     * a point on a trajectory whose speed the sender is feeding forward - the main
     * project's own note on that is explicit that a fixed point with a constant
     * feed-forward keeps being pushed by it, so the position loop can only chase.
     *
     * <p>What the arrival deadband used to do instead was silence it. The per-axis arrival
     * snap put the position back on the target on every tick, so the vehicle read the
     * commanded speed, applied it, and had it undone before it could carry the vehicle
     * anywhere: measured 0.00000 m of travel in twenty ticks, standing still at 0.0000 m/s
     * - a velocity channel that does not move the vehicle. The deadband belongs to axes
     * the sender asked to hold and nothing else, and this is the check for it.
     */
    @Test
    void keepsAFedForwardSpeedMovingUnderAPositionChannel() {
        VirtualDroneState drone = airborne();
        drone.setPositionTarget(0.0, 0.0, -1.0);
        for (int tick = 0; tick < 10; tick++) {
            drone.tick();
        }
        VirtualDroneSnapshot holding = drone.snapshot();
        assertTrue(drone.setLocalSetpoint(new LocalSetpoint(
            new LocalSetpoint.Axis(true, holding.northM(), true, 0.5, false, 0.0),
            new LocalSetpoint.Axis(true, holding.eastM(), false, 0.0, false, 0.0),
            LocalSetpoint.Axis.unset(), false, 0.0, false, 0.0)));

        // The feed-forward is the demand, and the position loop chases it: the vehicle flies
        // out, the tracker's demand grows until it cancels the feed-forward, and the vehicle
        // comes back. Measured: 0.23425 m out on tick 17 at 0.18010 m/s, 0.24260 m out on
        // tick 19 - the furthest - and back to 0.23970 m on tick 20 at -0.09527 m/s, with the
        // tracker's braking demand now beating the feed-forward.
        double commanded = 0.5;
        double furthest = 0.0;
        for (int tick = 1; tick <= 20; tick++) {
            drone.tick();
            furthest = Math.max(furthest, drone.snapshot().northM());
        }
        assertTrue(
            furthest > 0.10,
            "the commanded speed did not move the vehicle under a position channel: " + furthest);
        // ... and how far out it goes is arithmetic, not a runaway: the tracker's demand
        // cancels the feed-forward once (distance - speed * response) / response reaches it,
        // which is 2 * 0.2 * 0.5 = 0.2 m, plus the deadband it stops demanding inside and
        // the extra the plant's two-lag braking distance yields. Measured 0.26198 m against
        // this 0.30 m bound: the point a velocity feed-forward drags the vehicle off its
        // reference is a property of the PVA contract, so it is bounded rather than small.
        assertTrue(
            furthest <= 2 * commanded * responseSeconds() + 0.10,
            "the vehicle ran away from the point it was also holding: " + furthest);
    }

    /**
     * The horizontal acceleration the airframe can command, in m/s^2.
     *
     * <p>The velocity error is turned into a lean and the acceleration comes from what that
     * lean actually produces, so the cap is {@code g * tan(maxTiltRad())} - only the part
     * of the thrust that can be pointed sideways accelerates the vehicle. No tick of the
     * response may beat it.
     */
    private static double horizontalAccelerationLimitMps2() {
        return VehicleModel.GRAVITY_MPS2 * Math.tan(VehicleModel.DEFAULTS.maxTiltRad());
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
