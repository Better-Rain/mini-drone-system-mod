package com.vltbr.minidrone.mavlink;

import com.vltbr.minidrone.sim.VirtualDroneSnapshot;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavlinkMessagesTest {
    @Test
    void heartbeatReflectsGuidedAndArmedState() {
        byte[] payload = MavlinkMessages.heartbeat(snapshot(true, true, -1.0, 88.0));

        ByteBuffer data = MavlinkPayloads.reader(payload);
        assertEquals(MavlinkProtocol.ARDUCOPTER_MODE_GUIDED, data.getInt());
        assertEquals(2, Byte.toUnsignedInt(data.get()));
        assertEquals(3, Byte.toUnsignedInt(data.get()));
        int baseMode = Byte.toUnsignedInt(data.get());
        assertTrue((baseMode & MavlinkProtocol.MAV_MODE_FLAG_GUIDED_ENABLED) != 0);
        assertTrue((baseMode & MavlinkProtocol.MAV_MODE_FLAG_SAFETY_ARMED) != 0);
        assertEquals(4, Byte.toUnsignedInt(data.get()));
    }

    @Test
    void sysStatusUsesBackendExpectedOffsets() {
        byte[] payload = MavlinkMessages.sysStatus(snapshot(false, false, 0.0, 83.0));

        assertEquals(31, payload.length);
        ByteBuffer data = MavlinkPayloads.reader(payload);
        assertEquals(6142, Short.toUnsignedInt(data.getShort(14)));
        assertEquals(83, Byte.toUnsignedInt(payload[30]));
    }

    @Test
    void decodesCommandLongTargetAndParameters() {
        ByteBuffer payload = MavlinkPayloads.writer(33);
        for (int index = 0; index < 6; index++) {
            payload.putFloat(0.0f);
        }
        payload.putFloat(1.5f);
        payload.putShort((short) MavlinkProtocol.MAV_CMD_NAV_TAKEOFF);
        payload.put((byte) 54);
        payload.put((byte) 1);
        payload.put((byte) 0);

        MavlinkMessages.CommandLong decoded = MavlinkMessages.decodeCommandLong(payload.array());

        assertEquals(MavlinkProtocol.MAV_CMD_NAV_TAKEOFF, decoded.command());
        assertEquals(54, decoded.targetSystem());
        assertEquals(1, decoded.targetComponent());
        assertEquals(1.5f, decoded.params()[6]);

        float[] exposedParams = decoded.params();
        exposedParams[6] = 9.0f;
        assertEquals(1.5f, decoded.params()[6]);
    }

    @Test
    void decodesLocalNedPositionTargetFields() {
        ByteBuffer payload = MavlinkPayloads.writer(53);
        payload.putInt(1234);
        payload.putFloat(2.0f);
        payload.putFloat(-3.0f);
        payload.putFloat(-1.0f);
        payload.putFloat(0.5f);
        payload.putFloat(-0.25f);
        payload.putFloat(0.0f);
        payload.putFloat(0.0f);
        payload.putFloat(0.0f);
        payload.putFloat(0.0f);
        payload.putFloat(0.0f);
        payload.putFloat(0.0f);
        payload.putShort((short) MavlinkProtocol.POSITION_TARGET_TYPE_MASK_POSITION_VELOCITY);
        payload.put((byte) 54);
        payload.put((byte) 1);
        payload.put((byte) MavlinkProtocol.MAV_FRAME_LOCAL_NED);

        MavlinkMessages.PositionTargetLocalNed decoded =
            MavlinkMessages.decodePositionTargetLocalNed(payload.array());

        assertEquals(1234L, decoded.timeBootMs());
        assertEquals(2.0f, decoded.north());
        assertEquals(-3.0f, decoded.east());
        assertEquals(-1.0f, decoded.down());
        assertEquals(0.5f, decoded.velocityNorth());
        assertEquals(-0.25f, decoded.velocityEast());
        assertEquals(MavlinkProtocol.POSITION_TARGET_TYPE_MASK_POSITION_VELOCITY, decoded.typeMask());
        assertEquals(54, decoded.targetSystem());
        assertEquals(1, decoded.targetComponent());
        assertEquals(MavlinkProtocol.MAV_FRAME_LOCAL_NED, decoded.coordinateFrame());
    }

    @Test
    void extendedStateDistinguishesGroundAndAir() {
        byte[] ground = MavlinkMessages.extendedSysState(snapshot(false, false, 0.0, 100.0));
        byte[] air = MavlinkMessages.extendedSysState(snapshot(true, true, -1.0, 100.0));

        assertFalse(ground[1] == air[1]);
        assertEquals(1, ground[1]);
        assertEquals(2, air[1]);
    }

    // The backend's PVA setpoints use several type masks. Matching whole masks
    // silently dropped most of them, so every channel is read from its own bit.
    @Test
    void readsTheCommandedChannelsOfEveryBackendTypeMask() {
        MavlinkMessages.PositionTargetLocalNed positionOnly = decodeMask(0x0DF8);
        assertTrue(positionOnly.commandsNorth());
        assertTrue(positionOnly.commandsEast());
        assertTrue(positionOnly.commandsDown());
        assertFalse(positionOnly.commandsVelocityNorth());
        assertFalse(positionOnly.commandsAccelerationNorth());
        assertFalse(positionOnly.commandsYaw());
        assertTrue(positionOnly.commandsAnyChannel());

        MavlinkMessages.PositionTargetLocalNed positionVelocity = decodeMask(0x0DC0);
        assertTrue(positionVelocity.commandsNorth());
        assertTrue(positionVelocity.commandsVelocityEast());
        assertFalse(positionVelocity.commandsAccelerationEast());
        assertFalse(positionVelocity.commandsYaw());

        MavlinkMessages.PositionTargetLocalNed full = decodeMask(0x0C00);
        assertTrue(full.commandsNorth());
        assertTrue(full.commandsVelocityDown());
        assertTrue(full.commandsAccelerationNorth());
        assertFalse(full.commandsYaw());
        assertFalse(full.commandsYawRate());

        MavlinkMessages.PositionTargetLocalNed velocityOnly = decodeMask(0x0DC7);
        assertFalse(velocityOnly.commandsNorth());
        assertFalse(velocityOnly.commandsEast());
        assertFalse(velocityOnly.commandsDown());
        assertTrue(velocityOnly.commandsVelocityNorth());
        assertTrue(velocityOnly.commandsVelocityEast());
        assertTrue(velocityOnly.commandsVelocityDown());

        // Velocity on the horizontal axes while the vertical position is kept.
        MavlinkMessages.PositionTargetLocalNed mixed = decodeMask(0x0DC3);
        assertFalse(mixed.commandsNorth());
        assertFalse(mixed.commandsEast());
        assertTrue(mixed.commandsDown());
        assertTrue(mixed.commandsVelocityNorth());
        assertTrue(mixed.commandsVelocityEast());
        assertFalse(mixed.commandsYaw());

        // 0x0800 only clears the yaw_rate bit, so position, velocity,
        // acceleration and yaw are all commanded while yaw_rate is not.
        MavlinkMessages.PositionTargetLocalNed yawWithEverythingElse = decodeMask(0x0800);
        assertTrue(yawWithEverythingElse.commandsNorth());
        assertTrue(yawWithEverythingElse.commandsVelocityNorth());
        assertTrue(yawWithEverythingElse.commandsAccelerationNorth());
        assertTrue(yawWithEverythingElse.commandsYaw());
        assertFalse(yawWithEverythingElse.commandsYawRate());

        // Yaw on its own: every channel except yaw and yaw_rate is ignored.
        MavlinkMessages.PositionTargetLocalNed yawOnly = decodeMask(0x09FF);
        assertFalse(yawOnly.commandsNorth());
        assertFalse(yawOnly.commandsVelocityNorth());
        assertFalse(yawOnly.commandsAccelerationNorth());
        assertFalse(yawOnly.commandsDown());
        assertTrue(yawOnly.commandsYaw());
        assertFalse(yawOnly.commandsYawRate());
        assertTrue(yawOnly.commandsAnyChannel());

        MavlinkMessages.PositionTargetLocalNed everything = decodeMask(0x0000);
        assertTrue(everything.commandsNorth());
        assertTrue(everything.commandsVelocityNorth());
        assertTrue(everything.commandsAccelerationNorth());
        assertTrue(everything.commandsYaw());
        assertTrue(everything.commandsYawRate());

        MavlinkMessages.PositionTargetLocalNed nothing = decodeMask(0xFFFF);
        assertFalse(nothing.commandsAnyChannel());
    }

    @Test
    void decodesTheAccelerationAndYawChannelsInsteadOfSkippingThem() {        ByteBuffer payload = MavlinkPayloads.writer(53);
        payload.putInt(0);
        for (int index = 0; index < 3; index++) {
            payload.putFloat(0.0f);
        }
        for (int index = 0; index < 3; index++) {
            payload.putFloat(0.0f);
        }
        payload.putFloat(0.4f);
        payload.putFloat(-0.5f);
        payload.putFloat(0.6f);
        payload.putFloat(0.7f);
        payload.putFloat(-0.8f);
        payload.putShort((short) 0x0C00);
        payload.put((byte) 54);
        payload.put((byte) 1);
        payload.put((byte) MavlinkProtocol.MAV_FRAME_LOCAL_NED);

        MavlinkMessages.PositionTargetLocalNed decoded =
            MavlinkMessages.decodePositionTargetLocalNed(payload.array());

        assertEquals(0.4f, decoded.accelerationNorth());
        assertEquals(-0.5f, decoded.accelerationEast());
        assertEquals(0.6f, decoded.accelerationDown());
        assertEquals(0.7f, decoded.yaw());
        assertEquals(-0.8f, decoded.yawRate());
    }

    /**
     * Every payload the mod sends must fit the MAVLink 1 length, because the mod
     * sends v1 frames and v1 truncates trailing extension fields. Sending an
     * extension anyway is what a real flight controller does not do, and a strict
     * parser dropping the frame would take the gate that message feeds with it -
     * EKF_STATUS_REPORT carried the airspeed_variance extension until this was
     * checked against the official library's MIN_LEN values.
     *
     * <p>These are the official {@code MAVLINK_MSG_ID_*_MIN_LEN} numbers, taken
     * from the same MAVLink C library the backend links against.
     */
    @Test
    void everyPayloadFitsTheMavlinkV1Length() {
        VirtualDroneSnapshot state = snapshot(true, true, -1.0, 88.0);

        assertEquals(9, MavlinkMessages.heartbeat(state).length, "HEARTBEAT");
        assertEquals(31, MavlinkMessages.sysStatus(state).length, "SYS_STATUS");
        assertEquals(28, MavlinkMessages.attitude(state).length, "ATTITUDE");
        assertEquals(28, MavlinkMessages.localPositionNed(state).length, "LOCAL_POSITION_NED");
        assertEquals(2, MavlinkMessages.extendedSysState(state).length, "EXTENDED_SYS_STATE");
        assertEquals(3, MavlinkMessages.commandAck(400, 0).length, "COMMAND_ACK");
        assertEquals(22, MavlinkMessages.ekfStatusReport().length, "EKF_STATUS_REPORT");
        assertEquals(12, MavlinkMessages.gpsGlobalOrigin().length, "GPS_GLOBAL_ORIGIN");
        assertEquals(52, MavlinkMessages.homePosition().length, "HOME_POSITION");
        assertEquals(25, MavlinkMessages.paramValue("EK3_SRC1_YAW", 6.0f, 8, 4, 9).length, "PARAM_VALUE");
    }

    private static MavlinkMessages.PositionTargetLocalNed decodeMask(int typeMask) {
        ByteBuffer payload = MavlinkPayloads.writer(53);
        payload.putInt(0);
        for (int index = 0; index < 11; index++) {
            payload.putFloat(0.0f);
        }
        payload.putShort((short) typeMask);
        payload.put((byte) 54);
        payload.put((byte) 1);
        payload.put((byte) MavlinkProtocol.MAV_FRAME_LOCAL_NED);
        return MavlinkMessages.decodePositionTargetLocalNed(payload.array());
    }

    private static VirtualDroneSnapshot snapshot(
        boolean armed,
        boolean guided,
        double downM,
        double batteryPercent
    ) {
        return new VirtualDroneSnapshot(
            54,
            1,
            "minecraft_drone_01",
            1000,
            armed,
            guided,
            downM < -0.1,
            false,
            false,
            guided ? MavlinkProtocol.ARDUCOPTER_MODE_GUIDED : 0,
            0.0,
            0.0,
            downM,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            batteryPercent
        );
    }
}
