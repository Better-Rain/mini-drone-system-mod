package com.vltbr.minidrone.mavlink;

import java.util.Arrays;

/**
 * One frame this world is about to send, and which vehicle it is about to speak as.
 *
 * <p>Telemetry is built per drone already, but an answer to a command is built by the
 * autopilot, which has to say which aircraft it is answering for: an acknowledgement for the
 * second drone that went out under the first drone's system id would look to the monitoring
 * side like a vehicle answering for another one.
 */
public record MavlinkOutboundMessage(int systemId, int componentId, int messageId, byte[] payload) {
    public MavlinkOutboundMessage {
        payload = Arrays.copyOf(payload, payload.length);
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
