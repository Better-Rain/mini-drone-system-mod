package com.vltbr.minidrone.mavlink;

/**
 * Where a virtual autopilot reports what it did.
 *
 * <p>The command path is deliberately free of game classes so a test can drive real decoded
 * frames through it. Both loggers available in the main source - the mod's own and slf4j -
 * are unreachable from a plain JVM test, so the sink is a parameter: the transport passes the
 * mod's logger, and a test passes {@link #SILENT} (or collects the lines and asserts on them).
 */
@FunctionalInterface
public interface AutopilotLog {
    /** `level` is one of {@code debug}, {@code info}, {@code warn}. */
    void log(String level, String message);

    AutopilotLog SILENT = (level, message) -> { };
}
