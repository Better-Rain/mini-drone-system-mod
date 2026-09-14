package com.vltbr.minidrone.mavlink;

import com.vltbr.minidrone.MiniDroneMod;
import com.vltbr.minidrone.sim.VirtualDroneManager;
import com.vltbr.minidrone.sim.VirtualDroneSnapshot;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class MavlinkTransport {
    private static final InetAddress IPV4_LOOPBACK = ipv4Loopback();
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_REMOTE_PORT = 14561;
    private static final int DEFAULT_LOCAL_PORT = 0;
    private static final int DEFAULT_MOCAP_HEALTH_PORT = 18151;
    private static final int DEFAULT_MOCAP_CONTROL_PORT = 18152;
    // The backend stores this as text and compares it with the advertised value
    // verbatim, so the two sides must agree exactly. The isolated profile binds
    // the slot id, not the MAVLink system id, which is why this is the default.
    private static final String DEFAULT_MOCAP_EXPECTED_DRONE_ID = "minecraft_drone_01";
    private static final long FAST_TELEMETRY_PERIOD_MS = 50L;
    private static final long EXTENDED_STATE_PERIOD_MS = 200L;
    private static final long SLOW_TELEMETRY_PERIOD_MS = 500L;
    private static final long HEARTBEAT_PERIOD_MS = 1000L;
    private static final long MOCAP_HEALTH_PERIOD_MS = 250L;

    private final VirtualDroneManager droneManager;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean mocapControlRunning = new AtomicBoolean(false);
    private final ConcurrentLinkedQueue<MavlinkOutboundMessage> outbound =
        new ConcurrentLinkedQueue<>();
    private final int remotePort;
    private final int localPort;
    private final InetAddress remoteAddress;
    private final AtomicBoolean mocapHealthEnabled = new AtomicBoolean(false);
    private final int mocapHealthPort;
    private final int mocapControlPort;
    private final String mocapExpectedDroneId;
    private final MocapControlServer mocapControlServer;
    private final VirtualAutopilot autopilot;
    private final AtomicLong receivedPackets = new AtomicLong();
    private final AtomicLong receivedFrames = new AtomicLong();
    private final AtomicLong transmittedFrames = new AtomicLong();
    private final AtomicLong healthBeaconsSent = new AtomicLong();
    private volatile boolean socketBound;
    private volatile int boundLocalPort;
    private volatile long lastInboundAtMs;
    private volatile long lastOutboundAtMs;
    private volatile int lastInboundMessageId = -1;
    private volatile String lastInboundEndpoint = "-";
    private Thread ioThread;
    private Thread mocapControlThread;
    private int sequence;

    public MavlinkTransport(MinecraftServer server, VirtualDroneManager droneManager) {
        this.droneManager = droneManager;
        try {
            String host = System.getProperty("mini_drone.mavlink.remote_host", DEFAULT_HOST);
            remoteAddress = InetAddress.getByName(host);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to resolve MAVLink remote host", exception);
        }
        remotePort = Integer.getInteger("mini_drone.mavlink.remote_port", DEFAULT_REMOTE_PORT);
        localPort = Integer.getInteger("mini_drone.mavlink.local_port", DEFAULT_LOCAL_PORT);
        mocapHealthEnabled.set(Boolean.getBoolean("mini_drone.mocap.enabled"));
        mocapHealthPort = Integer.getInteger(
            "mini_drone.mocap.health_port",
            DEFAULT_MOCAP_HEALTH_PORT
        );
        mocapControlPort = Integer.getInteger(
            "mini_drone.mocap.control_port",
            DEFAULT_MOCAP_CONTROL_PORT
        );
        mocapExpectedDroneId = resolveMocapExpectedDroneId(
            System.getProperty("mini_drone.mocap.expected_drone_id")
        );
        mocapControlServer = new MocapControlServer(mocapControlPort);
        autopilot = new VirtualAutopilot(server, droneManager, outbound::add);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        startMocapControlIfNeeded();
        ioThread = new Thread(this::runIoLoop, "mini-drone-mavlink");
        ioThread.setDaemon(true);
        ioThread.start();
    }

    public synchronized void setMocapEnabled(boolean enabled) {
        boolean changed = mocapHealthEnabled.getAndSet(enabled) != enabled;
        if (enabled) {
            startMocapControlIfNeeded();
        } else if (changed || mocapControlRunning.get()) {
            stopMocapControl();
        }
    }

    public void stop() {
        boolean mavlinkWasRunning = running.getAndSet(false);
        boolean mocapControlWasRunning = mocapControlRunning.get();
        mocapHealthEnabled.set(false);
        if (!mavlinkWasRunning && !mocapControlWasRunning) {
            return;
        }
        socketBound = false;
        boundLocalPort = 0;
        if (ioThread != null) {
            ioThread.interrupt();
            try {
                ioThread.join(1500L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            ioThread = null;
        }
        stopMocapControl();
    }

    public MavlinkLinkStatus status() {
        return new MavlinkLinkStatus(
            running.get(),
            socketBound,
            boundLocalPort,
            remoteAddress.getHostAddress(),
            remotePort,
            receivedPackets.get(),
            receivedFrames.get(),
            transmittedFrames.get(),
            healthBeaconsSent.get(),
            lastInboundAtMs,
            lastOutboundAtMs,
            lastInboundMessageId,
            lastInboundEndpoint,
            mocapHealthEnabled.get(),
            mocapExpectedDroneId,
            mocapControlPort,
            mocapControlServer.isBound()
        );
    }

    private void runIoLoop() {
        long nextFastTelemetryAt = 0L;
        long nextExtendedStateAt = 0L;
        long nextSlowTelemetryAt = 0L;
        long nextHeartbeatAt = 0L;
        long nextMocapHealthAt = 0L;
        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.bind(new InetSocketAddress(IPV4_LOOPBACK, localPort));
            socket.setSoTimeout(20);
            boundLocalPort = socket.getLocalPort();
            socketBound = true;
            MiniDroneMod.LOGGER.info(
                "MAVLink UDP transport bound to {} and targeting {}:{}",
                socket.getLocalSocketAddress(),
                remoteAddress.getHostAddress(),
                remotePort
            );
            if (mocapHealthEnabled.get()) {
                MiniDroneMod.LOGGER.warn(
                    "Virtual motion-capture health beacon enabled for 127.0.0.1:{}; use only with the isolated Minecraft backend profile",
                    mocapHealthPort
                );
            }

            while (running.get()) {
                long now = System.currentTimeMillis();
                VirtualDroneSnapshot state = droneManager.snapshot();
                if (now >= nextFastTelemetryAt) {
                    send(socket, MavlinkProtocol.ATTITUDE, MavlinkMessages.attitude(state));
                    send(
                        socket,
                        MavlinkProtocol.LOCAL_POSITION_NED,
                        MavlinkMessages.localPositionNed(state)
                    );
                    nextFastTelemetryAt = now + FAST_TELEMETRY_PERIOD_MS;
                }
                if (now >= nextExtendedStateAt) {
                    send(
                        socket,
                        MavlinkProtocol.EXTENDED_SYS_STATE,
                        MavlinkMessages.extendedSysState(state)
                    );
                    nextExtendedStateAt = now + EXTENDED_STATE_PERIOD_MS;
                }
                if (now >= nextSlowTelemetryAt) {
                    send(socket, MavlinkProtocol.SYS_STATUS, MavlinkMessages.sysStatus(state));
                    send(
                        socket,
                        MavlinkProtocol.EKF_STATUS_REPORT,
                        MavlinkMessages.ekfStatusReport()
                    );
                    nextSlowTelemetryAt = now + SLOW_TELEMETRY_PERIOD_MS;
                }
                if (now >= nextHeartbeatAt) {
                    send(socket, MavlinkProtocol.HEARTBEAT, MavlinkMessages.heartbeat(state));
                    nextHeartbeatAt = now + HEARTBEAT_PERIOD_MS;
                }
                if (mocapHealthEnabled.get() && now >= nextMocapHealthAt) {
                    sendMocapHealth(socket, state);
                    nextMocapHealthAt = now + MOCAP_HEALTH_PERIOD_MS;
                }

                MavlinkOutboundMessage message;
                while ((message = outbound.poll()) != null) {
                    send(socket, message.messageId(), message.payload());
                }

                byte[] buffer = new byte[4096];
                DatagramPacket inbound = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(inbound);
                    receivedPackets.incrementAndGet();
                    lastInboundAtMs = System.currentTimeMillis();
                    lastInboundEndpoint = String.valueOf(inbound.getSocketAddress());
                    var frames = MavlinkV1Codec.decode(inbound.getData(), inbound.getLength());
                    receivedFrames.addAndGet(frames.size());
                    if (!frames.isEmpty()) {
                        lastInboundMessageId = frames.get(frames.size() - 1).messageId();
                    }
                    frames.forEach(autopilot::handle);
                } catch (SocketTimeoutException ignored) {
                    // The short timeout drives periodic telemetry and responsive shutdown.
                }
            }
        } catch (IOException exception) {
            if (running.get()) {
                MiniDroneMod.LOGGER.error("MAVLink UDP transport stopped unexpectedly", exception);
            }
        } finally {
            socketBound = false;
            boundLocalPort = 0;
            running.set(false);
        }
    }

    private void runMocapControlLoop() {
        try {
            mocapControlServer.run(mocapControlRunning, port -> MiniDroneMod.LOGGER.info(
                "Virtual motion-capture control bound to 127.0.0.1:{}",
                port
            ));
        } catch (IOException exception) {
            if (mocapControlRunning.get()) {
                MiniDroneMod.LOGGER.error(
                    "Virtual motion-capture control endpoint stopped unexpectedly",
                    exception
                );
            }
        } finally {
            mocapControlRunning.set(false);
        }
    }

    private synchronized void startMocapControlIfNeeded() {
        if (!running.get() || !mocapHealthEnabled.get()
            || !mocapControlRunning.compareAndSet(false, true)) {
            return;
        }
        mocapControlThread = new Thread(this::runMocapControlLoop, "mini-drone-mocap-control");
        mocapControlThread.setDaemon(true);
        mocapControlThread.start();
    }

    private synchronized void stopMocapControl() {
        mocapControlRunning.set(false);
        mocapControlServer.close();
        if (mocapControlThread != null) {
            mocapControlThread.interrupt();
            try {
                mocapControlThread.join(1500L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            mocapControlThread = null;
        }
    }

    private void send(DatagramSocket socket, int messageId, byte[] payload) throws IOException {
        VirtualDroneSnapshot state = droneManager.snapshot();
        byte[] frame = MavlinkV1Codec.encode(
            sequence++ & 0xFF,
            state.systemId(),
            state.componentId(),
            messageId,
            payload
        );
        socket.send(new DatagramPacket(frame, frame.length, remoteAddress, remotePort));
        transmittedFrames.incrementAndGet();
        lastOutboundAtMs = System.currentTimeMillis();
    }

    private void sendMocapHealth(DatagramSocket socket, VirtualDroneSnapshot state)
        throws IOException {
        String payload = mocapHealthPayload(
            state,
            System.currentTimeMillis() * 1000L,
            mocapExpectedDroneId
        );
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(
            bytes,
            bytes.length,
            IPV4_LOOPBACK,
            mocapHealthPort
        ));
        healthBeaconsSent.incrementAndGet();
    }

    static String resolveMocapExpectedDroneId(String configured) {
        if (configured == null) {
            return DEFAULT_MOCAP_EXPECTED_DRONE_ID;
        }
        String trimmed = configured.trim();
        return trimmed.isEmpty() ? DEFAULT_MOCAP_EXPECTED_DRONE_ID : trimmed;
    }

    static String mocapHealthPayload(
        VirtualDroneSnapshot state,
        long wallTimeUnixUs,
        String expectedDroneId
    ) {
        return String.format(
            Locale.ROOT,
            "{\"schema\":\"mocap_relay_health_v1\","
                + "\"source_mode\":\"minecraft_virtual\","
                + "\"wall_time_unix_us\":%d,"
                + "\"healthy\":true,\"safety_latched\":false,"
                + "\"position_only\":false,\"attitude_source\":\"hybrid\","
                + "\"fusion_mode\":\"flight_controller_roll_pitch_external_nav_position_yaw\","
                + "\"roll_pitch_source\":\"flight_controller\","
                + "\"yaw_source\":\"motion_capture_external_nav\","
                + "\"expected_drone_id\":%s,\"tracking_age_ms\":0.0,"
            + "\"forward_rate_hz\":20.0,\"orientation_held\":false,"
            + "\"tracking_holdover_active\":false,"
            + "\"last_forwarded_pose\":{\"position_m\":[%.6f,%.6f,%.6f],"
                + "\"roll_pitch_yaw_rad\":[%.6f,%.6f,%.6f]}}",
            wallTimeUnixUs,
            jsonString(expectedDroneId),
            state.northM(),
            state.eastM(),
            state.downM(),
            state.rollRad(),
            state.pitchRad(),
            state.yawRad()
        );
    }

    // The backend compares the advertised id against its configured value as
    // text, so it has to be a JSON string and it has to be escaped.
    static String jsonString(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 2);
        escaped.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        escaped.append('"');
        return escaped.toString();
    }

    private static InetAddress ipv4Loopback() {
        try {
            return InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        } catch (java.net.UnknownHostException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
