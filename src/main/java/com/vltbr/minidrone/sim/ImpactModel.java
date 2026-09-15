package com.vltbr.minidrone.sim;

/**
 * What a collision does to the vehicle.
 *
 * <p>Stopping dead is not what happens when a multirotor meets a wall or the ground:
 * the rotors keep turning, the airframe is no longer under control, and the useful
 * outcome for a simulator is that it falls. So an impact above a threshold disarms
 * the vehicle (which, by the rule the emergency stop already uses, means it falls),
 * and the impact is latched as a safety event so the monitoring side stops accepting
 * commands until the operator resets the vehicle.
 *
 * <p>The thresholds are bounded by the speeds this plant actually flies at: the
 * controlled descent is 0.6 m/s, so a touchdown below that must never be a crash, and
 * the horizontal limit is 1.4 m/s, so a full-speed run into a wall must be one. The
 * gap between them is what makes "hit the obstacle" and "land" distinguishable at all.
 *
 * <p>Pure arithmetic on purpose: this is the rule an operator argues about after a
 * flight, so it is tested rather than tuned by feel.
 */
public final class ImpactModel {
    /** Descending faster than the controlled descent rate is a hard arrival. */
    public static final double CONTROLLED_DESCENT_MPS = 0.6;
    /** Horizontal impact at or above this is a crash. */
    public static final double CRASH_HORIZONTAL_MPS = 0.8;
    /** Vertical impact at or above this is a crash. */
    public static final double CRASH_VERTICAL_MPS = 1.5;

    /** What the world did to the vehicle. */
    public enum Outcome {
        /** Nothing touched: the step was free. */
        NONE,
        /** Contact, but slow enough to be a landing rather than damage. */
        CONTACT,
        /** Fast enough to destroy the flight: disarm, fall, latch. */
        CRASH
    }

    private ImpactModel() {
    }

    /**
     * Judges an impact.
     *
     * @param horizontalSpeed the speed into the blocked horizontal direction, m/s
     * @param descentSpeed    the descent rate into solid ground, m/s, negative when
     *                        the vehicle was climbing
     */
    public static Outcome assess(double horizontalSpeed, double descentSpeed) {
        double horizontal = Math.abs(horizontalSpeed);
        double vertical = Math.max(0.0, descentSpeed);
        if (horizontal >= CRASH_HORIZONTAL_MPS || vertical >= CRASH_VERTICAL_MPS) {
            return Outcome.CRASH;
        }
        if (horizontal > 0.0 || vertical > 0.0) {
            return Outcome.CONTACT;
        }
        return Outcome.NONE;
    }

    /** A human-readable reason, for the log and the chat message. */
    public static String describe(Outcome outcome, double horizontalSpeed, double descentSpeed) {
        return String.format(
            java.util.Locale.ROOT,
            "%s (horizontal %.2f m/s, descent %.2f m/s)",
            outcome,
            Math.abs(horizontalSpeed),
            Math.max(0.0, descentSpeed)
        );
    }
}
