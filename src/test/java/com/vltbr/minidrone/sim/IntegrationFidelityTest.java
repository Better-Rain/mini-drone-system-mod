package com.vltbr.minidrone.sim;

import com.vltbr.minidrone.mavlink.MavlinkProtocol;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How accurate the plant's integration actually is, measured instead of assumed.
 *
 * <p>The control loop runs at 20 Hz and the plant under it runs {@link #PHYSICS_SUBSTEPS}
 * times per control tick, so the step the plant integrates at is no longer the step the
 * setpoints arrive at. This test is the measurement that justified splitting the two: the
 * position is integrated with a rectangle rule, and a rectangle rule costs `h/2 * v` of
 * bias per acceleration phase - 3.5 cm at the top speed with a single 50 ms step, 0.875 cm
 * with four 12.5 ms sub-steps - while an attitude with a 0.12 s response only develops
 * over 2.4 samples per step at 20 Hz.
 *
 * <p>What is *not* true, and what an earlier version of this file asserted, is that the
 * velocity channel is a single first-order lag sampled at the tick rate: it is a cascade
 * of the attitude and the velocity response, so the closed form below is the two-lag one,
 * and the plant tracks it four times more closely than the single 50 ms step did.
 */
class IntegrationFidelityTest {
    private static final double EPSILON = 1.0e-6;
    /**
     * The control period and the plant's sub-step count, mirrored the way
     * {@code VirtualDroneStateTest} mirrors the tick: the numbers asserted below are
     * derived from them rather than typed in.
     */
    private static final double TICK_SECONDS = 0.05;
    private static final int PHYSICS_SUBSTEPS = 4;

    private static VirtualDroneState hovering() {
        VirtualDroneState drone = new VirtualDroneState(54, 1, "minecraft_drone_01");
        drone.setMode(MavlinkProtocol.ARDUCOPTER_MODE_GUIDED);
        drone.setArmed(true);
        drone.takeoff(1.5);
        for (int tick = 0; tick < 60; tick++) {
            drone.tick();
        }
        return drone;
    }

    /**
     * The velocity channel is monotone and arrives inside the envelope it promises.
     *
     * <p>The premise this file used to carry - a single lag of the response time sampled
     * at the control rate, `demand * (1 - (1 - h/T)^n)` - was wrong, and the first tick
     * said so: it expected 0.25 m/s of a 1 m/s command and the plant delivered 0.0355
     * (0.0186 now). The channel is a cascade: the velocity error asks for an acceleration,
     * the attitude takes its own 0.12 s to reach the lean that produces it, and the speed
     * follows the lean. What that does guarantee is the shape below.
     *
     * <p>Measured on the 0.5 m/s command this drives: the speed climbs from 0.018599 on
     * tick 1 through 0.373420 on tick 10 and 0.479102 on tick 20, reaches 1 per cent of
     * the command - 0.495 m/s - on tick 29, and is at 0.499980 on tick 60. It never once
     * falls back and never once passes the command.
     */
    @Test
    void theVelocityChannelIsMonotoneAndArrivesInsideItsBudget() {
        double demand = 0.5;
        // 35 ticks is 8.75 response times: nearly three times the 29 ticks the response
        // actually needs, so this asserts a budget rather than a stopwatch.
        int budgetTicks = 35;

        VirtualDroneState drone = hovering();
        assertTrue(drone.setLocalSetpoint(velocityNorth(demand)));
        double previous = 0.0;
        int ticksToWithinOnePercent = -1;
        for (int tick = 1; tick <= 60; tick++) {
            drone.tick();
            double speed = drone.snapshot().velocityNorthMps();
            // The two-stage response is overdamped - the velocity error is measured against
            // the speed the lean is about to deliver - so the command is approached from
            // below and never passed, and the ramp only ever goes one way. That is a real
            // guarantee: a response that rang past its command would fly the vehicle
            // further than its setpoint asked for.
            assertTrue(speed >= previous, "the response fell back on tick " + tick);
            assertTrue(speed <= demand, "the response passed the command on tick " + tick);
            if (ticksToWithinOnePercent < 0 && speed >= demand * 0.99) {
                ticksToWithinOnePercent = tick;
            }
            previous = speed;
        }

        assertTrue(
            ticksToWithinOnePercent > 0 && ticksToWithinOnePercent <= budgetTicks,
            "the channel did not reach within 1 per cent of the command inside " + budgetTicks
                + " ticks: " + ticksToWithinOnePercent + ", at " + previous + " m/s");
    }

    /**
     * The velocity channel tracks the closed form of its own loop, which is the accuracy
     * the plant's step buys.
     *
     * <p>The plant's loop is a cascade of the attitude lag `t` and the response `T`; with
     * the drag feed-forward cancelling the drag, it is exactly
     * `t*v'' + (1 + t/T)*v' + (v - demand)/T = 0`, whose step response is
     * `demand * (1 + 1.5*e^(-t/t) - 2.5*e^(-t/T))`. That is the reference here: the
     * analytic answer the sampled plant is trying to reproduce, evaluated at each control
     * tick.
     *
     * <p>The demand is a step only while it is below the one-tick acceleration the tracker
     * allows - `g * tan(maxTilt) * 0.05` = 0.17385 m/s - so this drives 0.17 m/s and holds
     * it. The sampled plant is then within 0.0041 m/s of the closed form, 2.4 per cent of
     * the command, its worst tick being tick 3 where the loop is fastest. The single 50 ms
     * step was 0.0179 m/s out at that same tick, 10.5 per cent, because the acceleration it
     * applied for a whole step was the attitude at the end of it: a quarter of the step is a
     * quarter of that error, which is what the measurement below shows.
     */
    @Test
    void theVelocityChannelTracksItsTwoStageClosedForm() {
        double demand = 0.17;
        double attitudeS = VehicleModel.DEFAULTS.attitudeTimeConstantS();
        double responseS = VehicleModel.DEFAULTS.motorTimeConstantS() + attitudeS;

        VirtualDroneState drone = hovering();
        assertTrue(drone.setLocalSetpoint(velocityNorth(demand)));

        double worstDeviation = 0.0;
        for (int tick = 1; tick <= 20; tick++) {
            drone.tick();
            double speed = drone.snapshot().velocityNorthMps();
            double elapsed = tick * TICK_SECONDS;
            double closedForm = demand * (1.0
                + responseS * Math.exp(-elapsed / responseS) / (attitudeS - responseS)
                - attitudeS * Math.exp(-elapsed / attitudeS) / (attitudeS - responseS));
            worstDeviation = Math.max(worstDeviation, Math.abs(speed - closedForm));
        }

        // 4 per cent of the command is a loose bound for a measured 2.4 per cent, and still
        // well inside the 10.5 per cent the single step was out by: this fails if the plant
        // is integrated at the control rate again.
        assertTrue(
            worstDeviation <= demand * 0.04,
            "the channel is " + worstDeviation + " m/s from its closed form, "
                + (worstDeviation / demand * 100.0) + " per cent of the command");
    }

    /**
     * The position bias a rectangle rule leaves per acceleration phase, before and after.
     *
     * <p>The rule integrates a changing velocity as a staircase, so a phase that ends at
     * speed `v` is short (or long) by half a step of it. With the single 50 ms step that was
     * `0.05/2 * 1.4` = 3.5 cm per phase - most of the 5 cm arrival deadband, on every leg,
     * and the number that justified running the plant finer. With four 12.5 ms sub-steps it
     * is `0.0125/2 * 1.4` = 0.875 cm, exactly a quarter of it.
     *
     * <p>Both numbers above are arithmetic; the run below is the measurement. It flies a
     * 40 tick acceleration phase to the top speed and compares the plant's own position
     * against the trapezoid of the speeds it published - second order rather than first, so
     * a reference, and the measured agreement says how good a one: the plant is 0.008539 m
     * from it, against the 0.008750 m the half-sub-step term above predicts. The same
     * samples integrated with the old 50 ms rectangle rule are 0.033912 m from the
     * trapezoid, four times further out. That is the improvement, in metres.
     */
    @Test
    void thePositionBiasStaysUnderACentimetrePerPhase() {
        double plantStep = TICK_SECONDS / PHYSICS_SUBSTEPS;
        double topSpeed = VehicleModel.DEFAULTS.maxHorizontalSpeedMps();
        double biasBefore = TICK_SECONDS / 2.0 * topSpeed;
        double biasAfter = plantStep / 2.0 * topSpeed;

        assertTrue(biasAfter < 0.01,
            "half a plant step of the top speed is " + biasAfter + " m of rectangle-rule bias");
        assertTrue(biasBefore > 0.03,
            "the single-step bias this change removed was " + biasBefore + " m per phase");

        VirtualDroneState drone = hovering();
        assertTrue(drone.setLocalSetpoint(velocityNorth(topSpeed)));
        double rectangleSum = 0.0;
        double trapezoidSum = 0.0;
        double previous = 0.0;
        for (int tick = 1; tick <= 40; tick++) {
            drone.tick();
            double speed = drone.snapshot().velocityNorthMps();
            rectangleSum += speed * TICK_SECONDS;
            trapezoidSum += (previous + speed) / 2.0 * TICK_SECONDS;
            previous = speed;
        }
        double travelled = drone.snapshot().northM();
        double plantBias = Math.abs(travelled - trapezoidSum);
        double oldBias = Math.abs(rectangleSum - trapezoidSum);

        // The plant really is integrating at the finer step: its position is within the
        // sub-step bias of the reference, and the old rule's bias is the quarter-of-a-tick
        // term the sub-stepping removed, four times larger.
        assertTrue(
            plantBias < 0.01,
            "the plant is " + plantBias + " m from the reference over the phase");
        assertTrue(
            oldBias > 3.0 * plantBias,
            "the single-step rule was not four times further out: " + oldBias
                + " m against " + plantBias + " m");
    }

    /**
     * Free fall is pure gravity integrated at the plant's step, so its height is the closed
     * form plus exactly half a step of gravity.
     *
     * <p>The earlier version of this test disarmed the vehicle with
     * {@code setLocalPosition}, which an armed vehicle refuses, so it failed on its own
     * setup rather than on the height. The fall has no response ramp in it at all: the
     * vehicle has lost its thrust rather than being asked for a descent rate, so the speed
     * it publishes is `g*t` to the digit on every tick. What the step does decide is the
     * height, and a rectangle rule over gravity overshoots the closed form `0.5*g*t^2` by
     * `0.5*g*t*h` - 3.07 cm after half a second with the 12.5 ms sub-step, against the
     * 12.26 cm the single 50 ms step gave.
     */
    @Test
    void freeFallTracksTheClosedFormHeight() {
        VirtualDroneState drone = hovering();
        double startM = drone.snapshot().downM();
        // A forced disarm in the air is what an emergency stop does, and it starts the fall
        // from rest.
        assertTrue(drone.setArmed(false));
        double plantStep = TICK_SECONDS / PHYSICS_SUBSTEPS;

        double previous = startM;
        for (int tick = 1; tick <= 10; tick++) {
            drone.tick();
            double elapsed = tick * TICK_SECONDS;
            VirtualDroneSnapshot snapshot = drone.snapshot();
            // Gravity, unlagged: the free fall is not the thrust channel, so there is no
            // response time in it.
            assertEquals(VehicleModel.GRAVITY_MPS2 * elapsed, snapshot.velocityDownMps(), EPSILON,
                "the descent speed is not gravity * t on tick " + tick);
            // The height is the rectangle sum of exactly that speed, at the plant's step,
            // which is the closed form plus the rule's half step of gravity.
            assertEquals(
                startM + 0.5 * VehicleModel.GRAVITY_MPS2 * elapsed * elapsed
                    + 0.5 * VehicleModel.GRAVITY_MPS2 * elapsed * plantStep,
                snapshot.downM(),
                1.0e-9,
                "height after " + elapsed + " s of free fall");
            // Down is positive in NED, so a fall is this growing towards zero.
            assertTrue(snapshot.downM() > previous, "the fall does not descend monotonically");
            previous = snapshot.downM();
        }

        // And how far that leaves the fall from the closed form after half a second: the
        // half-step term above, 3.07 cm, where the single 50 ms step was 12.26 cm out.
        double closedFormHeight = startM + 0.5 * VehicleModel.GRAVITY_MPS2 * 0.5 * 0.5;
        assertEquals(
            0.5 * VehicleModel.GRAVITY_MPS2 * 0.5 * plantStep,
            Math.abs(previous - closedFormHeight),
            1.0e-9,
            "the fall is not the closed form plus half a plant step");
    }

    private static LocalSetpoint velocityNorth(double speed) {
        return new LocalSetpoint(
            new LocalSetpoint.Axis(false, 0.0, true, speed, false, 0.0),
            LocalSetpoint.Axis.unset(),
            LocalSetpoint.Axis.unset(),
            false,
            0.0,
            false,
            0.0
        );
    }

    @Test
    void theNumbersAboveAreTheOnesThePlantUses() {
        assertEquals(0.2, VehicleModel.DEFAULTS.motorTimeConstantS()
            + VehicleModel.DEFAULTS.attitudeTimeConstantS(), EPSILON);
        assertEquals(1.4, VehicleModel.DEFAULTS.maxHorizontalSpeedMps(), EPSILON);
        // The plant's step, which every expectation above is derived from.
        assertEquals(0.0125, TICK_SECONDS / PHYSICS_SUBSTEPS, EPSILON);
    }
}
