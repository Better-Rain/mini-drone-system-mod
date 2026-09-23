package com.vltbr.minidrone.mavlink;

import com.vltbr.minidrone.sim.LocalSetpoint;
import com.vltbr.minidrone.sim.VirtualFlightController;
import com.vltbr.minidrone.sim.VirtualDroneSnapshot;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

public final class VirtualAutopilot {
    private static final int MAV_PARAM_TYPE_INT8 = 2;
    private static final int MAV_PARAM_TYPE_REAL32 = 9;

    private final Executor executor;
    private final VirtualFlightController droneManager;
    private final Consumer<MavlinkOutboundMessage> outbound;
    private final ForwardingHold forwardingHold;
    private final AutopilotLog log;
    private final Map<String, Parameter> parameters = new LinkedHashMap<>();

    public VirtualAutopilot(
        Executor executor,
        VirtualFlightController droneManager,
        Consumer<MavlinkOutboundMessage> outbound,
        ForwardingHold forwardingHold
    ) {
        this(executor, droneManager, outbound, forwardingHold, AutopilotLog.SILENT);
    }

    /**
     * `log` receives what this autopilot did, as lines the operator can read. The command
     * path is deliberately free of game classes so a test can drive decoded frames through
     * it, and the mod's logger lives on a class Minecraft types hang off, so the sink is a
     * parameter rather than a direct call.
     */
    public VirtualAutopilot(
        Executor executor,
        VirtualFlightController droneManager,
        Consumer<MavlinkOutboundMessage> outbound,
        ForwardingHold forwardingHold,
        AutopilotLog log
    ) {
        this.executor = executor;
        this.droneManager = droneManager;
        this.outbound = outbound;
        this.forwardingHold = forwardingHold;
        this.log = log == null ? AutopilotLog.SILENT : log;
        registerParameters();
    }

    public void handle(MavlinkV1Frame frame) {
        try {
            // Resolve the vehicle from the message's *target*, not from the frame header: the
            // header carries the sender's system id (a ground station's, here), and the
            // target is the only thing that says which aircraft the message is for.
            switch (frame.messageId()) {
                case MavlinkProtocol.COMMAND_LONG -> {
                    MavlinkMessages.CommandLong command =
                        MavlinkMessages.decodeCommandLong(frame.payload());
                    VirtualFlightController vehicle =
                        targetVehicle(command.targetSystem(), command.targetComponent());
                    if (vehicle != null) {
                        handleCommandLong(vehicle, command);
                    }
                }
                case MavlinkProtocol.SET_MODE -> {
                    MavlinkMessages.SetMode setMode =
                        MavlinkMessages.decodeSetMode(frame.payload());
                    VirtualFlightController vehicle = targetVehicle(setMode.targetSystem(), 0);
                    if (vehicle != null) {
                        handleSetMode(vehicle, setMode);
                    }
                }
                case MavlinkProtocol.PARAM_REQUEST_READ -> {
                    MavlinkMessages.ParamRequestRead request =
                        MavlinkMessages.decodeParamRequestRead(frame.payload());
                    VirtualFlightController vehicle =
                        targetVehicle(request.targetSystem(), request.targetComponent());
                    if (vehicle != null) {
                        handleParamRequestRead(vehicle, request);
                    }
                }
                case MavlinkProtocol.PARAM_REQUEST_LIST -> {
                    byte[] payload = frame.payload();
                    if (payload.length >= 2) {
                        VirtualFlightController vehicle = targetVehicle(
                            Byte.toUnsignedInt(payload[0]),
                            Byte.toUnsignedInt(payload[1]));
                        if (vehicle != null) {
                            handleParamRequestList(vehicle);
                        }
                    }
                }
                case MavlinkProtocol.PARAM_SET -> handleParamSet(frame.payload());
                case MavlinkProtocol.SET_GPS_GLOBAL_ORIGIN -> {
                    byte[] payload = frame.payload();
                    if (payload.length >= 13) {
                        VirtualFlightController vehicle =
                            targetVehicle(Byte.toUnsignedInt(payload[12]), 0);
                        if (vehicle != null) {
                            handleSetGpsGlobalOrigin(vehicle);
                        }
                    }
                }
                case MavlinkProtocol.SET_POSITION_TARGET_LOCAL_NED -> {
                    MavlinkMessages.PositionTargetLocalNed target =
                        MavlinkMessages.decodePositionTargetLocalNed(frame.payload());
                    VirtualFlightController vehicle =
                        targetVehicle(target.targetSystem(), target.targetComponent());
                    if (vehicle != null) {
                        handlePositionTarget(vehicle, target);
                    }
                }
                default -> {
                    // Ground-control heartbeats and unsupported diagnostic requests are harmless.
                }
            }
        } catch (IllegalArgumentException exception) {
            log.log(
                "warn",
                "Rejected malformed MAVLink message " + frame.messageId() + ": " + exception.getMessage()
            );
        }
    }

    /**
     * The vehicle a message is for, or null when this world does not fly it.
     *
     * <p>A target system id of zero means "whoever is listening", which for a single-aircraft
     * setup is that aircraft and for a fleet is the first one - the same drone every
     * single-drone path means. Anything else has to name a vehicle this world flies: applying
     * a message to whichever drone happens to be first would fly the wrong aircraft.
     */
    private VirtualFlightController targetVehicle(int targetSystem, int targetComponent) {
        final int systemId = targetSystem == 0
            ? droneManager.snapshot().systemId()
            : targetSystem;
        VirtualFlightController vehicle = droneManager.vehicleForSystemId(systemId);
        if (vehicle == null) {
            log.log("debug", "Ignored MAVLink message for unknown system id " + systemId);
            return null;
        }
        final int componentId = vehicle.snapshot().componentId();
        if (targetComponent != 0 && targetComponent != componentId) {
            log.log(
                "debug",
                "Ignored MAVLink message for unknown component " + targetComponent
                    + " on system id " + systemId
            );
            return null;
        }
        return vehicle;
    }

    private void handleCommandLong(
        VirtualFlightController vehicle, MavlinkMessages.CommandLong command
    ) {
        executor.execute(() -> {
            int result = switch (command.command()) {
                case MavlinkProtocol.MAV_CMD_DO_SET_MODE -> setMode(vehicle, Math.round(command.params()[1]));
                case MavlinkProtocol.MAV_CMD_COMPONENT_ARM_DISARM ->
                    vehicle.setArmed(command.params()[0] >= 0.5f)
                        ? MavlinkProtocol.MAV_RESULT_ACCEPTED
                        : MavlinkProtocol.MAV_RESULT_TEMPORARILY_REJECTED;
                case MavlinkProtocol.MAV_CMD_NAV_TAKEOFF ->
                    vehicle.takeoff(command.params()[6])
                        ? MavlinkProtocol.MAV_RESULT_ACCEPTED
                        : MavlinkProtocol.MAV_RESULT_TEMPORARILY_REJECTED;
                case MavlinkProtocol.MAV_CMD_NAV_LAND ->
                    vehicle.land()
                        ? MavlinkProtocol.MAV_RESULT_ACCEPTED
                        : MavlinkProtocol.MAV_RESULT_TEMPORARILY_REJECTED;
                case MavlinkProtocol.MAV_CMD_DO_SET_HOME -> {
                    sendOriginTelemetry(vehicle);
                    yield MavlinkProtocol.MAV_RESULT_ACCEPTED;
                }
                case MavlinkProtocol.MAV_CMD_SET_MESSAGE_INTERVAL ->
                    MavlinkProtocol.MAV_RESULT_ACCEPTED;
                case MavlinkProtocol.MAV_CMD_REQUEST_MESSAGE -> {
                    sendRequestedMessage(vehicle, Math.round(command.params()[0]));
                    yield MavlinkProtocol.MAV_RESULT_ACCEPTED;
                }
                default -> MavlinkProtocol.MAV_RESULT_UNSUPPORTED;
            };
            send(
                vehicle,
                MavlinkProtocol.COMMAND_ACK,
                MavlinkMessages.commandAck(command.command(), result)
            );
            log.log(
                "info",
                "MAVLink command " + command.command() + " completed with result " + result
                    + " for " + vehicle.snapshot().droneId()
            );
        });
    }

    private void handleSetMode(VirtualFlightController vehicle, MavlinkMessages.SetMode setMode) {
        executor.execute(() -> setMode(vehicle, (int) setMode.customMode()));
    }

    private int setMode(VirtualFlightController vehicle, int customMode) {
        return vehicle.setMode(customMode)
            ? MavlinkProtocol.MAV_RESULT_ACCEPTED
            : MavlinkProtocol.MAV_RESULT_UNSUPPORTED;
    }

    private void handleParamRequestRead(
        VirtualFlightController vehicle, MavlinkMessages.ParamRequestRead request
    ) {
        Parameter parameter = request.parameterId().isEmpty()
            ? parameterAt(request.parameterIndex())
            : parameters.get(request.parameterId());
        if (parameter != null) {
            sendParameter(vehicle, parameter);
        }
    }

    private void handleParamRequestList(VirtualFlightController vehicle) {
        parameters.values().forEach(parameter -> sendParameter(vehicle, parameter));
    }

    private void handleParamSet(byte[] payload) {
        if (payload.length < 23) {
            throw new IllegalArgumentException("PARAM_SET payload is shorter than 23 bytes");
        }
        ByteBuffer data = MavlinkPayloads.reader(payload);
        float value = data.getFloat();
        int targetSystem = Byte.toUnsignedInt(data.get());
        int targetComponent = Byte.toUnsignedInt(data.get());
        VirtualFlightController vehicle = targetVehicle(targetSystem, targetComponent);
        if (vehicle == null) {
            return;
        }
        byte[] id = new byte[16];
        data.get(id);
        String name = readAscii(id);
        Parameter current = parameters.get(name);
        if (current != null) {
            Parameter updated = new Parameter(current.name(), value, current.type(), current.index());
            parameters.put(name, updated);
            sendParameter(vehicle, updated);
        }
    }

    private void handleSetGpsGlobalOrigin(VirtualFlightController vehicle) {
        sendOriginTelemetry(vehicle);
    }

    private void handlePositionTarget(
        VirtualFlightController vehicle, MavlinkMessages.PositionTargetLocalNed target
    ) {
        if (target.coordinateFrame() != MavlinkProtocol.MAV_FRAME_LOCAL_NED
            || !target.commandsAnyChannel()) {
            return;
        }
        // The backend holds forwarding when no operator link owns the vehicle. A
        // relay answers by pausing its multicast; here the equivalent is to stop
        // accepting new setpoints, leaving the vehicle on the last one it took.
        if (!forwardingHold.acceptsSetpoints()) {
            log.log("debug", "Ignored local NED setpoint while motion-capture forwarding is held");
            return;
        }
        // The backend's PVA setpoints use many type_mask combinations, so every
        // commanded channel is mapped instead of matching whole masks: a dropped
        // frame leaves the operator with a virtual vehicle that simply ignores
        // the command.
        LocalSetpoint setpoint = toLocalSetpoint(target);
        executor.execute(() -> {
            boolean accepted = vehicle.setLocalSetpoint(setpoint);
            if (!accepted) {
                log.log(
                    "debug",
                    "Rejected local NED setpoint " + setpoint + " for "
                        + vehicle.snapshot().droneId()
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

    private void sendRequestedMessage(VirtualFlightController vehicle, int messageId) {
        VirtualDroneSnapshot state = vehicle.snapshot();
        switch (messageId) {
            case MavlinkProtocol.ATTITUDE -> send(vehicle, messageId, MavlinkMessages.attitude(state));
            case MavlinkProtocol.LOCAL_POSITION_NED ->
                send(vehicle, messageId, MavlinkMessages.localPositionNed(state));
            case MavlinkProtocol.EKF_STATUS_REPORT ->
                send(vehicle, messageId, MavlinkMessages.ekfStatusReport());
            case MavlinkProtocol.GPS_GLOBAL_ORIGIN ->
                send(vehicle, messageId, MavlinkMessages.gpsGlobalOrigin());
            case MavlinkProtocol.HOME_POSITION ->
                send(vehicle, messageId, MavlinkMessages.homePosition());
            case MavlinkProtocol.EXTENDED_SYS_STATE ->
                send(vehicle, messageId, MavlinkMessages.extendedSysState(state));
            default -> {
                // An accepted request is allowed to have no response for unsupported diagnostics.
            }
        }
    }

    private void sendOriginTelemetry(VirtualFlightController vehicle) {
        send(
            vehicle,
            MavlinkProtocol.GPS_GLOBAL_ORIGIN,
            MavlinkMessages.gpsGlobalOrigin()
        );
        send(
            vehicle,
            MavlinkProtocol.HOME_POSITION,
            MavlinkMessages.homePosition()
        );
    }

    private void sendParameter(VirtualFlightController vehicle, Parameter parameter) {
        send(
            vehicle,
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

    private void send(VirtualFlightController vehicle, int messageId, byte[] payload) {
        VirtualDroneSnapshot state = vehicle.snapshot();
        // Speak as the vehicle being answered, not as whichever one was created first.
        outbound.accept(new MavlinkOutboundMessage(
            state.systemId(),
            state.componentId(),
            messageId,
            payload
        ));
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
