package com.vltbr.minidrone.sim;

import com.vltbr.minidrone.mavlink.MavlinkProtocol;

public final class VirtualDroneState {
    private static final double TICK_SECONDS = 0.05;
    private static final double HORIZONTAL_SPEED_MPS = 1.4;
    private static final double CLIMB_RATE_MPS = 0.8;
    private static final double DESCENT_RATE_MPS = 0.6;
    private static final double HORIZONTAL_ACCELERATION_MPS2 = 2.0;
    private static final double VERTICAL_ACCELERATION_MPS2 = 1.0;
    private static final double MAX_YAW_RATE_RAD_S = 1.5;
    private static final double GROUND_EPSILON_M = 0.01;
    private static final double POSITION_EPSILON_M = 0.01;
    private static final double YAW_EPSILON_RAD = 0.001;
    private static final double MAX_HORIZONTAL_DISTANCE_M = 120.0;
    private static final double MAX_TILT_RAD = Math.toRadians(12.0);
    private static final double ATTITUDE_RESPONSE = 0.3;
    private static final int AXIS_COUNT = 3;

    private final int systemId;
    private final int componentId;
    private final String droneId;
    private final long bootNanoTime = System.nanoTime();
    private final Channel position = new Channel();
    private final Channel velocity = new Channel();
    private final Channel acceleration = new Channel();
    private final double[] actuatedVelocityMps = new double[AXIS_COUNT];
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

        if (flightPhase == FlightPhase.TAKING_OFF) {
            velocityDownMps = -CLIMB_RATE_MPS;
            double altitudeTarget = position.value(AXIS_DOWN);
            downM = Math.max(altitudeTarget, downM + velocityDownMps * TICK_SECONDS);
            if (downM <= altitudeTarget + GROUND_EPSILON_M) {
                downM = altitudeTarget;
                velocityDownMps = 0.0;
                flightPhase = FlightPhase.FLYING;
            }
        } else if (flightPhase == FlightPhase.LANDING) {
            velocityDownMps = DESCENT_RATE_MPS;
            downM = Math.min(0.0, downM + velocityDownMps * TICK_SECONDS);
            if (downM >= -GROUND_EPSILON_M) {
                downM = 0.0;
                velocityDownMps = 0.0;
                armed = false;
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
            flightPhase = downM < -GROUND_EPSILON_M ? FlightPhase.FLYING : FlightPhase.LANDED;
        }
        return true;
    }

    public boolean takeoff(double altitudeM) {
        if (!armed || customMode != MavlinkProtocol.ARDUCOPTER_MODE_GUIDED
            || !Double.isFinite(altitudeM) || altitudeM < 0.3 || altitudeM > 120.0) {
            return false;
        }
        clearSetpoint();
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
                double step = Math.min(HORIZONTAL_SPEED_MPS * TICK_SECONDS, distance);
                trackingNorth = deltaNorth / distance * step / TICK_SECONDS;
                trackingEast = deltaEast / distance * step / TICK_SECONDS;
            }
        } else if (trackedNorth) {
            trackingNorth = trackScalar(northM, position.value(AXIS_NORTH), HORIZONTAL_SPEED_MPS);
        } else if (trackedEast) {
            trackingEast = trackScalar(eastM, position.value(AXIS_EAST), HORIZONTAL_SPEED_MPS);
        }

        double trackingDown = 0.0;
        if (position.active(AXIS_DOWN)) {
            trackingDown = trackScalar(
                downM,
                position.value(AXIS_DOWN),
                downM < position.value(AXIS_DOWN) ? CLIMB_RATE_MPS : DESCENT_RATE_MPS
            );
        }

        double[] demand = {
            demand(AXIS_NORTH, trackingNorth),
            demand(AXIS_EAST, trackingEast),
            demand(AXIS_DOWN, trackingDown)
        };
        boundToPlantEnvelope(demand);

        velocityNorthMps = demand[AXIS_NORTH];
        velocityEastMps = demand[AXIS_EAST];
        velocityDownMps = demand[AXIS_DOWN];
        northM += velocityNorthMps * TICK_SECONDS;
        eastM += velocityEastMps * TICK_SECONDS;
        downM += velocityDownMps * TICK_SECONDS;
        System.arraycopy(demand, 0, actuatedVelocityMps, 0, AXIS_COUNT);
        snapOnArrival();
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
        double limit = (axis == AXIS_DOWN ? VERTICAL_ACCELERATION_MPS2 : HORIZONTAL_ACCELERATION_MPS2)
            * TICK_SECONDS;
        return actuatedVelocityMps[axis] + clamp(target - actuatedVelocityMps[axis], limit);
    }

    private static double trackScalar(double current, double target, double rateMps) {
        double delta = target - current;
        return Math.abs(delta) > POSITION_EPSILON_M ? Math.copySign(rateMps, delta) : 0.0;
    }

    private static void boundToPlantEnvelope(double[] demand) {
        double horizontal = Math.hypot(demand[AXIS_NORTH], demand[AXIS_EAST]);
        if (horizontal > HORIZONTAL_SPEED_MPS) {
            double scale = HORIZONTAL_SPEED_MPS / horizontal;
            demand[AXIS_NORTH] *= scale;
            demand[AXIS_EAST] *= scale;
        }
        demand[AXIS_DOWN] = demand[AXIS_DOWN] > 0.0
            ? Math.min(demand[AXIS_DOWN], DESCENT_RATE_MPS)
            : Math.max(demand[AXIS_DOWN], -CLIMB_RATE_MPS);
    }

    private void snapOnArrival() {
        for (int axis = 0; axis < AXIS_COUNT; axis++) {
            if (!position.active(axis)) {
                continue;
            }
            double target = position.value(axis);
            double current = position(axis);
            if (Math.abs(target - current) <= POSITION_EPSILON_M) {
                setPosition(axis, target);
            }
        }
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

    private void updateAttitude() {
        // Tilt is a body-frame attitude, so the NED velocity is rotated into the
        // body frame first: a vehicle yawed 90 degrees while flying north shows
        // roll, not pitch.
        double cosYaw = Math.cos(yawRad);
        double sinYaw = Math.sin(yawRad);
        double bodyForward = velocityNorthMps * cosYaw + velocityEastMps * sinYaw;
        double bodyRight = -velocityNorthMps * sinYaw + velocityEastMps * cosYaw;
        double desiredPitch = -MAX_TILT_RAD * bodyForward / HORIZONTAL_SPEED_MPS;
        double desiredRoll = MAX_TILT_RAD * bodyRight / HORIZONTAL_SPEED_MPS;
        double previousRoll = rollRad;
        double previousPitch = pitchRad;
        rollRad += (desiredRoll - rollRad) * ATTITUDE_RESPONSE;
        pitchRad += (desiredPitch - pitchRad) * ATTITUDE_RESPONSE;
        rollRateRadS = (rollRad - previousRoll) / TICK_SECONDS;
        pitchRateRadS = (pitchRad - previousPitch) / TICK_SECONDS;
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
        LANDING
    }
}
