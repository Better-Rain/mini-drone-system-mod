package com.vltbr.minidrone.sim;

/**
 * One drone of a fleet, addressed the way MAVLink addresses it.
 *
 * <p>The autopilot drives vehicles through {@link VirtualFlightController}; a fleet needs
 * that contract per drone rather than per world, because a frame carries a system id and
 * nothing else about who it is for. Wrapping a single {@link VirtualDroneState} here is what
 * makes system 55 reach the second drone and leave the first one alone.
 *
 * <p>{@code onChanged} exists because the world republishes what it flies after a command:
 * a caller that owns a published view passes the republish here, and a test passes nothing.
 */
public final class VirtualDroneHandle implements VirtualFlightController {
    private final VirtualDroneState drone;
    private final Runnable onChanged;

    public VirtualDroneHandle(VirtualDroneState drone, Runnable onChanged) {
        if (drone == null) {
            throw new IllegalArgumentException("a drone handle needs a drone");
        }
        this.drone = drone;
        this.onChanged = onChanged == null ? () -> { } : onChanged;
    }

    /** The drone this handle speaks for. */
    public VirtualDroneState drone() {
        return drone;
    }

    @Override
    public VirtualDroneSnapshot snapshot() {
        return drone.snapshot();
    }

    @Override
    public VirtualFlightController vehicleForSystemId(int systemId) {
        return systemId == drone.snapshot().systemId() ? this : null;
    }

    @Override
    public boolean setMode(int customMode) {
        boolean accepted = drone.setMode(customMode);
        onChanged.run();
        return accepted;
    }

    @Override
    public boolean setArmed(boolean armed) {
        boolean accepted = drone.setArmed(armed);
        onChanged.run();
        return accepted;
    }

    @Override
    public boolean takeoff(double altitudeM) {
        boolean accepted = drone.takeoff(altitudeM);
        onChanged.run();
        return accepted;
    }

    @Override
    public boolean land() {
        boolean accepted = drone.land();
        onChanged.run();
        return accepted;
    }

    @Override
    public boolean setLocalSetpoint(LocalSetpoint setpoint) {
        boolean accepted = drone.setLocalSetpoint(setpoint);
        onChanged.run();
        return accepted;
    }
}
