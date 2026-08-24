package com.vltbr.minidrone.mavlink;

import java.util.Arrays;

public record MavlinkV1Frame(
    int sequence,
    int systemId,
    int componentId,
    int messageId,
    byte[] payload
) {
    public MavlinkV1Frame {
        payload = Arrays.copyOf(payload, payload.length);
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
