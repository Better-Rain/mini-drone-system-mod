package com.vltbr.minidrone.world;

import com.vltbr.minidrone.sim.VirtualDroneSnapshot;

public final class NedWorldTransform {
    private final double originX;
    private final double originY;
    private final double originZ;

    public NedWorldTransform(double originX, double originY, double originZ) {
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
    }

    public double originX() {
        return originX;
    }

    public double originY() {
        return originY;
    }

    public double originZ() {
        return originZ;
    }

    public WorldPose toWorldPose(VirtualDroneSnapshot snapshot) {
        return toWorldPose(
            snapshot.northM(),
            snapshot.eastM(),
            snapshot.downM(),
            snapshot.rollRad(),
            snapshot.pitchRad(),
            snapshot.yawRad()
        );
    }

    public WorldPose toWorldPose(
        double northM,
        double eastM,
        double downM,
        double rollRad,
        double pitchRad,
        double yawRad
    ) {
        return new WorldPose(
            originX - eastM,
            originY - downM,
            originZ - northM,
            wrapDegrees(180.0 - Math.toDegrees(yawRad)),
            (float) -Math.toDegrees(pitchRad),
            (float) Math.toDegrees(rollRad)
        );
    }

    static float wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0;
        if (wrapped >= 180.0) {
            wrapped -= 360.0;
        }
        if (wrapped < -180.0) {
            wrapped += 360.0;
        }
        return (float) wrapped;
    }
}
