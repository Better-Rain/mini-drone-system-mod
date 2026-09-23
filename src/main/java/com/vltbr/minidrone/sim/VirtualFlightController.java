package com.vltbr.minidrone.sim;

/**
 * The operations {@link com.vltbr.minidrone.mavlink.VirtualAutopilot} needs from
 * the flight model.
 *
 * <p>The autopilot is the inbound command path, and it only ever calls these six
 * methods. Depending on this instead of {@code VirtualDroneManager} keeps the
 * command path free of game classes, so a test can drive real decoded frames
 * into the real decoder and the real autopilot without a running server.
 */
public interface VirtualFlightController {
    VirtualDroneSnapshot snapshot();

    /**
     * The vehicle a MAVLink frame addresses, or null when this world does not fly it.
     *
     * <p>A MAVLink frame carries a system id and nothing else about who it is for, so with
     * more than one aircraft the command path has to resolve the frame to <em>that</em>
     * vehicle. Null is a real answer - a frame for a system id this world does not fly is
     * dropped rather than applied to whichever drone happens to be first - and a controller
     * returned here owns one drone's own state, so two aircraft arm, fly and land
     * independently.
     */
    VirtualFlightController vehicleForSystemId(int systemId);

    boolean setMode(int customMode);

    boolean setArmed(boolean armed);

    boolean takeoff(double altitudeM);

    boolean land();

    boolean setLocalSetpoint(LocalSetpoint setpoint);
}
