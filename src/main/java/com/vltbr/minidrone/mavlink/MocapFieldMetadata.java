package com.vltbr.minidrone.mavlink;

/**
 * Training-field metadata advertised in the motion-capture health beacon.
 *
 * <p>The main project only trusts a field that is centred on the world origin of
 * the controller's local NED frame, so this record doubles as the statement that
 * the arena centre <em>is</em> that origin (see {@link
 * com.vltbr.minidrone.world.NedWorldTransform}). The size is in metres, and one
 * Minecraft block is one metre.
 *
 * <p>A null value means "this source has no field to publish", which is what the
 * backend expects from a source whose flying volume is not a known arena.
 */
public record MocapFieldMetadata(
    double widthM,
    double depthM,
    boolean centeredAtWorldOrigin,
    int protocolVersion,
    long updatedWallTimeUnixUs,
    double centerOffsetXM,
    double centerOffsetZM
) {
    /** Version of the field-metadata contract this mod publishes. */
    public static final int CURRENT_PROTOCOL_VERSION = 1;

    /** A field whose centre is the world origin, which needs no offset. */
    public MocapFieldMetadata(
        double widthM,
        double depthM,
        boolean centeredAtWorldOrigin,
        int protocolVersion,
        long updatedWallTimeUnixUs
    ) {
        this(widthM, depthM, centeredAtWorldOrigin, protocolVersion, updatedWallTimeUnixUs, 0.0, 0.0);
    }

    public MocapFieldMetadata {
        if (!Double.isFinite(widthM) || !Double.isFinite(depthM) || widthM <= 0.0 || depthM <= 0.0) {
            throw new IllegalArgumentException("field size must be finite and positive");
        }
        if (protocolVersion <= 0) {
            throw new IllegalArgumentException("field protocol version must be positive");
        }
        if (!Double.isFinite(centerOffsetXM) || !Double.isFinite(centerOffsetZM)) {
            throw new IllegalArgumentException("field centre offset must be finite");
        }
        // The monitoring side draws a field around the world origin when this flag
        // is set, so a non-zero offset has to come with the flag cleared:
        // otherwise it would draw the ground in the wrong place and the vehicle's
        // position relative to it would silently be wrong again.
        if (centeredAtWorldOrigin && (centerOffsetXM != 0.0 || centerOffsetZM != 0.0)) {
            throw new IllegalArgumentException(
                "a field centred at the world origin cannot also carry a centre offset");
        }
    }

    /** True when the field centre is not the origin and the offset matters. */
    public boolean hasCenterOffset() {
        return centerOffsetXM != 0.0 || centerOffsetZM != 0.0;
    }
}
