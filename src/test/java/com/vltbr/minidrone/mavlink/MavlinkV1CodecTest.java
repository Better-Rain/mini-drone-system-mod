package com.vltbr.minidrone.mavlink;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavlinkV1CodecTest {
    @Test
    void heartbeatMatchesMainProjectCodec() {
        byte[] payload = HexFormat.of().parseHex("000000000203010303");
        byte[] encoded = MavlinkV1Codec.encode(1, 54, 1, MavlinkProtocol.HEARTBEAT, payload);

        assertEquals("fe0901360100000000000203010303b70d", HexFormat.of().formatHex(encoded));
    }

    @Test
    void commandAckMatchesMainProjectCodec() {
        byte[] encoded = MavlinkV1Codec.encode(
            10,
            54,
            1,
            MavlinkProtocol.COMMAND_ACK,
            MavlinkMessages.commandAck(400, MavlinkProtocol.MAV_RESULT_ACCEPTED)
        );

        assertEquals("fe030a36014d900100da41", HexFormat.of().formatHex(encoded));
    }

    @Test
    void decodesMultipleFramesAndSkipsNoise() {
        byte[] first = MavlinkV1Codec.encode(
            7,
            54,
            1,
            MavlinkProtocol.COMMAND_ACK,
            MavlinkMessages.commandAck(22, 0)
        );
        byte[] second = MavlinkV1Codec.encode(
            8,
            54,
            1,
            MavlinkProtocol.EXTENDED_SYS_STATE,
            new byte[] {0, 1}
        );
        byte[] datagram = new byte[first.length + second.length + 2];
        datagram[0] = 0x55;
        datagram[1] = 0x12;
        System.arraycopy(first, 0, datagram, 2, first.length);
        System.arraycopy(second, 0, datagram, 2 + first.length, second.length);

        List<MavlinkV1Frame> decoded = MavlinkV1Codec.decode(datagram, datagram.length);

        assertEquals(2, decoded.size());
        assertEquals(MavlinkProtocol.COMMAND_ACK, decoded.get(0).messageId());
        assertEquals(MavlinkProtocol.EXTENDED_SYS_STATE, decoded.get(1).messageId());
        assertArrayEquals(new byte[] {0, 1}, decoded.get(1).payload());
    }

    @Test
    void rejectsFrameWithInvalidChecksum() {
        byte[] encoded = MavlinkV1Codec.encode(
            1,
            54,
            1,
            MavlinkProtocol.COMMAND_ACK,
            MavlinkMessages.commandAck(21, 0)
        );
        encoded[6] ^= 0x01;

        assertTrue(MavlinkV1Codec.decode(encoded, encoded.length).isEmpty());
    }
}
