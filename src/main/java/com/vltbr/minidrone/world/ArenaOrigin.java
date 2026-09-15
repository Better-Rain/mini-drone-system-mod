package com.vltbr.minidrone.world;

/**
 * Where the virtual world origin sits.
 *
 * <p>The main project draws its field centred on the local NED origin, so the two
 * only agree when the arena centre <em>is</em> that origin. With a training arena
 * the origin is therefore the centre of its landing pad:
 *
 * <ul>
 *   <li>X/Z at the block centre of the pad block, which is the geometric centre of
 *       the square footprint the beacon advertises;</li>
 *   <li>Y one block above the surface layer ({@code topY}), which is where a
 *       resting drone's NED down = 0 sits.</li>
 * </ul>
 *
 * <p>Without an arena there is nothing to be centred on, and the caller falls back
 * to spawning in front of the player.
 *
 * <p>Deliberately free of Minecraft world classes: the origin rule is arithmetic
 * that the unit tests pin down, not something that needs a live server.
 */
public final class ArenaOrigin {
    /** Height above the surface block layer where a resting drone's origin sits. */
    public static final double PAD_SURFACE_OFFSET_M = 1.0;

    private ArenaOrigin() {
    }

    /** The NED origin for an arena whose surface layer is at {@code topY}. */
    public static NedWorldTransform centeredTransform(int centerX, int topY, int centerZ) {
        return new NedWorldTransform(
            centerX + 0.5,
            topY + PAD_SURFACE_OFFSET_M,
            centerZ + 0.5
        );
    }
}
