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
