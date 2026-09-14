package com.vltbr.minidrone.mavlink;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Wire contract for the backend's loopback virtual motion-capture discovery probe. */
final class MocapControlProtocol {
    static final String STATUS_REQUEST = "VLT_RELAY_STATUS_V1";
    static final String RECONNECT_REQUEST = "VLT_RELAY_RECONNECT_V1";

    private MocapControlProtocol() {}

    static Optional<byte[]> responseFor(byte[] request, int offset, int length) {
        int end = offset + length;
        while (end > offset && (request[end - 1] == '\r' || request[end - 1] == '\n')) {
            end--;
        }

        String command = new String(request, offset, end - offset, StandardCharsets.US_ASCII);
        String action = switch (command) {
            case STATUS_REQUEST -> "status";
            case RECONNECT_REQUEST -> "reconnect";
            default -> null;
        };
        if (action == null) {
            return Optional.empty();
        }
        return Optional.of(responseForAction(action).getBytes(StandardCharsets.UTF_8));
    }

    private static String responseForAction(String action) {
        return "{\"schema\":\"mocap_relay_control_v1\",\"ok\":true,"
            + "\"action\":\"" + action + "\","
            + "\"message\":\"virtual motion-capture source is online\","
            + "\"source_packet_age_ms\":0,\"safety_latched\":false}";
    }
}
