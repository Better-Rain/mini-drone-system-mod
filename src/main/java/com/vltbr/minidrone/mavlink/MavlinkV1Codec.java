package com.vltbr.minidrone.mavlink;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class MavlinkV1Codec {
    private static final int HEADER_LENGTH = 6;
    private static final int CHECKSUM_LENGTH = 2;

    private MavlinkV1Codec() {}

    public static byte[] encode(
        int sequence,
        int systemId,
        int componentId,
        int messageId,
        byte[] payload
    ) {
        if (payload.length > 255) {
            throw new IllegalArgumentException("MAVLink v1 payload cannot exceed 255 bytes");
        }
        int crcExtra = requireCrcExtra(messageId);
        byte[] frame = new byte[HEADER_LENGTH + payload.length + CHECKSUM_LENGTH];
        frame[0] = (byte) MavlinkProtocol.V1_MAGIC;
        frame[1] = (byte) payload.length;
        frame[2] = (byte) sequence;
        frame[3] = (byte) systemId;
        frame[4] = (byte) componentId;
        frame[5] = (byte) messageId;
        System.arraycopy(payload, 0, frame, HEADER_LENGTH, payload.length);

        int crc = checksum(frame, 1, HEADER_LENGTH + payload.length - 1, crcExtra);
        frame[HEADER_LENGTH + payload.length] = (byte) crc;
        frame[HEADER_LENGTH + payload.length + 1] = (byte) (crc >>> 8);
        return frame;
    }

    public static List<MavlinkV1Frame> decode(byte[] datagram, int length) {
        int limit = Math.min(length, datagram.length);
        List<MavlinkV1Frame> frames = new ArrayList<>();
        int offset = 0;
        while (offset + HEADER_LENGTH + CHECKSUM_LENGTH <= limit) {
            if ((datagram[offset] & 0xFF) != MavlinkProtocol.V1_MAGIC) {
                offset++;
                continue;
            }

            int payloadLength = datagram[offset + 1] & 0xFF;
            int frameLength = HEADER_LENGTH + payloadLength + CHECKSUM_LENGTH;
            if (offset + frameLength > limit) {
                break;
            }

            int messageId = datagram[offset + 5] & 0xFF;
            int crcExtra = MavlinkProtocol.crcExtra(messageId);
            if (crcExtra < 0) {
                offset += frameLength;
                continue;
            }

            int expected = (datagram[offset + HEADER_LENGTH + payloadLength] & 0xFF)
                | ((datagram[offset + HEADER_LENGTH + payloadLength + 1] & 0xFF) << 8);
            int actual = checksum(
                datagram,
                offset + 1,
                HEADER_LENGTH + payloadLength - 1,
                crcExtra
            );
            if (actual == expected) {
                frames.add(new MavlinkV1Frame(
                    datagram[offset + 2] & 0xFF,
                    datagram[offset + 3] & 0xFF,
                    datagram[offset + 4] & 0xFF,
                    messageId,
                    Arrays.copyOfRange(
                        datagram,
                        offset + HEADER_LENGTH,
                        offset + HEADER_LENGTH + payloadLength
                    )
                ));
            }
            offset += frameLength;
        }
        return frames;
    }

    static int accumulate(int crc, int value) {
        int tmp = value ^ (crc & 0xFF);
        tmp ^= (tmp << 4) & 0xFF;
        return ((crc >>> 8) ^ (tmp << 8) ^ (tmp << 3) ^ (tmp >>> 4)) & 0xFFFF;
    }

    private static int checksum(byte[] bytes, int offset, int length, int crcExtra) {
        int crc = 0xFFFF;
        for (int index = offset; index < offset + length; index++) {
            crc = accumulate(crc, bytes[index] & 0xFF);
        }
        return accumulate(crc, crcExtra);
    }

    private static int requireCrcExtra(int messageId) {
        int crcExtra = MavlinkProtocol.crcExtra(messageId);
        if (crcExtra < 0) {
            throw new IllegalArgumentException("Unknown MAVLink v1 CRC extra for message " + messageId);
        }
        return crcExtra;
    }
}
