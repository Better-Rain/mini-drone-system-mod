package com.vltbr.minidrone.sim;

/**
 * One Local NED setpoint for the virtual flight controller, expressed as the
 * channels the sender actually commanded.
 *
 * <p>The main project drives the virtual vehicle through PVA setpoints, so a
 * frame may carry any combination of position, velocity, acceleration, yaw and
 * yaw rate per axis. A setpoint replaces the previous one: an axis the sender
 * did not command has no active channel, so the vehicle holds that axis instead
 * of continuing an earlier command. A frame that commands nothing at all is not
 * a valid setpoint and is refused.
 */
public record LocalSetpoint(
    Axis north,
    Axis east,
    Axis down,
    boolean yawSet,
    double yawRad,
    boolean yawRateSet,
    double yawRateRadS
) {
    /** Named axis indices, shared with {@link VirtualDroneState}. */
    public static final int AXIS_NORTH = 0;
    public static final int AXIS_EAST = 1;
    public static final int AXIS_DOWN = 2;

    public Axis axis(int index) {
        return switch (index) {
            case AXIS_NORTH -> north;
            case AXIS_EAST -> east;
            case AXIS_DOWN -> down;
            default -> throw new IllegalArgumentException("unknown axis index " + index);
        };
    }

    public boolean anyCommanded() {
        return north.anyCommanded() || east.anyCommanded() || down.anyCommanded()
            || yawSet || yawRateSet;
    }

    /** Builds a position-only setpoint, the shape used before PVA channels existed. */
    public static LocalSetpoint positionOnly(double northM, double eastM, double downM) {
        return new LocalSetpoint(
            Axis.position(northM),
            Axis.position(eastM),
            Axis.position(downM),
            false, 0.0,
            false, 0.0
        );
    }

    /**
     * One Local NED axis. Velocities are metres per second and accelerations
     * metres per second squared; a channel that is not set is ignored.
     */
    public record Axis(
        boolean positionSet,
        double position,
        boolean velocitySet,
        double velocity,
        boolean accelerationSet,
        double acceleration
    ) {
        public static Axis position(double position) {
            return new Axis(true, position, false, 0.0, false, 0.0);
        }

        public static Axis unset() {
            return new Axis(false, 0.0, false, 0.0, false, 0.0);
        }

        public boolean anyCommanded() {
            return positionSet || velocitySet || accelerationSet;
        }

        public boolean finite() {
            return (!positionSet || Double.isFinite(position))
                && (!velocitySet || Double.isFinite(velocity))
                && (!accelerationSet || Double.isFinite(acceleration));
        }
    }
}
