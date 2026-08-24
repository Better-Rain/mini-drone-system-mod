package com.vltbr.minidrone.sim;

import com.vltbr.minidrone.mavlink.MavlinkProtocol;

public final class VirtualDroneState {
    private static final double TICK_SECONDS = 0.05;
    private static final double HORIZONTAL_SPEED_MPS = 1.4;
    private static final double CLIMB_RATE_MPS = 0.8;
    private static final double DESCENT_RATE_MPS = 0.6;
    private static final double GROUND_EPSILON_M = 0.01;
    private static final double POSITION_EPSILON_M = 0.01;
    private static final double MAX_HORIZONTAL_DISTANCE_M = 120.0;
    private static final double MAX_TILT_RAD = Math.toRadians(12.0);
    private static final double ATTITUDE_RESPONSE = 0.3;

    private final int systemId;
    private final int componentId;
    private final String droneId;
    private final long bootNanoTime = System.nanoTime();
    private boolean armed;
    private FlightPhase flightPhase = FlightPhase.LANDED;
    private int customMode;
    private boolean positionTargetActive;
    private double targetNorthM;
    private double targetEastM;
    private double targetDownM;
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

        if (flightPhase == FlightPhase.TAKING_OFF) {
            velocityDownMps = -CLIMB_RATE_MPS;
            downM = Math.max(targetDownM, downM + velocityDownMps * TICK_SECONDS);
            if (downM <= targetDownM + GROUND_EPSILON_M) {
                downM = targetDownM;
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
        } else if (flightPhase == FlightPhase.FLYING && armed && positionTargetActive) {
            updatePositionTarget();
        }

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
            positionTargetActive = false;
            flightPhase = downM < -GROUND_EPSILON_M ? FlightPhase.FLYING : FlightPhase.LANDED;
        }
        return true;
    }

    public boolean takeoff(double altitudeM) {
        if (!armed || customMode != MavlinkProtocol.ARDUCOPTER_MODE_GUIDED
            || !Double.isFinite(altitudeM) || altitudeM < 0.3 || altitudeM > 120.0) {
            return false;
        }
        positionTargetActive = false;
        targetNorthM = northM;
        targetEastM = eastM;
        targetDownM = -altitudeM;
        flightPhase = FlightPhase.TAKING_OFF;
        return true;
    }

    public boolean land() {
        customMode = 9;
        positionTargetActive = false;
        if (!armed && flightPhase == FlightPhase.LANDED) {
            return true;
        }
        flightPhase = FlightPhase.LANDING;
        return true;
    }

    public boolean setPositionTarget(double north, double east, double down) {
        if (!armed || customMode != MavlinkProtocol.ARDUCOPTER_MODE_GUIDED
            || (flightPhase != FlightPhase.FLYING && flightPhase != FlightPhase.TAKING_OFF)
            || !Double.isFinite(north) || !Double.isFinite(east) || !Double.isFinite(down)
            || Math.hypot(north, east) > MAX_HORIZONTAL_DISTANCE_M
            || down > -0.1 || down < -120.0) {
            return false;
        }
        targetNorthM = north;
        targetEastM = east;
        targetDownM = down;
        positionTargetActive = true;
        return true;
    }

    public boolean canResetLocalPosition() {
        return !armed && flightPhase == FlightPhase.LANDED;
    }

    public boolean resetLocalPosition() {
        if (!canResetLocalPosition()) {
            return false;
        }
        positionTargetActive = false;
        targetNorthM = 0.0;
        targetEastM = 0.0;
        targetDownM = 0.0;
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

    private void updatePositionTarget() {
        double deltaNorth = targetNorthM - northM;
        double deltaEast = targetEastM - eastM;
        double horizontalDistance = Math.hypot(deltaNorth, deltaEast);
        if (horizontalDistance > POSITION_EPSILON_M) {
            double step = Math.min(HORIZONTAL_SPEED_MPS * TICK_SECONDS, horizontalDistance);
            double ratio = step / horizontalDistance;
            double northStep = deltaNorth * ratio;
            double eastStep = deltaEast * ratio;
            northM += northStep;
            eastM += eastStep;
            velocityNorthMps = northStep / TICK_SECONDS;
            velocityEastMps = eastStep / TICK_SECONDS;
        } else {
            northM = targetNorthM;
            eastM = targetEastM;
        }

        double deltaDown = targetDownM - downM;
        if (Math.abs(deltaDown) > POSITION_EPSILON_M) {
            double rate = deltaDown < 0.0 ? CLIMB_RATE_MPS : DESCENT_RATE_MPS;
            double downStep = Math.copySign(
                Math.min(rate * TICK_SECONDS, Math.abs(deltaDown)),
                deltaDown
            );
            downM += downStep;
            velocityDownMps = downStep / TICK_SECONDS;
        } else {
            downM = targetDownM;
        }

        if (Math.hypot(targetNorthM - northM, targetEastM - eastM) <= POSITION_EPSILON_M
            && Math.abs(targetDownM - downM) <= POSITION_EPSILON_M) {
            northM = targetNorthM;
            eastM = targetEastM;
            downM = targetDownM;
            positionTargetActive = false;
        }
    }

    private void updateAttitude() {
        double desiredPitch = -MAX_TILT_RAD * velocityNorthMps / HORIZONTAL_SPEED_MPS;
        double desiredRoll = MAX_TILT_RAD * velocityEastMps / HORIZONTAL_SPEED_MPS;
        double previousRoll = rollRad;
        double previousPitch = pitchRad;
        rollRad += (desiredRoll - rollRad) * ATTITUDE_RESPONSE;
        pitchRad += (desiredPitch - pitchRad) * ATTITUDE_RESPONSE;
        rollRateRadS = (rollRad - previousRoll) / TICK_SECONDS;
        pitchRateRadS = (pitchRad - previousPitch) / TICK_SECONDS;
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

    private enum FlightPhase {
        LANDED,
        TAKING_OFF,
        FLYING,
        LANDING
    }
}
