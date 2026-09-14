package com.vltbr.minidrone.mavlink;

import com.vltbr.minidrone.MiniDroneMod;
import com.vltbr.minidrone.sim.LocalSetpoint;
import com.vltbr.minidrone.sim.VirtualDroneManager;
import com.vltbr.minidrone.sim.VirtualDroneSnapshot;
import net.minecraft.server.MinecraftServer;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

public final class VirtualAutopilot {
    private static final int MAV_PARAM_TYPE_INT8 = 2;
    private static final int MAV_PARAM_TYPE_REAL32 = 9;

    private final MinecraftServer server;
    private final VirtualDroneManager droneManager;
    private final Consumer<MavlinkOutboundMessage> outbound;
    private final ForwardingHold forwardingHold;
    private final Map<String, Parameter> parameters = new LinkedHashMap<>();

    public VirtualAutopilot(
        MinecraftServer server,
        VirtualDroneManager droneManager,
        Consumer<MavlinkOutboundMessage> outbound,
        ForwardingHold forwardingHold
    ) {
        this.server = server;
        this.droneManager = droneManager;
        this.outbound = outbound;
        this.forwardingHold = forwardingHold;
        registerParameters();
    }

    public void handle(MavlinkV1Frame frame) {
        try {
            switch (frame.messageId()) {
                case MavlinkProtocol.COMMAND_LONG -> handleCommandLong(
                    MavlinkMessages.decodeCommandLong(frame.payload())
                );
                case MavlinkProtocol.SET_MODE -> handleSetMode(
                    MavlinkMessages.decodeSetMode(frame.payload())
                );
                case MavlinkProtocol.PARAM_REQUEST_READ -> handleParamRequestRead(
                    MavlinkMessages.decodeParamRequestRead(frame.payload())
                );
                case MavlinkProtocol.PARAM_REQUEST_LIST -> handleParamRequestList(frame.payload());
                case MavlinkProtocol.PARAM_SET -> handleParamSet(frame.payload());
                case MavlinkProtocol.SET_GPS_GLOBAL_ORIGIN -> handleSetGpsGlobalOrigin(frame.payload());
                case MavlinkProtocol.SET_POSITION_TARGET_LOCAL_NED -> handlePositionTarget(frame.payload());
                default -> {
                    // Ground-control heartbeats and unsupported diagnostic requests are harmless.
                }
            }
        } catch (IllegalArgumentException exception) {
            MiniDroneMod.LOGGER.warn(
                "Rejected malformed MAVLink message {}: {}",
                frame.messageId(),
                exception.getMessage()
            );
        }
    }

    private void handleCommandLong(MavlinkMessages.CommandLong command) {
        if (!targetsThisVehicle(command.targetSystem(), command.targetComponent())) {
            return;
        }
        server.execute(() -> {
            int result = switch (command.command()) {
                case MavlinkProtocol.MAV_CMD_DO_SET_MODE -> setMode(Math.round(command.params()[1]));
                case MavlinkProtocol.MAV_CMD_COMPONENT_ARM_DISARM ->
                    droneManager.setArmed(command.params()[0] >= 0.5f)
                        ? MavlinkProtocol.MAV_RESULT_ACCEPTED
                        : MavlinkProtocol.MAV_RESULT_TEMPORARILY_REJECTED;
                case MavlinkProtocol.MAV_CMD_NAV_TAKEOFF ->
                    droneManager.takeoff(command.params()[6])
                        ? MavlinkProtocol.MAV_RESULT_ACCEPTED
                        : MavlinkProtocol.MAV_RESULT_TEMPORARILY_REJECTED;
                case MavlinkProtocol.MAV_CMD_NAV_LAND ->
                    droneManager.land()
                        ? MavlinkProtocol.MAV_RESULT_ACCEPTED
                        : MavlinkProtocol.MAV_RESULT_TEMPORARILY_REJECTED;
                case MavlinkProtocol.MAV_CMD_DO_SET_HOME -> {
                    sendOriginTelemetry();
                    yield MavlinkProtocol.MAV_RESULT_ACCEPTED;
                }
                case MavlinkProtocol.MAV_CMD_SET_MESSAGE_INTERVAL ->
                    MavlinkProtocol.MAV_RESULT_ACCEPTED;
                case MavlinkProtocol.MAV_CMD_REQUEST_MESSAGE -> {
                    sendRequestedMessage(Math.round(command.params()[0]));
                    yield MavlinkProtocol.MAV_RESULT_ACCEPTED;
                }
                default -> MavlinkProtocol.MAV_RESULT_UNSUPPORTED;
            };
            send(MavlinkProtocol.COMMAND_ACK, MavlinkMessages.commandAck(command.command(), result));
            MiniDroneMod.LOGGER.info(
                "MAVLink command {} completed with result {} for {}",
                command.command(),
                result,
                droneManager.snapshot().droneId()
            );
        });
    }

    private void handleSetMode(MavlinkMessages.SetMode setMode) {
        if (!targetsThisVehicle(setMode.targetSystem(), 0)) {
            return;
        }
        server.execute(() -> setMode((int) setMode.customMode()));
    }

    private int setMode(int customMode) {
        return droneManager.setMode(customMode)
            ? MavlinkProtocol.MAV_RESULT_ACCEPTED
            : MavlinkProtocol.MAV_RESULT_UNSUPPORTED;
    }

    private void handleParamRequestRead(MavlinkMessages.ParamRequestRead request) {
        if (!targetsThisVehicle(request.targetSystem(), request.targetComponent())) {
            return;
        }
        Parameter parameter = request.parameterId().isEmpty()
            ? parameterAt(request.parameterIndex())
            : parameters.get(request.parameterId());
        if (parameter != null) {
            sendParameter(parameter);
        }
    }

    private void handleParamRequestList(byte[] payload) {
        if (payload.length < 2 || !targetsThisVehicle(
            Byte.toUnsignedInt(payload[0]),
            Byte.toUnsignedInt(payload[1])
        )) {
            return;
        }
        parameters.values().forEach(this::sendParameter);
    }

    private void handleParamSet(byte[] payload) {
        if (payload.length < 23) {
            throw new IllegalArgumentException("PARAM_SET payload is shorter than 23 bytes");
        }
        ByteBuffer data = MavlinkPayloads.reader(payload);
        float value = data.getFloat();
        int targetSystem = Byte.toUnsignedInt(data.get());
        int targetComponent = Byte.toUnsignedInt(data.get());
        if (!targetsThisVehicle(targetSystem, targetComponent)) {
            return;
        }
        byte[] id = new byte[16];
        data.get(id);
        String name = readAscii(id);
        Parameter current = parameters.get(name);
        if (current != null) {
            Parameter updated = new Parameter(current.name(), value, current.type(), current.index());
            parameters.put(name, updated);
            sendParameter(updated);
        }
    }

    private void handleSetGpsGlobalOrigin(byte[] payload) {
        if (payload.length < 13 || !targetsThisVehicle(Byte.toUnsignedInt(payload[12]), 0)) {
            return;
        }
        sendOriginTelemetry();
    }

    private void handlePositionTarget(byte[] payload) {
        MavlinkMessages.PositionTargetLocalNed target =
            MavlinkMessages.decodePositionTargetLocalNed(payload);
        if (!targetsThisVehicle(target.targetSystem(), target.targetComponent())
            || target.coordinateFrame() != MavlinkProtocol.MAV_FRAME_LOCAL_NED
            || !target.commandsAnyChannel()) {
            return;
        }
        // The backend holds forwarding when no operator link owns the vehicle. A
        // relay answers by pausing its multicast; here the equivalent is to stop
        // accepting new setpoints, leaving the vehicle on the last one it took.
        if (!forwardingHold.acceptsSetpoints()) {
            MiniDroneMod.LOGGER.debug(
                "Ignored local NED setpoint while motion-capture forwarding is held"
            );
            return;
        }
        // The backend's PVA setpoints use many type_mask combinations, so every
        // commanded channel is mapped instead of matching whole masks: a dropped
        // frame leaves the operator with a virtual vehicle that simply ignores
        // the command.
        LocalSetpoint setpoint = toLocalSetpoint(target);
        server.execute(() -> {
            boolean accepted = droneManager.setLocalSetpoint(setpoint);
            if (!accepted) {
                MiniDroneMod.LOGGER.debug(
                    "Rejected local NED setpoint {} for {}",
                    setpoint,
                    droneManager.snapshot().droneId()
                );
            }
        });
    }

    static LocalSetpoint toLocalSetpoint(MavlinkMessages.PositionTargetLocalNed target) {
        return new LocalSetpoint(
            axis(
                target.commandsNorth(),
                target.north(),
                target.commandsVelocityNorth(),
                target.velocityNorth(),
                target.commandsAccelerationNorth(),
                target.accelerationNorth()
            ),
            axis(
                target.commandsEast(),
                target.east(),
                target.commandsVelocityEast(),
                target.velocityEast(),
                target.commandsAccelerationEast(),
                target.accelerationEast()
            ),
            axis(
                target.commandsDown(),
                target.down(),
                target.commandsVelocityDown(),
                target.velocityDown(),
                target.commandsAccelerationDown(),
                target.accelerationDown()
            ),
            target.commandsYaw(),
            target.yaw(),
            target.commandsYawRate(),
            target.yawRate()
        );
    }

    private static LocalSetpoint.Axis axis(
        boolean positionSet,
        double position,
        boolean velocitySet,
        double velocity,
        boolean accelerationSet,
        double acceleration
    ) {
        return new LocalSetpoint.Axis(
            positionSet, position,
            velocitySet, velocity,
            accelerationSet, acceleration
        );
    }

    private void sendRequestedMessage(int messageId) {
        VirtualDroneSnapshot state = droneManager.snapshot();
        switch (messageId) {
            case MavlinkProtocol.ATTITUDE -> send(messageId, MavlinkMessages.attitude(state));
            case MavlinkProtocol.LOCAL_POSITION_NED ->
                send(messageId, MavlinkMessages.localPositionNed(state));
            case MavlinkProtocol.EKF_STATUS_REPORT ->
                send(messageId, MavlinkMessages.ekfStatusReport());
            case MavlinkProtocol.GPS_GLOBAL_ORIGIN ->
                send(messageId, MavlinkMessages.gpsGlobalOrigin(state.timeBootMs() * 1000L));
            case MavlinkProtocol.HOME_POSITION ->
                send(messageId, MavlinkMessages.homePosition(state.timeBootMs() * 1000L));
            case MavlinkProtocol.EXTENDED_SYS_STATE ->
                send(messageId, MavlinkMessages.extendedSysState(state));
            default -> {
                // An accepted request is allowed to have no response for unsupported diagnostics.
            }
        }
    }

    private void sendOriginTelemetry() {
        VirtualDroneSnapshot state = droneManager.snapshot();
        send(
            MavlinkProtocol.GPS_GLOBAL_ORIGIN,
            MavlinkMessages.gpsGlobalOrigin(state.timeBootMs() * 1000L)
        );
        send(
            MavlinkProtocol.HOME_POSITION,
            MavlinkMessages.homePosition(state.timeBootMs() * 1000L)
        );
    }

    private void sendParameter(Parameter parameter) {
        send(
            MavlinkProtocol.PARAM_VALUE,
            MavlinkMessages.paramValue(
                parameter.name(),
                parameter.value(),
                parameters.size(),
                parameter.index(),
                parameter.type()
            )
        );
    }

    private boolean targetsThisVehicle(int targetSystem, int targetComponent) {
        VirtualDroneSnapshot state = droneManager.snapshot();
        return (targetSystem == 0 || targetSystem == state.systemId())
            && (targetComponent == 0 || targetComponent == state.componentId());
    }

    private void send(int messageId, byte[] payload) {
        outbound.accept(new MavlinkOutboundMessage(messageId, payload));
    }

    private Parameter parameterAt(int index) {
        if (index < 0 || index >= parameters.size()) {
            return null;
        }
        return parameters.values().stream().skip(index).findFirst().orElse(null);
    }

    private void registerParameters() {
        addParameter("EK3_SRC1_POSXY", 6.0f, MAV_PARAM_TYPE_INT8);
        addParameter("EK3_SRC1_POSZ", 6.0f, MAV_PARAM_TYPE_INT8);
        addParameter("EK3_SRC1_VELXY", 6.0f, MAV_PARAM_TYPE_INT8);
        addParameter("EK3_SRC1_VELZ", 6.0f, MAV_PARAM_TYPE_INT8);
        addParameter("EK3_SRC1_YAW", 6.0f, MAV_PARAM_TYPE_INT8);
        addParameter("GUID_OPTIONS", 0.0f, MAV_PARAM_TYPE_REAL32);
        addParameter("WPNAV_SPEED_UP", 100.0f, MAV_PARAM_TYPE_REAL32);
        addParameter("WPNAV_SPEED_DN", 75.0f, MAV_PARAM_TYPE_REAL32);
    }

    private void addParameter(String name, float value, int type) {
        parameters.put(name, new Parameter(name, value, type, parameters.size()));
    }

    private static String readAscii(byte[] bytes) {
        int length = 0;
        while (length < bytes.length && bytes[length] != 0) {
            length++;
        }
        return new String(bytes, 0, length, StandardCharsets.US_ASCII);
    }

    private record Parameter(String name, float value, int type, int index) {}
}
