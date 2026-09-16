package com.vltbr.minidrone.world;

/**
 * How far one Minecraft block is said to be, in metres.
 *
 * <p>The simulation speaks SI - the vehicle model, the speed limits, the crash thresholds
 * and the monitoring system are all in metres - while the world speaks blocks. Something
 * has to say how the two relate, and this is that one place.
 *
 * <p>At the default of 1.0 nothing changes: a 13-block arena is 13 m and the vehicle is
 * as wide as its bounding box says. At 0.25 the same arena reads as 3.25 m and a 49-block
 * arena as 12.25 m, so a vehicle that occupies under a block of world reads as the 0.22 m
 * nano quadcopter it is supposed to be, while its 1.4 m/s speed limit stays exactly what
 * the backend contract says. Build the arena in blocks to suit the room you want to
 * imagine; use this to say how big that room is.
 *
 * <p>Set with {@code -Dmini_drone.world.metres_per_block=0.25}. Anything that is not a
 * positive finite number falls back to 1.0 rather than poisoning every coordinate.
 */
public final class WorldScale {
    /** The property an operator sets; kept next to the vehicle properties in the docs. */
    public static final String PROPERTY = "mini_drone.world.metres_per_block";

    public static final double DEFAULT_METRES_PER_BLOCK = 1.0;

    private WorldScale() {
    }

    public static double metresPerBlock() {
        return fromProperties(System.getProperties());
    }

    /** Reads the scale from a property set, so it can be tested without the JVM's own. */
    public static double fromProperties(java.util.Properties properties) {
        String raw = properties == null ? null : properties.getProperty(PROPERTY);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_METRES_PER_BLOCK;
        }
        try {
            double value = Double.parseDouble(raw.trim());
            return value > 0.0 && Double.isFinite(value) ? value : DEFAULT_METRES_PER_BLOCK;
        } catch (NumberFormatException exception) {
            return DEFAULT_METRES_PER_BLOCK;
        }
    }
}
