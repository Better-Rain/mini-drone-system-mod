package com.vltbr.minidrone.mavlink;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class MavlinkPayloads {
    private MavlinkPayloads() {}

    public static ByteBuffer writer(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    public static ByteBuffer reader(byte[] payload) {
        return ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
    }
}
