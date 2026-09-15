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
    long updatedWallTimeUnixUs
) {
    /** Version of the field-metadata contract this mod publishes. */
    public static final int CURRENT_PROTOCOL_VERSION = 1;

    public MocapFieldMetadata {
        if (!Double.isFinite(widthM) || !Double.isFinite(depthM) || widthM <= 0.0 || depthM <= 0.0) {
            throw new IllegalArgumentException("field size must be finite and positive");
        }
        if (protocolVersion <= 0) {
            throw new IllegalArgumentException("field protocol version must be positive");
        }
    }
}
