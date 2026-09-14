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

    boolean setMode(int customMode);

    boolean setArmed(boolean armed);

    boolean takeoff(double altitudeM);

    boolean land();

    boolean setLocalSetpoint(LocalSetpoint setpoint);
}
