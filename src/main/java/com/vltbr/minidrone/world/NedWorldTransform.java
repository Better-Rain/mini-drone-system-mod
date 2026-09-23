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
        this.metresPerBlock = 1.0;
    }

    /**
     * Metres per block: how far one Minecraft block is said to be.
     *
     * <p>The simulation speaks SI - the vehicle model, the speed limits and the monitoring
     * system are all in metres - while the world speaks blocks. This is the single place
     * they meet: NED offsets are divided by the scale on the way out to the world, so at a
     * scale of 0.25 the same 1.4 m/s flight covers four times as many blocks, and a
     * 49-block arena reads as 12.25 m instead of 49 m. At 1.0 - the default, and what every
     * existing setup uses - nothing changes.
     */
    private final double metresPerBlock;

    public NedWorldTransform(
        double originX, double originY, double originZ, double metresPerBlock
    ) {
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.metresPerBlock = metresPerBlock > 0.0 && Double.isFinite(metresPerBlock)
            ? metresPerBlock
            : 1.0;
    }

    public double metresPerBlock() {
        return metresPerBlock;
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
        // Metres to blocks: the world is where the vehicle is actually simulated, so this
        // is where the scale is applied.
        double eastBlocks = eastM / metresPerBlock;
        double downBlocks = downM / metresPerBlock;
        double northBlocks = northM / metresPerBlock;
        return new WorldPose(
            originX - eastBlocks,
            originY - downBlocks,
            originZ - northBlocks,
            wrapDegrees(180.0 - Math.toDegrees(yawRad)),
            // Pitch keeps its own sign. The scene frame is the documented mirror of NED
            // (world x = -east, z = -north), and mirroring turns a nose-down attitude into a
            // nose-down attitude: only the *heading* comes back as 180 - yaw. Negating this
            // rendered a vehicle accelerating forward as one pitching up - the monitoring view
            // and the game showed opposite attitudes for the same aircraft.
            (float) Math.toDegrees(pitchRad),
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
