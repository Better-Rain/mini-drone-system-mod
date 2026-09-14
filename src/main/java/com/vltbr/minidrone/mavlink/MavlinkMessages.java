package com.vltbr.minidrone.mavlink;

import com.vltbr.minidrone.sim.VirtualDroneSnapshot;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class MavlinkMessages {
    private static final int MAV_TYPE_QUADROTOR = 2;
    private static final int MAV_AUTOPILOT_ARDUPILOTMEGA = 3;
    private static final int MAV_STATE_ACTIVE = 4;
    private static final int MAV_STATE_STANDBY = 3;
    private static final int MAV_VTOL_STATE_UNDEFINED = 0;
    private static final int MAV_LANDED_STATE_ON_GROUND = 1;
    private static final int MAV_LANDED_STATE_IN_AIR = 2;
    private static final int MAV_LANDED_STATE_TAKEOFF = 3;
    private static final int MAV_LANDED_STATE_LANDING = 4;
    private static final int INDOOR_LATITUDE_E7 = 455000000;
    private static final int INDOOR_LONGITUDE_E7 = 1275000000;
    private static final int INDOOR_ALTITUDE_MM = 50000;

    private MavlinkMessages() {}

    public static byte[] heartbeat(VirtualDroneSnapshot state) {
        int baseMode = MavlinkProtocol.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED;
        if (state.guided()) {
            baseMode |= MavlinkProtocol.MAV_MODE_FLAG_GUIDED_ENABLED;
        }
        if (state.armed()) {
            baseMode |= MavlinkProtocol.MAV_MODE_FLAG_SAFETY_ARMED;
        }
        return MavlinkPayloads.writer(9)
            .putInt(state.customMode())
            .put((byte) MAV_TYPE_QUADROTOR)
            .put((byte) MAV_AUTOPILOT_ARDUPILOTMEGA)
            .put((byte) baseMode)
            .put((byte) (state.armed() ? MAV_STATE_ACTIVE : MAV_STATE_STANDBY))
            .put((byte) 3)
            .array();
    }

    public static byte[] attitude(VirtualDroneSnapshot state) {
        return MavlinkPayloads.writer(28)
            .putInt((int) state.timeBootMs())
            .putFloat((float) state.rollRad())
            .putFloat((float) state.pitchRad())
            .putFloat((float) state.yawRad())
            .putFloat((float) state.rollRateRadS())
            .putFloat((float) state.pitchRateRadS())
            .putFloat((float) state.yawRateRadS())
            .array();
    }

    public static byte[] localPositionNed(VirtualDroneSnapshot state) {
        return MavlinkPayloads.writer(28)
            .putInt((int) state.timeBootMs())
            .putFloat((float) state.northM())
            .putFloat((float) state.eastM())
            .putFloat((float) state.downM())
            .putFloat((float) state.velocityNorthMps())
            .putFloat((float) state.velocityEastMps())
            .putFloat((float) state.velocityDownMps())
            .array();
    }

    public static byte[] sysStatus(VirtualDroneSnapshot state) {
        ByteBuffer payload = MavlinkPayloads.writer(31);
        int sensorMask = 0x00200000;
        payload.putInt(sensorMask);
        payload.putInt(sensorMask);
        payload.putInt(sensorMask);
        payload.putShort((short) 100);
        payload.putShort((short) Math.round(7400.0 * state.batteryPercent() / 100.0));
        payload.putShort((short) (state.armed() ? 150 : 40));
        payload.putShort((short) 0);
        payload.putShort((short) 0);
        payload.putShort((short) 0);
        payload.putShort((short) 0);
        payload.putShort((short) 0);
        payload.putShort((short) 0);
        payload.put((byte) Math.round(state.batteryPercent()));
        return payload.array();
    }

    public static byte[] extendedSysState(VirtualDroneSnapshot state) {
        int landedState;
        if (state.landing()) {
            landedState = MAV_LANDED_STATE_LANDING;
        } else if (state.takingOff()) {
            landedState = MAV_LANDED_STATE_TAKEOFF;
        } else if (state.airborne()) {
            landedState = MAV_LANDED_STATE_IN_AIR;
        } else {
            landedState = MAV_LANDED_STATE_ON_GROUND;
        }
        return new byte[] {(byte) MAV_VTOL_STATE_UNDEFINED, (byte) landedState};
    }

    public static byte[] commandAck(int command, int result) {
        return MavlinkPayloads.writer(3)
            .putShort((short) command)
            .put((byte) result)
            .array();
    }

    /**
     * 22 bytes, not the 26 a MAVLink 2 sender would use.
     *
     * <p>{@code airspeed_variance} is an extension field: MAVLink 1 truncates
     * trailing extensions, and that is what a real ArduPilot v1 stream carries
     * (the backend's own frame diagnostics expect the 22-byte form). Sending the
     * extension inside a v1 frame is non-conformant, and a strict parser
     * dropping the frame would take every position command and takeoff with it,
     * because this message feeds the EKF gate. Verified against the main
     * project's encoder in {@code MavlinkV1CodecTest}.
     */
    public static byte[] ekfStatusReport() {
        return MavlinkPayloads.writer(22)
            .putFloat(0.001f)
            .putFloat(0.001f)
            .putFloat(0.001f)
            .putFloat(0.001f)
            .putFloat(0.001f)
            .putShort((short) (1 | 2 | 4 | 8 | 32))
            .array();
    }

    /**
     * 20 bytes: the 12-byte MAVLink 1 body plus the {@code time_usec} extension.
     *
     * <p>Deliberately unlike {@link #ekfStatusReport()}: the backend reads
     * {@code time_usec} from offset 12 when the payload is at least this long,
     * so truncating to the v1 body would drop information it uses. The same
     * applies to {@link #homePosition(long)}.
     */
    public static byte[] gpsGlobalOrigin(long timeBootUs) {
        return MavlinkPayloads.writer(20)
            .putInt(INDOOR_LATITUDE_E7)
            .putInt(INDOOR_LONGITUDE_E7)
            .putInt(INDOOR_ALTITUDE_MM)
            .putLong(timeBootUs)
            .array();
    }

    public static byte[] homePosition(long timeBootUs) {
        ByteBuffer payload = MavlinkPayloads.writer(60);
        payload.putInt(INDOOR_LATITUDE_E7);
        payload.putInt(INDOOR_LONGITUDE_E7);
        payload.putInt(INDOOR_ALTITUDE_MM);
        payload.putFloat(0.0f);
        payload.putFloat(0.0f);
        payload.putFloat(0.0f);
        payload.putFloat(1.0f);
        payload.putFloat(0.0f);
        payload.putFloat(0.0f);
        payload.putFloat(0.0f);
        payload.putFloat(0.0f);
        payload.putFloat(0.0f);
        payload.putFloat(0.0f);
        payload.putLong(timeBootUs);
        return payload.array();
    }

    public static byte[] paramValue(String name, float value, int count, int index, int type) {
        ByteBuffer payload = MavlinkPayloads.writer(25);
        payload.putFloat(value);
        payload.putShort((short) count);
        payload.putShort((short) index);
        byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
        payload.put(nameBytes, 0, Math.min(nameBytes.length, 16));
        while (payload.position() < 24) {
            payload.put((byte) 0);
        }
        payload.put((byte) type);
        return payload.array();
    }

    public static CommandLong decodeCommandLong(byte[] payload) {
        if (payload.length < 33) {
            throw new IllegalArgumentException("COMMAND_LONG payload is shorter than 33 bytes");
        }
        ByteBuffer data = MavlinkPayloads.reader(payload);
        float[] params = new float[7];
        for (int index = 0; index < params.length; index++) {
            params[index] = data.getFloat();
        }
        int command = Short.toUnsignedInt(data.getShort());
        int targetSystem = Byte.toUnsignedInt(data.get());
        int targetComponent = Byte.toUnsignedInt(data.get());
        int confirmation = Byte.toUnsignedInt(data.get());
        return new CommandLong(command, targetSystem, targetComponent, confirmation, params);
    }

    public static SetMode decodeSetMode(byte[] payload) {
        if (payload.length < 6) {
            throw new IllegalArgumentException("SET_MODE payload is shorter than 6 bytes");
        }
        ByteBuffer data = MavlinkPayloads.reader(payload);
        long customMode = Integer.toUnsignedLong(data.getInt());
        int targetSystem = Byte.toUnsignedInt(data.get());
        int baseMode = Byte.toUnsignedInt(data.get());
        return new SetMode(targetSystem, baseMode, customMode);
    }

    public static ParamRequestRead decodeParamRequestRead(byte[] payload) {
        if (payload.length < 20) {
            throw new IllegalArgumentException("PARAM_REQUEST_READ payload is shorter than 20 bytes");
        }
        ByteBuffer data = MavlinkPayloads.reader(payload);
        short index = data.getShort();
        int targetSystem = Byte.toUnsignedInt(data.get());
        int targetComponent = Byte.toUnsignedInt(data.get());
        byte[] id = new byte[16];
        data.get(id);
        return new ParamRequestRead(targetSystem, targetComponent, readAscii(id), index);
    }

    public static PositionTargetLocalNed decodePositionTargetLocalNed(byte[] payload) {
        if (payload.length < 53) {
            throw new IllegalArgumentException(
                "SET_POSITION_TARGET_LOCAL_NED payload is shorter than 53 bytes"
            );
        }
        ByteBuffer data = MavlinkPayloads.reader(payload);
        long timeBootMs = Integer.toUnsignedLong(data.getInt());
        float north = data.getFloat();
        float east = data.getFloat();
        float down = data.getFloat();
        float velocityNorth = data.getFloat();
        float velocityEast = data.getFloat();
        float velocityDown = data.getFloat();
        float accelerationNorth = data.getFloat();
        float accelerationEast = data.getFloat();
        float accelerationDown = data.getFloat();
        float yaw = data.getFloat();
        float yawRate = data.getFloat();
        int typeMask = Short.toUnsignedInt(data.getShort());
        int targetSystem = Byte.toUnsignedInt(data.get());
        int targetComponent = Byte.toUnsignedInt(data.get());
        int coordinateFrame = Byte.toUnsignedInt(data.get());
        return new PositionTargetLocalNed(
            timeBootMs,
            north,
            east,
            down,
            velocityNorth,
            velocityEast,
            velocityDown,
            accelerationNorth,
            accelerationEast,
            accelerationDown,
            yaw,
            yawRate,
            typeMask,
            targetSystem,
            targetComponent,
            coordinateFrame
        );
    }

    private static String readAscii(byte[] bytes) {
        int length = 0;
        while (length < bytes.length && bytes[length] != 0) {
            length++;
        }
        return new String(bytes, 0, length, StandardCharsets.US_ASCII);
    }

    public record CommandLong(
        int command,
        int targetSystem,
        int targetComponent,
        int confirmation,
        float[] params
    ) {
        public CommandLong {
            params = Arrays.copyOf(params, params.length);
        }

        @Override
        public float[] params() {
            return Arrays.copyOf(params, params.length);
        }
    }

    public record SetMode(int targetSystem, int baseMode, long customMode) {}

    public record ParamRequestRead(
        int targetSystem,
        int targetComponent,
        String parameterId,
        int parameterIndex
    ) {}

    public record PositionTargetLocalNed(
        long timeBootMs,
        float north,
        float east,
        float down,
        float velocityNorth,
        float velocityEast,
        float velocityDown,
        float accelerationNorth,
        float accelerationEast,
        float accelerationDown,
        float yaw,
        float yawRate,
        int typeMask,
        int targetSystem,
        int targetComponent,
        int coordinateFrame
    ) {
        public boolean commandsNorth() {
            return commandsPosition(MavlinkProtocol.POSITION_TARGET_IGNORE_POSITION_X);
        }

        public boolean commandsEast() {
            return commandsPosition(MavlinkProtocol.POSITION_TARGET_IGNORE_POSITION_Y);
        }

        public boolean commandsDown() {
            return commandsPosition(MavlinkProtocol.POSITION_TARGET_IGNORE_POSITION_Z);
        }

        public boolean commandsVelocityNorth() {
            return commandsPosition(MavlinkProtocol.POSITION_TARGET_IGNORE_VELOCITY_X);
        }

        public boolean commandsVelocityEast() {
            return commandsPosition(MavlinkProtocol.POSITION_TARGET_IGNORE_VELOCITY_Y);
        }

        public boolean commandsVelocityDown() {
            return commandsPosition(MavlinkProtocol.POSITION_TARGET_IGNORE_VELOCITY_Z);
        }

        public boolean commandsAccelerationNorth() {
            return commandsPosition(MavlinkProtocol.POSITION_TARGET_IGNORE_ACCELERATION_X);
        }

        public boolean commandsAccelerationEast() {
            return commandsPosition(MavlinkProtocol.POSITION_TARGET_IGNORE_ACCELERATION_Y);
        }

        public boolean commandsAccelerationDown() {
            return commandsPosition(MavlinkProtocol.POSITION_TARGET_IGNORE_ACCELERATION_Z);
        }

        public boolean commandsYaw() {
            return commandsPosition(MavlinkProtocol.POSITION_TARGET_IGNORE_YAW);
        }

        public boolean commandsYawRate() {
            return commandsPosition(MavlinkProtocol.POSITION_TARGET_IGNORE_YAW_RATE);
        }

        public boolean commandsAnyChannel() {
            return MavlinkProtocol.commandsAnyChannel(typeMask);
        }

        private boolean commandsPosition(int ignoreBit) {
            return MavlinkProtocol.commandsPosition(typeMask, ignoreBit);
        }
    }
}
