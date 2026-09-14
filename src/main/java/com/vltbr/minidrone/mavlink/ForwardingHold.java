package com.vltbr.minidrone.mavlink;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Whether the backend has asked this source to stop forwarding positions.
 *
 * <p>The backend sends {@code VLT_RELAY_HOLD_FORWARDING_V1} once the last
 * vehicle link is disconnected and the vehicle is disarmed, and
 * {@code VLT_RELAY_RESUME_FORWARDING_V1} when a link is taken back. A relay
 * answers by pausing its multicast; the virtual source is itself the flight
 * controller, so the faithful equivalent is to stop accepting new position
 * setpoints. The vehicle keeps whatever it already accepted, exactly as a real
 * flight controller keeps flying to its last setpoint.
 *
 * <p>Only position and velocity setpoints are gated. Mode, arm and land commands
 * stay available, so an operator can always put the vehicle down.
 */
public final class ForwardingHold {
    private static final String BACKEND_REQUEST_REASON = "backend_request";

    private final AtomicBoolean held = new AtomicBoolean(false);

    /** What a control command asks the hold to become. */
    public enum Change {
        UNCHANGED,
        HOLD,
        RESUME
    }

    /** Applies a control command and returns the state that now applies. */
    public boolean apply(Change change) {
        switch (change) {
            case HOLD -> held.set(true);
            case RESUME -> held.set(false);
            default -> {
                // A status probe only reports the current state.
            }
        }
        return held.get();
    }

    public boolean held() {
        return held.get();
    }

    public String reason() {
        return held.get() ? BACKEND_REQUEST_REASON : "";
    }

    /** New position and velocity setpoints are accepted only while forwarding runs. */
    public boolean acceptsSetpoints() {
        return !held.get();
    }
}
