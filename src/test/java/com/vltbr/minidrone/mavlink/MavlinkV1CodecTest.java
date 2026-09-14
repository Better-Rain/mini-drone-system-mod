package com.vltbr.minidrone.mavlink;

import com.vltbr.minidrone.sim.VirtualDroneSnapshot;
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

    /**
     * Pins every frame the mod sends against the main project's independent
     * MAVLink implementation, which is what the backend's decoder consumes.
     *
     * <p>The two cases above already cover a heartbeat and a command ACK; these
     * cover the telemetry the main project depends on for state and gating, so a
     * field-order or CRC-extra mistake cannot pass unnoticed. The expected hex
     * was generated with the main project's {@code tools/mavlink-delay-probe/
     * mavlink.js}, whose own frames the backend accepts, so the two encoders
     * agreeing is evidence about the mod rather than self-consistency.
     */
    @Test
    void matchesTheMainProjectEncoderForEveryTelemetryMessage() {
        VirtualDroneSnapshot hovering = new VirtualDroneSnapshot(
            54, 1, "minecraft_drone_01", 1000L,
            false, false, false, false, false, 0,
            0.0, 0.0, 0.0,
            0.0, 0.0, 0.0,
            0.0, 0.0, 0.0,
            0.0, 0.0, 0.0,
            83.0
        );
        VirtualDroneSnapshot flying = new VirtualDroneSnapshot(
            54, 1, "minecraft_drone_01", 1234L,
            true, true, true, false, false, 4,
            1.25, -2.5, 0.75,
            0.1, -0.2, 0.3,
            0.04, -0.05, 0.06,
            0.004, -0.005, 0.006,
            88.0
        );

        assertEncodes(
            "heartbeat (armed, guided)",
            "fe090136010004000000020389040398f9",
            1, MavlinkProtocol.HEARTBEAT, MavlinkMessages.heartbeat(flying));
        assertEncodes(
            "local position NED at the origin",
            "fe1c02360120e803000000000000000000000000000000000000000000000000000004e7",
            2, MavlinkProtocol.LOCAL_POSITION_NED, MavlinkMessages.localPositionNed(hovering));
        assertEncodes(
            "local position NED with a full pose",
            "fe1c03360120d20400000000a03f000020c00000403fcdcccc3dcdcc4cbe9a99993e2910",
            3, MavlinkProtocol.LOCAL_POSITION_NED, MavlinkMessages.localPositionNed(flying));
        assertEncodes(
            "EKF status report",
            "fe16043601c16f12833a6f12833a6f12833a6f12833a6f12833a2f0009b2",
            4, MavlinkProtocol.EKF_STATUS_REPORT, MavlinkMessages.ekfStatusReport());
        assertEncodes(
            "parameter value",
            "fe19053601160000c04008000000454b335f535243315f504f535859000009b2d8",
            5,
            MavlinkProtocol.PARAM_VALUE,
            MavlinkMessages.paramValue("EK3_SRC1_POSXY", 6.0f, 8, 0, 9));
        assertEncodes(
            "command ACK for arming",
            "fe030636014d900100b87a",
            6,
            MavlinkProtocol.COMMAND_ACK,
            MavlinkMessages.commandAck(400, MavlinkProtocol.MAV_RESULT_ACCEPTED));
        assertEncodes(
            "extended system state on the ground",
            "fe02073601f500012bc3",
            7, MavlinkProtocol.EXTENDED_SYS_STATE, MavlinkMessages.extendedSysState(hovering));
    }

    private static void assertEncodes(
        String description,
        String expectedHex,
        int sequence,
        int messageId,
        byte[] payload
    ) {
        assertEquals(
            expectedHex,
            HexFormat.of().formatHex(MavlinkV1Codec.encode(sequence, 54, 1, messageId, payload)),
            description
        );
    }
}
