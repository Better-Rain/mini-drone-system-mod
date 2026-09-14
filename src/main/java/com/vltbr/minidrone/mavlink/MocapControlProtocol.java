package com.vltbr.minidrone.mavlink;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Wire contract for the backend's loopback virtual motion-capture discovery probe. */
final class MocapControlProtocol {
    static final String STATUS_REQUEST = "VLT_RELAY_STATUS_V1";
    static final String RECONNECT_REQUEST = "VLT_RELAY_RECONNECT_V1";
    // The backend holds position forwarding when the last vehicle link is
    // disconnected, and resumes it when a link is taken back. A relay pauses its
    // multicast; this source stops accepting new position setpoints.
    static final String HOLD_REQUEST = "VLT_RELAY_HOLD_FORWARDING_V1";
    static final String RESUME_REQUEST = "VLT_RELAY_RESUME_FORWARDING_V1";

    static final String ACTION_STATUS = "status";
    static final String ACTION_RECONNECT = "reconnect";
    static final String ACTION_HOLD = "hold";
    static final String ACTION_RESUME = "resume";

    private MocapControlProtocol() {}

    /** A parsed probe: the action to echo back and what it does to the hold. */
    record Command(String action, ForwardingHold.Change holdChange) {}

    static Optional<Command> commandFor(byte[] request, int offset, int length) {
        int end = offset + length;
        while (end > offset && (request[end - 1] == '\r' || request[end - 1] == '\n')) {
            end--;
        }

        String command = new String(request, offset, end - offset, StandardCharsets.US_ASCII);
        return switch (command) {
            case STATUS_REQUEST -> Optional.of(new Command(
                ACTION_STATUS, ForwardingHold.Change.UNCHANGED));
            // A reconnect also clears the hold, matching the relay.
            case RECONNECT_REQUEST -> Optional.of(new Command(
                ACTION_RECONNECT, ForwardingHold.Change.RESUME));
            case HOLD_REQUEST -> Optional.of(new Command(
                ACTION_HOLD, ForwardingHold.Change.HOLD));
            case RESUME_REQUEST -> Optional.of(new Command(
                ACTION_RESUME, ForwardingHold.Change.RESUME));
            default -> Optional.empty();
        };
    }

    static byte[] response(Command command, boolean forwardingHeld) {
        return ("{\"schema\":\"mocap_relay_control_v1\",\"ok\":true,"
            + "\"action\":\"" + command.action() + "\","
            + "\"message\":\"virtual motion-capture source is online\","
            + "\"source_packet_age_ms\":0,\"safety_latched\":false,"
            + "\"forwarding_held\":" + forwardingHeld + "}")
            .getBytes(StandardCharsets.UTF_8);
    }
}
