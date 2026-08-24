package com.vltbr.minidrone.mavlink;

import java.util.Arrays;

public record MavlinkOutboundMessage(int messageId, byte[] payload) {
    public MavlinkOutboundMessage {
        payload = Arrays.copyOf(payload, payload.length);
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
