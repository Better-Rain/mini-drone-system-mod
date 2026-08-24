package com.vltbr.minidrone.sim;

public record VirtualDroneSnapshot(
    int systemId,
    int componentId,
    String droneId,
    long timeBootMs,
    boolean armed,
    boolean guided,
    boolean airborne,
    boolean takingOff,
    boolean landing,
    int customMode,
    double northM,
    double eastM,
    double downM,
    double velocityNorthMps,
    double velocityEastMps,
    double velocityDownMps,
    double rollRad,
    double pitchRad,
    double yawRad,
    double rollRateRadS,
    double pitchRateRadS,
    double yawRateRadS,
    double batteryPercent
) {}
