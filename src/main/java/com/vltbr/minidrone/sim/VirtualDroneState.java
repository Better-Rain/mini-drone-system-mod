package com.vltbr.minidrone.sim;

import com.vltbr.minidrone.mavlink.MavlinkProtocol;

public final class VirtualDroneState {
    private static final double TICK_SECONDS = 0.05;
    /**
     * Top horizontal speed of the default vehicle, in metres per second.
     *
     * <p>Kept as a constant because the health beacon's period has to stay inside the
     * backend consistency window for it; the vehicle actually flies at whatever its
     * {@link VehicleModel} says.
     */
    public static final double HORIZONTAL_SPEED_LIMIT_MPS = VehicleModel.DEFAULTS.maxHorizontalSpeedMps();
    // A disarmed multirotor has no thrust left, so it falls rather than holding
    // altitude. This is a plain free fall, not the full plant: it exists because
    // the backend only releases an emergency stop once the flight controller
    // reports a landed, disarmed vehicle (EmergencyStopReleasePolicy), and a
    // vehicle parked in the air can never satisfy that.
    private static final double FALL_GRAVITY_MPS2 = 9.81;
    private static final double FALL_TERMINAL_SPEED_MPS = 8.0;
    private static final double MAX_YAW_RATE_RAD_S = 1.5;
    private static final double GROUND_EPSILON_M = 0.01;
    private static final double POSITION_EPSILON_M = 0.01;
    /**
     * How close a commanded point is considered reached. Inside it the vehicle holds
     * position instead of chasing the last centimetres, which is what a real position
     * loop does with its deadband - and what stops a lagged vehicle hunting around a
     * point it can never sit exactly on.
     */
    private static final double ARRIVAL_DEADBAND_M = 0.05;
    /** And how slow it has to be for that to count. */
    private static final double ARRIVAL_SPEED_MPS = 0.20;
    private static final double YAW_EPSILON_RAD = 0.001;
    private static final double MAX_HORIZONTAL_DISTANCE_M = 120.0;
    /**
     * The airframe being simulated: mass, thrust, drag, lag, limits. Every number the
     * motion depends on comes from here, so tuning the vehicle does not mean editing
     * constants.
     */
    private VehicleModel vehicleModel = VehicleModel.DEFAULTS;

    private static final int AXIS_COUNT = 3;

    private final int systemId;
    private final int componentId;
    private final String droneId;
    private final long bootNanoTime = System.nanoTime();
    private final Channel position = new Channel();
    private final Channel velocity = new Channel();
    private final Channel acceleration = new Channel();
    private final double[] actuatedVelocityMps = new double[AXIS_COUNT];
    /**
     * The acceleration the last tick actually applied to the horizontal axes, m/s^2.
     *
     * <p>Kept because the lean lags: the speed the vehicle is about to carry is its
     * speed plus one attitude response time of this, which is what
     * {@link #applyThrustVectorMotion} has to measure the velocity error against.
     */
    private final double[] achievedAccelerationMps2 = new double[AXIS_COUNT];
    private boolean yawSet;
    private double yawSetpointRad;
    private boolean yawRateSet;
    private double yawRateSetpointRadS;
    private boolean setpointActive;
    private boolean armed;
    private FlightPhase flightPhase = FlightPhase.LANDED;
    private int customMode;
    private double northM;
    private double eastM;
    private double downM;
    private double velocityNorthMps;
    private double velocityEastMps;
    private double velocityDownMps;
    private double rollRad;
    private double pitchRad;
    private double yawRad;
    private double rollRateRadS;
    private double pitchRateRadS;
    private double yawRateRadS;
    private double batteryPercent = 100.0;
    /** Descent speed built up while falling after an in-flight disarm. */
    private double fallSpeedMps;
    /**
     * True while something solid is holding the vehicle up.
     *
     * <p>The world refines it every tick; until a world says otherwise the vehicle is
     * assumed supported, which is what it is at spawn and what keeps the simulation
     * usable on its own (the unit tests and the in-game self test run it with no world
     * at all).
     */
    private boolean supportedByWorld = true;
    /**
     * Set when the vehicle has been destroyed by an impact.
     *
     * <p>It latches: the health beacon reports it as safety_latched, so the monitoring
     * side refuses commands until the operator resets the vehicle, which is also what
     * clears it.
     */
    private boolean safetyLatched;

    public VirtualDroneState(int systemId, int componentId, String droneId) {
        this.systemId = systemId;
        this.componentId = componentId;
        this.droneId = droneId;
    }

    public void tick() {
        velocityNorthMps = 0.0;
        velocityEastMps = 0.0;
        velocityDownMps = 0.0;
        yawRateRadS = 0.0;

        // Unpowered and unsupported means it falls, evaluated every tick rather than
        // only when the disarm arrives: a vehicle that becomes unsupported later - the
        // block under it was removed, the origin moved - has to fall too.
        if (!armed && !supportedByWorld
            && flightPhase != FlightPhase.LANDING
            && flightPhase != FlightPhase.FALLING) {
            flightPhase = FlightPhase.FALLING;
            fallSpeedMps = 0.0;
        }

        if (flightPhase == FlightPhase.TAKING_OFF) {
            velocityDownMps = -vehicleModel.climbRateMps();
            double altitudeTarget = position.value(AXIS_DOWN);
            downM = Math.max(altitudeTarget, downM + velocityDownMps * TICK_SECONDS);
            if (downM <= altitudeTarget + GROUND_EPSILON_M) {
                downM = altitudeTarget;
                velocityDownMps = 0.0;
                flightPhase = FlightPhase.FLYING;
            }
        } else if (flightPhase == FlightPhase.LANDING) {
            velocityDownMps = vehicleModel.descentRateMps();
            downM = Math.min(0.0, downM + velocityDownMps * TICK_SECONDS);
            if (downM >= -GROUND_EPSILON_M) {
                downM = 0.0;
                velocityDownMps = 0.0;
                armed = false;
                flightPhase = FlightPhase.LANDED;
            }
        } else if (flightPhase == FlightPhase.FALLING) {
            fallSpeedMps = Math.min(
                FALL_TERMINAL_SPEED_MPS,
                fallSpeedMps + FALL_GRAVITY_MPS2 * TICK_SECONDS
            );
            velocityDownMps = fallSpeedMps;
            downM = Math.min(0.0, downM + fallSpeedMps * TICK_SECONDS);
            if (downM >= -GROUND_EPSILON_M) {
                downM = 0.0;
                velocityDownMps = 0.0;
                fallSpeedMps = 0.0;
                flightPhase = FlightPhase.LANDED;
            }
        } else if (flightPhase == FlightPhase.FLYING && armed && setpointActive) {
            updateSetpointMotion();
        }

        updateYaw();
        updateAttitude();

        if (armed) {
            batteryPercent = Math.max(0.0, batteryPercent - 0.0005);
        }
    }

    public boolean setMode(int requestedMode) {
        if (requestedMode != MavlinkProtocol.ARDUCOPTER_MODE_GUIDED && requestedMode != 9) {
            return false;
        }
        customMode = requestedMode;
        if (requestedMode == 9 && armed) {
            flightPhase = FlightPhase.LANDING;
        }
        return true;
    }

    public boolean setArmed(boolean requestedArmed) {
        if (requestedArmed && customMode != MavlinkProtocol.ARDUCOPTER_MODE_GUIDED) {
            return false;
        }
        armed = requestedArmed;
        if (!requestedArmed) {
            clearSetpoint();
            if (downM < -GROUND_EPSILON_M) {
                // Disarmed in flight (an emergency stop, or any forced disarm): the
                // vehicle loses its thrust and falls to the ground. Hovering here
                // instead would leave it airborne forever with no way to recover -
                // the backend refuses to release an emergency stop until the flight
                // controller reports ON_GROUND, and it never would.
                flightPhase = FlightPhase.FALLING;
                fallSpeedMps = 0.0;
            } else {
                flightPhase = FlightPhase.LANDED;
            }
        }
        return true;
    }

    public boolean takeoff(double altitudeM) {
        if (!armed || customMode != MavlinkProtocol.ARDUCOPTER_MODE_GUIDED
            || !Double.isFinite(altitudeM) || altitudeM < 0.3 || altitudeM > 120.0) {
            return false;
        }
        clearSetpoint();
        fallSpeedMps = 0.0;
        position.set(AXIS_DOWN, true, -altitudeM);
        flightPhase = FlightPhase.TAKING_OFF;
        return true;
    }

    public boolean land() {
        customMode = 9;
        clearSetpoint();
        if (!armed && flightPhase == FlightPhase.LANDED) {
            return true;
        }
        fallSpeedMps = 0.0;
        flightPhase = FlightPhase.LANDING;
        return true;
    }

    /** Position-only setpoint, equivalent to a frame that ignores every other channel. */
    public boolean setPositionTarget(double north, double east, double down) {
        return setLocalSetpoint(LocalSetpoint.positionOnly(north, east, down));
    }

    /**
     * Applies one PVA setpoint. Channels the sender did not command are dropped
     * from the new setpoint, so those axes keep their previous behaviour.
     */
    public boolean setLocalSetpoint(LocalSetpoint setpoint) {
        if (!armed || customMode != MavlinkProtocol.ARDUCOPTER_MODE_GUIDED
            || (flightPhase != FlightPhase.FLYING && flightPhase != FlightPhase.TAKING_OFF)
            || !setpoint.anyCommanded() || !finite(setpoint)) {
            return false;
        }
        if (!positionInRange(setpoint)) {
            return false;
        }
        applyChannel(position, setpoint.north(), setpoint.east(), setpoint.down());
        applyVelocityChannel(setpoint);
        applyAccelerationChannel(setpoint);
        yawSet = setpoint.yawSet();
        yawSetpointRad = setpoint.yawRad();
        yawRateSet = setpoint.yawRateSet();
        yawRateSetpointRadS = setpoint.yawRateRadS();
        setpointActive = true;
        return true;
    }

    public boolean canResetLocalPosition() {
        return !armed && flightPhase == FlightPhase.LANDED;
    }

    public boolean resetLocalPosition() {
        if (!canResetLocalPosition()) {
            return false;
        }
        clearSetpoint();
        northM = 0.0;
        eastM = 0.0;
        downM = 0.0;
        velocityNorthMps = 0.0;
        velocityEastMps = 0.0;
        velocityDownMps = 0.0;
        rollRad = 0.0;
        pitchRad = 0.0;
        rollRateRadS = 0.0;
        pitchRateRadS = 0.0;
        return true;
    }

    /**
     * Puts the vehicle at a given local NED offset, as if the operator had carried
     * it there.
     *
     * <p>Allowed while disarmed at any altitude, because that is what placing the
     * drone by hand means - and then the same rule as a forced disarm applies: an
     * unpowered vehicle in the air does not hover, it falls. Placing it on the ground
     * simply leaves it resting there.
     */
    public boolean setLocalPosition(double north, double east, double down) {
        if (armed) {
            return false;
        }
        clearSetpoint();
        northM = north;
        eastM = east;
        downM = down;
        velocityNorthMps = 0.0;
        velocityEastMps = 0.0;
        velocityDownMps = 0.0;
        rollRad = 0.0;
        pitchRad = 0.0;
        rollRateRadS = 0.0;
        pitchRateRadS = 0.0;
        fallSpeedMps = 0.0;
        customMode = MavlinkProtocol.ARDUCOPTER_MODE_GUIDED;
        flightPhase = down < -GROUND_EPSILON_M ? FlightPhase.FALLING : FlightPhase.LANDED;
        return true;
    }


    /**
     * Takes the position the world actually allowed, and what stopped the vehicle
     * getting further.
     *
     * <p>This is what makes the telemetry honest: the plant integrates what the
     * setpoints asked for, the world resolves it against the blocks, and the result
     * comes back here. A vehicle pressed against a wall therefore reports standing
     * still, not the speed it would like to be doing, and a vehicle that has come to
     * rest on something finishes its descent instead of hovering a centimetre above it
     * forever.
     */
    public void adoptExternalPosition(
        double north,
        double east,
        double down,
        boolean blockedHorizontally,
        boolean blockedVertically,
        boolean blockedNorth,
        boolean blockedEast,
        boolean supported
    ) {
        northM = north;
        eastM = east;
        downM = down;
        supportedByWorld = supported;

        // A light touch is not a stop: the component into the surface comes back
        // reversed (restitution) and the component along it loses speed to friction.
        // Zeroing both is what made the vehicle stick to walls like a stamp.
        double friction = vehicleModel.friction();
        double restitution = vehicleModel.restitution();
        if (blockedNorth) {
            velocityNorthMps = ContactResponse.rebound(velocityNorthMps, restitution);
            velocityEastMps = ContactResponse.slide(velocityEastMps, friction, TICK_SECONDS);
        } else if (blockedEast) {
            velocityEastMps = ContactResponse.rebound(velocityEastMps, restitution);
            velocityNorthMps = ContactResponse.slide(velocityNorthMps, friction, TICK_SECONDS);
        } else if (blockedHorizontally) {
            velocityNorthMps = ContactResponse.slide(velocityNorthMps, friction, TICK_SECONDS);
            velocityEastMps = ContactResponse.slide(velocityEastMps, friction, TICK_SECONDS);
        }
        if (blockedVertically) {
            if (velocityDownMps > 0.0) {
                // Touching down a little fast hops before it settles; a controlled
                // descent barely does.
                velocityDownMps = ContactResponse.rebound(velocityDownMps, restitution);
            } else {
                velocityDownMps = 0.0;
            }
            fallSpeedMps = 0.0;
        }
        // Coming to rest is "the world is holding it up", not "the altitude reads
        // zero": a vehicle parked on a block that sits above the origin plane is just
        // as landed, and reporting it as airborne would have the monitoring side
        // waiting for a landing that already happened.
        if (supported && blockedVertically) {
            if (down >= -GROUND_EPSILON_M) {
                downM = 0.0;
            }
            if (flightPhase == FlightPhase.FALLING) {
                flightPhase = FlightPhase.LANDED;
            } else if (flightPhase == FlightPhase.LANDING) {
                armed = false;
                flightPhase = FlightPhase.LANDED;
            }
        }
    }

    /** True while something solid is holding the vehicle up. */
    public boolean supportedByWorld() {
        return supportedByWorld;
    }

    /** True when an impact has destroyed the vehicle and it has not been reset. */
    public boolean safetyLatched() {
        return safetyLatched;
    }

    /** Clears the crash latch; the operator's reset does this. */
    public void clearSafetyLatch() {
        safetyLatched = false;
    }

    /**
     * An impact hard enough to destroy the flight: the rotors are done, so the vehicle
     * is disarmed (which means it falls) and the event is latched for the monitoring
     * side. Nothing about the position is changed here - falling from where it hit is
     * what a crash looks like.
     */
    public void crash() {
        safetyLatched = true;
        armed = false;
        clearSetpoint();
        if (downM < -GROUND_EPSILON_M) {
            flightPhase = FlightPhase.FALLING;
            fallSpeedMps = 0.0;
        } else {
            downM = 0.0;
            flightPhase = FlightPhase.LANDED;
        }
    }

    private void applyChannel(
        Channel channel,
        LocalSetpoint.Axis north,
        LocalSetpoint.Axis east,
        LocalSetpoint.Axis down
    ) {
        channel.set(AXIS_NORTH, north.positionSet(), north.position());
        channel.set(AXIS_EAST, east.positionSet(), east.position());
        channel.set(AXIS_DOWN, down.positionSet(), down.position());
    }

    private void applyVelocityChannel(LocalSetpoint setpoint) {
        velocity.set(AXIS_NORTH, setpoint.north().velocitySet(), setpoint.north().velocity());
        velocity.set(AXIS_EAST, setpoint.east().velocitySet(), setpoint.east().velocity());
        velocity.set(AXIS_DOWN, setpoint.down().velocitySet(), setpoint.down().velocity());
    }

    private void applyAccelerationChannel(LocalSetpoint setpoint) {
        acceleration.set(
            AXIS_NORTH, setpoint.north().accelerationSet(), setpoint.north().acceleration());
        acceleration.set(
            AXIS_EAST, setpoint.east().accelerationSet(), setpoint.east().acceleration());
        acceleration.set(
            AXIS_DOWN, setpoint.down().accelerationSet(), setpoint.down().acceleration());
    }

    private void clearSetpoint() {
        position.clear();
        velocity.clear();
        acceleration.clear();
        java.util.Arrays.fill(actuatedVelocityMps, 0.0);
        java.util.Arrays.fill(achievedAccelerationMps2, 0.0);
        yawSet = false;
        yawRateSet = false;
        setpointActive = false;
    }

    private static boolean finite(LocalSetpoint setpoint) {
        return setpoint.north().finite() && setpoint.east().finite() && setpoint.down().finite()
            && (!setpoint.yawSet() || Double.isFinite(setpoint.yawRad()))
            && (!setpoint.yawRateSet() || Double.isFinite(setpoint.yawRateRadS()));
    }

    private static boolean positionInRange(LocalSetpoint setpoint) {
        if (setpoint.north().positionSet() || setpoint.east().positionSet()) {
            double north = setpoint.north().positionSet() ? setpoint.north().position() : 0.0;
            double east = setpoint.east().positionSet() ? setpoint.east().position() : 0.0;
            if (Math.hypot(north, east) > MAX_HORIZONTAL_DISTANCE_M) {
                return false;
            }
        }
        if (setpoint.down().positionSet()) {
            double down = setpoint.down().position();
            if (down > -0.1 || down < -120.0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Resolves one tick of motion from the active channels.
     *
     * <p>Where the sender commands a position, the virtual vehicle tracks it at a
     * bounded rate, exactly as it did before PVA channels existed. A commanded
     * velocity is fed forward onto that tracking; where the sender ignores the
     * position it becomes the tracked value instead, approached under an
     * acceleration limit. A commanded acceleration integrates into that velocity.
     */
    private void updateSetpointMotion() {
        boolean trackedNorth = position.active(AXIS_NORTH);
        boolean trackedEast = position.active(AXIS_EAST);
        double trackingNorth = 0.0;
        double trackingEast = 0.0;

        if (trackedNorth && trackedEast) {
            // Both horizontal axes are commanded, so the pair is one bounded speed
            // vector: limiting each axis separately would allow 1.98 m/s.
            double deltaNorth = position.value(AXIS_NORTH) - northM;
            double deltaEast = position.value(AXIS_EAST) - eastM;
            double distance = Math.hypot(deltaNorth, deltaEast);
            if (distance > POSITION_EPSILON_M) {
                double speed = approachSpeedMps(
                    distance,
                    Math.hypot(actuatedVelocityMps[AXIS_NORTH], actuatedVelocityMps[AXIS_EAST]));
                trackingNorth = deltaNorth / distance * speed;
                trackingEast = deltaEast / distance * speed;
            }
        } else if (trackedNorth) {
            double delta = position.value(AXIS_NORTH) - northM;
            trackingNorth = Math.copySign(
                approachSpeedMps(Math.abs(delta), Math.abs(actuatedVelocityMps[AXIS_NORTH])), delta);
        } else if (trackedEast) {
            double delta = position.value(AXIS_EAST) - eastM;
            trackingEast = Math.copySign(
                approachSpeedMps(Math.abs(delta), Math.abs(actuatedVelocityMps[AXIS_EAST])), delta);
        }

        double trackingDown = 0.0;
        if (position.active(AXIS_DOWN)) {
            trackingDown = trackScalar(
                downM,
                position.value(AXIS_DOWN),
                downM < position.value(AXIS_DOWN) ? vehicleModel.climbRateMps() : vehicleModel.descentRateMps()
            );
        }

        double[] demand = {
            demand(AXIS_NORTH, trackingNorth),
            demand(AXIS_EAST, trackingEast),
            demand(AXIS_DOWN, trackingDown)
        };
        boundToPlantEnvelope(demand);
        applyThrustVectorMotion(demand);

        velocityNorthMps = actuatedVelocityMps[AXIS_NORTH];
        velocityEastMps = actuatedVelocityMps[AXIS_EAST];
        velocityDownMps = actuatedVelocityMps[AXIS_DOWN];
        northM += velocityNorthMps * TICK_SECONDS;
        eastM += velocityEastMps * TICK_SECONDS;
        downM += velocityDownMps * TICK_SECONDS;
        snapOnArrival();
    }

    /**
     * The airframe's answer to what the controller asked for.
     *
     * <p>Two physical facts turn a request into a motion:
     *
     * <ul>
     *   <li>only the thrust that can be pointed sideways accelerates the vehicle, so
     *       how fast it can change speed follows from thrust, lean limit and mass;</li>
     *   <li>drag grows with the square of the speed, so it caps how fast the vehicle
     *       can travel at all - the speed the flight controller may ask for limits the
     *       request, not the world.</li>
     * </ul>
     *
     * <p>Motors take time to change thrust, so the response is first order rather than
     * instant: a step command builds up over a few tenths of a second the way a real
     * quadcopter does, instead of jumping there in one tick.
     */
    /**
     * Horizontal motion from the thrust vector.
     *
     * <p>A quadcopter has no wings and no wheels: the only way it moves sideways is by
     * leaning and pointing part of its thrust that way. So the velocity error commands a
     * lean, the rate loop reaches that lean, and the acceleration is what the lean
     * actually produces (`g * tan(tilt)`, clamped by the lean limit). Drag then resists
     * the motion as its square.
     *
     * <p>This is what makes the vehicle feel like an aircraft rather than a cursor: it
     * has to tip into a movement, it keeps drifting while it tips back, and a hard lean
     * leaves less thrust to hold altitude with.
     */
    private void applyThrustVectorMotion(double[] demand) {
        double gravity = VehicleModel.GRAVITY_MPS2;
        double responseS = Math.max(
            TICK_SECONDS,
            vehicleModel.motorTimeConstantS() + vehicleModel.attitudeTimeConstantS());
        double accelLimit = gravity * Math.tan(vehicleModel.maxTiltRad());

        // The velocity error asks for an acceleration; the lean limit says how much of
        // it can be had. Drag is fed forward, because a lean chosen from the velocity
        // error alone balances drag at some fraction of the command: the vehicle would
        // settle 15 to 30 per cent short of every speed it was asked for.
        //
        // The error is measured against where the vehicle is heading rather than where
        // it is. The lean takes attitudeTimeConstantS to build, so the speed keeps
        // climbing for that long after the command is reached - and the error, measured
        // against the speed of the moment, keeps asking for the acceleration that does
        // the climbing. That is what made the response ring past its command (measured
        // 0.50470 m/s for a 0.5 m/s command, peaking fifteen ticks in). Reading the
        // error off the speed the acceleration of the moment is about to deliver -
        // speed + attitudeTimeConstantS * acceleration - puts the lean back on the trim
        // before the command is reached instead of after it: with the response time T
        // and the attitude lag t the loop becomes t*v'' + (1 + t/T)*v' + v/T = 0, whose
        // damping ratio (1 + t/T) / (2*sqrt(t/T)) is at least one for every airframe,
        // so the command is approached from below and never passed.
        double predictS = Math.max(TICK_SECONDS, vehicleModel.attitudeTimeConstantS());
        double predictedNorth = actuatedVelocityMps[AXIS_NORTH]
            + achievedAccelerationMps2[AXIS_NORTH] * predictS;
        double predictedEast = actuatedVelocityMps[AXIS_EAST]
            + achievedAccelerationMps2[AXIS_EAST] * predictS;
        double accelNorth = clamp(
            (demand[AXIS_NORTH] - predictedNorth) / responseS
                + dragAccelerationMps2(actuatedVelocityMps[AXIS_NORTH]),
            accelLimit);
        double accelEast = clamp(
            (demand[AXIS_EAST] - predictedEast) / responseS
                + dragAccelerationMps2(actuatedVelocityMps[AXIS_EAST]),
            accelLimit);

        // Lean is a body-frame attitude: forward is nose-down, right is roll-right, and
        // the commanded acceleration has to be rotated into that frame by the yaw.
        double cosYaw = Math.cos(yawRad);
        double sinYaw = Math.sin(yawRad);
        double forwardAccel = accelNorth * cosYaw + accelEast * sinYaw;
        double rightAccel = -accelNorth * sinYaw + accelEast * cosYaw;
        steerTowardTilt(
            Math.atan(rightAccel / gravity),
            -Math.atan(forwardAccel / gravity)
        );

        // What the actual attitude produces, not what was asked for.
        double actualForward = -Math.tan(pitchRad);
        double actualRight = Math.tan(rollRad);
        double actualNorth = gravity * (actualForward * cosYaw - actualRight * sinYaw);
        double actualEast = gravity * (actualForward * sinYaw + actualRight * cosYaw);

        double speedNorth = actuatedVelocityMps[AXIS_NORTH];
        double speedEast = actuatedVelocityMps[AXIS_EAST];
        actuatedVelocityMps[AXIS_NORTH] = stepWithDrag(actualNorth, speedNorth);
        actuatedVelocityMps[AXIS_EAST] = stepWithDrag(actualEast, speedEast);
        keepHorizontalSpeedInsideTheLimit();
        // Remembered for the prediction above: what this tick really applied, after the
        // attitude and the drag had their say.
        achievedAccelerationMps2[AXIS_NORTH] = actualNorth - dragAccelerationMps2(speedNorth);
        achievedAccelerationMps2[AXIS_EAST] = actualEast - dragAccelerationMps2(speedEast);

        // A leaning vehicle has less thrust pointing up, which is why a hard run sags.
        double thrustUp = Math.cos(rollRad) * Math.cos(pitchRad);
        applyVerticalResponse(demand[AXIS_DOWN], thrustUp);
    }

    /**
     * The contract's horizontal limit is a bound, and no setpoint may carry the vehicle
     * past it.
     *
     * <p>The drag trim is what crosses it: the trim hands the step exactly the
     * acceleration the drag is about to take away, and at the limit the two do not
     * cancel to the digit. A demand of 5 m/s on both horizontal axes reached
     * 1.4000505 m/s against the 1.4 m/s limit on tick 20 - 0.0036 per cent over, which
     * is still a speed the flight controller was never allowed to ask for. Scaling the
     * pair back onto the limit keeps the envelope a bound rather than a target.
     */
    private void keepHorizontalSpeedInsideTheLimit() {
        double limit = vehicleModel.maxHorizontalSpeedMps();
        double speed = Math.hypot(
            actuatedVelocityMps[AXIS_NORTH], actuatedVelocityMps[AXIS_EAST]);
        if (speed > limit) {
            double scale = limit / speed;
            actuatedVelocityMps[AXIS_NORTH] *= scale;
            actuatedVelocityMps[AXIS_EAST] *= scale;
        }
    }

    /** The deceleration quadratic drag applies at a speed, m/s^2. */
    private double dragAccelerationMps2(double speed) {
        return vehicleModel.dragCoefficient() * speed * Math.abs(speed) / vehicleModel.massKg();
    }

    /** One tick of horizontal motion under an acceleration and quadratic drag. */
    private double stepWithDrag(double accel, double current) {
        return current + (accel - dragAccelerationMps2(current)) * TICK_SECONDS;
    }

    /** The vertical channel: thrust left over after the lean holds the vehicle up. */
    private void applyVerticalResponse(double target, double thrustFactor) {
        double mass = vehicleModel.massKg();
        double climbAccel = Math.max(
            0.0, vehicleModel.maxThrustN() * thrustFactor / mass - VehicleModel.GRAVITY_MPS2);
        double responseS = Math.max(
            TICK_SECONDS,
            vehicleModel.motorTimeConstantS() + vehicleModel.attitudeTimeConstantS());
        double current = actuatedVelocityMps[AXIS_DOWN];
        double accelLimit = target < current ? VehicleModel.GRAVITY_MPS2 : climbAccel;
        double requested = (target - current) * TICK_SECONDS / responseS;
        actuatedVelocityMps[AXIS_DOWN] = current + clamp(requested, accelLimit * TICK_SECONDS);
    }

    /** The airframe this plant is flying. */
    public VehicleModel vehicleModel() {
        return vehicleModel;
    }

    public void setVehicleModel(VehicleModel model) {
        if (model != null) {
            vehicleModel = model;
        }
    }

    private double demand(int axis, double tracking) {
        // A commanded velocity is feed-forward; a commanded acceleration is that
        // same feed-forward one tick ahead, so no channel is dropped silently.
        double feedForward = 0.0;
        if (velocity.active(axis)) {
            feedForward += velocity.value(axis);
        }
        if (acceleration.active(axis)) {
            feedForward += acceleration.value(axis) * TICK_SECONDS;
        }
        if (position.active(axis)) {
            return tracking + feedForward;
        }
        if (!velocity.active(axis) && !acceleration.active(axis)) {
            return 0.0;
        }
        double target = velocity.active(axis) ? feedForward : actuatedVelocityMps[axis] + feedForward;
        // The rate a channel may change at is the airframe's, not a separate constant:
        // limiting it twice - once here and once in the airframe response - made a
        // velocity command crawl to its speed over seconds instead of the fraction of a
        // second the motors actually take.
        double limit = (axis == AXIS_DOWN ? verticalAccelLimitMps2() : horizontalAccelLimitMps2())
            * TICK_SECONDS;
        return actuatedVelocityMps[axis] + clamp(target - actuatedVelocityMps[axis], limit);
    }

    /** How fast the motors can change horizontal speed, m/s^2. */
    private double horizontalAccelLimitMps2() {
        // A quadcopter accelerates sideways by leaning, so the limit is what the lean
        // can produce, not what the motors could do if they pointed sideways.
        return VehicleModel.GRAVITY_MPS2 * Math.tan(vehicleModel.maxTiltRad());
    }

    /** Climbing is what thrust is left over after holding the vehicle up. */
    private double verticalAccelLimitMps2() {
        return Math.max(0.0, vehicleModel.maxThrustN() / vehicleModel.massKg()
            - VehicleModel.GRAVITY_MPS2);
    }

    private static double trackScalar(double current, double target, double rateMps) {
        double delta = target - current;
        return Math.abs(delta) > POSITION_EPSILON_M ? Math.copySign(rateMps, delta) : 0.0;
    }

    /**
     * The speed to close a distance with.
     *
     * <p>A vehicle with lag cannot stop on the spot: while the attitude tips back and the
     * motors spin down it still travels about {@code speed * (motor + attitude constant)}.
     * Commanding more than the remaining distance can absorb makes the vehicle overshoot
     * and then orbit the point it was told to hold - which is exactly what the old
     * "move at the limit until you are within a centimetre" tracker did once the plant
     * became physical. So the commanded speed leaves braking room, and stops demanding
     * anything at all once the rest of the way is momentum.
     *
     * <p>This is the position tracker's own demand, and it is only asked for on axes the
     * sender commanded a position on: it is one half of the demand, the feed-forward is
     * the other, so a quiet tracker here never silences a commanded speed. What does
     * silence one is the arrival snap, which is why that one is gated on
     * {@link #positionIsTheWholeInstruction(int)}.
     */
    private double approachSpeedMps(double distance, double currentSpeed) {
        double lagS = Math.max(
            TICK_SECONDS,
            vehicleModel.motorTimeConstantS() + vehicleModel.attitudeTimeConstantS());
        double brakingDistance = currentSpeed * lagS;
        if (distance <= brakingDistance + ARRIVAL_DEADBAND_M) {
            return 0.0;
        }
        return Math.min(
            vehicleModel.maxHorizontalSpeedMps(),
            (distance - brakingDistance) / lagS);
    }

    private void boundToPlantEnvelope(double[] demand) {
        double horizontal = Math.hypot(demand[AXIS_NORTH], demand[AXIS_EAST]);
        if (horizontal > vehicleModel.maxHorizontalSpeedMps()) {
            double scale = vehicleModel.maxHorizontalSpeedMps() / horizontal;
            demand[AXIS_NORTH] *= scale;
            demand[AXIS_EAST] *= scale;
        }
        demand[AXIS_DOWN] = demand[AXIS_DOWN] > 0.0
            ? Math.min(demand[AXIS_DOWN], vehicleModel.descentRateMps())
            : Math.max(demand[AXIS_DOWN], -vehicleModel.climbRateMps());
    }

    private void snapOnArrival() {
        for (int axis = 0; axis < AXIS_COUNT; axis++) {
            if (!position.active(axis) || !positionIsTheWholeInstruction(axis)) {
                continue;
            }
            double target = position.value(axis);
            double current = position(axis);
            if (Math.abs(target - current) <= POSITION_EPSILON_M) {
                setPosition(axis, target);
            }
        }

        // The last few centimetres are held rather than chased. A real position loop has
        // a deadband for the same reason: with the attitude and motor lag in the path,
        // demanding motion inside it only produces a hunt around the point.
        if (position.active(AXIS_NORTH) && position.active(AXIS_EAST)
            && positionIsTheWholeInstruction(AXIS_NORTH)
            && positionIsTheWholeInstruction(AXIS_EAST)) {
            double deltaNorth = position.value(AXIS_NORTH) - northM;
            double deltaEast = position.value(AXIS_EAST) - eastM;
            double speed = Math.hypot(
                actuatedVelocityMps[AXIS_NORTH], actuatedVelocityMps[AXIS_EAST]);
            if (Math.hypot(deltaNorth, deltaEast) <= ARRIVAL_DEADBAND_M
                && speed <= ARRIVAL_SPEED_MPS) {
                northM = position.value(AXIS_NORTH);
                eastM = position.value(AXIS_EAST);
                setPosition(AXIS_NORTH, northM);
                setPosition(AXIS_EAST, eastM);
                actuatedVelocityMps[AXIS_NORTH] = 0.0;
                actuatedVelocityMps[AXIS_EAST] = 0.0;
                velocityNorthMps = 0.0;
                velocityEastMps = 0.0;
            }
        }
    }

    /**
     * True when the sender asked this axis to be somewhere and nothing else - no speed,
     * no acceleration to fly it there with.
     *
     * <p>Those are the only axes where arriving is the vehicle's own decision. A PVA
     * frame may command a position and a speed on the same axis, and then the position is
     * a point on a trajectory whose speed the sender is feeding forward: the main
     * project's own note on that is that a fixed point with a constant feed-forward keeps
     * being pushed by the feed-forward, so the position loop can only chase it.
     *
     * <p>Snapping the arrival there does not chase it, it silences it. Measured before
     * this gate: a frame holding the current point with a 0.5 m/s feed-forward moved the
     * vehicle 0.00000 m in twenty ticks and left it standing still at 0.0000 m/s, because
     * the per-axis snap put the position back on the target on every tick - the commanded
     * speed was read, applied, and then undone before it could carry the vehicle anywhere.
     */
    private boolean positionIsTheWholeInstruction(int axis) {
        return !velocity.active(axis) && !acceleration.active(axis);
    }

    private void updateYaw() {
        if (!yawSet) {
            if (yawRateSet) {
                yawRad = wrapToPi(yawRad + yawRateSetpointRadS * TICK_SECONDS);
                yawRateRadS = yawRateSetpointRadS;
            }
            return;
        }
        // Yaw follows the same shape as position: slew to the reference at a
        // bounded rate, clipped so it lands on the reference instead of stepping
        // past it, with a commanded yaw rate fed forward.
        double delta = wrapToPi(yawSetpointRad - yawRad);
        double tracking = Math.abs(delta) > YAW_EPSILON_RAD
            ? Math.copySign(
                Math.min(MAX_YAW_RATE_RAD_S * TICK_SECONDS, Math.abs(delta)) / TICK_SECONDS,
                delta
            )
            : 0.0;
        double feedForward = yawRateSet ? yawRateSetpointRadS : 0.0;
        double rate = clamp(tracking + feedForward, MAX_YAW_RATE_RAD_S);
        yawRad = wrapToPi(yawRad + rate * TICK_SECONDS);
        yawRateRadS = rate;
        if (Math.abs(wrapToPi(yawSetpointRad - yawRad)) <= YAW_EPSILON_RAD) {
            yawRad = wrapToPi(yawSetpointRad);
            yawRateRadS = yawRateSet ? feedForward : 0.0;
        }
    }

    /**
     * Attitude when nothing is asking for a lean: the vehicle levels itself under the
     * rate loop, which is what a pilot sees after releasing the sticks.
     *
     * <p>While a setpoint is driving the vehicle the attitude is not "how fast am I
     * going" - it is what the acceleration comes from, and
     * {@link #applyThrustVectorMotion} owns it for that reason.
     */
    private void updateAttitude() {
        if (flightPhase == FlightPhase.FLYING && armed && setpointActive) {
            return;
        }
        steerTowardTilt(0.0, 0.0);
    }

    /**
     * The rate loop: the attitude moves toward a commanded lean with a first-order
     * response, and the rates reported are the ones the integration actually used.
     */
    private void steerTowardTilt(double commandedRoll, double commandedPitch) {
        double responseS = Math.max(TICK_SECONDS, vehicleModel.attitudeTimeConstantS());
        double maxRate = maxAttitudeRateRadS();
        double previousRoll = rollRad;
        double previousPitch = pitchRad;
        rollRateRadS = clamp((commandedRoll - rollRad) / responseS, maxRate);
        pitchRateRadS = clamp((commandedPitch - pitchRad) / responseS, maxRate);
        rollRad = wrapToPi(rollRad + rollRateRadS * TICK_SECONDS);
        pitchRad = wrapToPi(pitchRad + pitchRateRadS * TICK_SECONDS);
        rollRateRadS = (rollRad - previousRoll) / TICK_SECONDS;
        pitchRateRadS = (pitchRad - previousPitch) / TICK_SECONDS;
    }

    /** How fast the attitude may move: the lean limit reached in the response time. */
    private double maxAttitudeRateRadS() {
        return vehicleModel.maxTiltRad() / Math.max(TICK_SECONDS, vehicleModel.attitudeTimeConstantS());
    }

    private double position(int axis) {
        return switch (axis) {
            case AXIS_NORTH -> northM;
            case AXIS_EAST -> eastM;
            default -> downM;
        };
    }

    private void setPosition(int axis, double value) {
        switch (axis) {
            case AXIS_NORTH -> northM = value;
            case AXIS_EAST -> eastM = value;
            default -> downM = value;
        }
    }

    private static double clamp(double value, double limit) {
        return Math.max(-limit, Math.min(limit, value));
    }

    private static double wrapToPi(double angle) {
        double wrapped = Math.IEEEremainder(angle, 2.0 * Math.PI);
        return Double.isNaN(wrapped) ? 0.0 : wrapped;
    }

    public VirtualDroneSnapshot snapshot() {
        return new VirtualDroneSnapshot(
            systemId,
            componentId,
            droneId,
            (System.nanoTime() - bootNanoTime) / 1_000_000L,
            armed,
            customMode == MavlinkProtocol.ARDUCOPTER_MODE_GUIDED,
            downM < -0.1,
            flightPhase == FlightPhase.TAKING_OFF,
            flightPhase == FlightPhase.LANDING,
            customMode,
            northM,
            eastM,
            downM,
            velocityNorthMps,
            velocityEastMps,
            velocityDownMps,
            rollRad,
            pitchRad,
            yawRad,
            rollRateRadS,
            pitchRateRadS,
            yawRateRadS,
            batteryPercent
        );
    }

    private static final int AXIS_NORTH = LocalSetpoint.AXIS_NORTH;
    private static final int AXIS_EAST = LocalSetpoint.AXIS_EAST;
    private static final int AXIS_DOWN = LocalSetpoint.AXIS_DOWN;

    /** Per-axis commanded values of one PVA channel. */
    private static final class Channel {
        private final boolean[] active = new boolean[AXIS_COUNT];
        private final double[] value = new double[AXIS_COUNT];

        void set(int axis, boolean isActive, double command) {
            active[axis] = isActive;
            value[axis] = isActive ? command : 0.0;
        }

        void clear() {
            java.util.Arrays.fill(active, false);
            java.util.Arrays.fill(value, 0.0);
        }

        boolean active(int axis) {
            return active[axis];
        }

        double value(int axis) {
            return value[axis];
        }
    }

    private enum FlightPhase {
        LANDED,
        TAKING_OFF,
        FLYING,
        LANDING,
        /** Disarmed in the air: no thrust, falling to the ground. */
        FALLING
    }
}
