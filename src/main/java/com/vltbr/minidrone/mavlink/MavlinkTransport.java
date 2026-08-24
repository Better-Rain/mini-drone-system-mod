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
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_REMOTE_PORT = 14561;
    private static final int DEFAULT_LOCAL_PORT = 0;
    private static final int DEFAULT_MOCAP_HEALTH_PORT = 15151;
    private static final long FAST_TELEMETRY_PERIOD_MS = 50L;
    private static final long EXTENDED_STATE_PERIOD_MS = 200L;
    private static final long SLOW_TELEMETRY_PERIOD_MS = 500L;
    private static final long HEARTBEAT_PERIOD_MS = 1000L;
    private static final long MOCAP_HEALTH_PERIOD_MS = 250L;

    private final VirtualDroneManager droneManager;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ConcurrentLinkedQueue<MavlinkOutboundMessage> outbound =
        new ConcurrentLinkedQueue<>();
    private final int remotePort;
    private final int localPort;
    private final InetAddress remoteAddress;
    private final boolean mocapHealthEnabled;
    private final int mocapHealthPort;
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
        mocapHealthEnabled = Boolean.getBoolean("mini_drone.mocap.enabled");
        mocapHealthPort = Integer.getInteger(
            "mini_drone.mocap.health_port",
            DEFAULT_MOCAP_HEALTH_PORT
        );
        autopilot = new VirtualAutopilot(server, droneManager, outbound::add);
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        ioThread = new Thread(this::runIoLoop, "mini-drone-mavlink");
        ioThread.setDaemon(true);
        ioThread.start();
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
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
            mocapHealthEnabled
        );
    }

    private void runIoLoop() {
        long nextFastTelemetryAt = 0L;
        long nextExtendedStateAt = 0L;
        long nextSlowTelemetryAt = 0L;
        long nextHeartbeatAt = 0L;
        long nextMocapHealthAt = 0L;
        try (DatagramSocket socket = new DatagramSocket(null)) {
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), localPort));
            socket.setSoTimeout(20);
            boundLocalPort = socket.getLocalPort();
            socketBound = true;
            MiniDroneMod.LOGGER.info(
                "MAVLink UDP transport bound to {} and targeting {}:{}",
                socket.getLocalSocketAddress(),
                remoteAddress.getHostAddress(),
                remotePort
            );
            if (mocapHealthEnabled) {
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
                if (mocapHealthEnabled && now >= nextMocapHealthAt) {
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
        String payload = String.format(
            Locale.ROOT,
            "{\"schema\":\"mocap_relay_health_v1\","
                + "\"source_mode\":\"minecraft_virtual\","
                + "\"wall_time_unix_us\":%d,"
                + "\"healthy\":true,\"safety_latched\":false,"
                + "\"position_only\":false,\"attitude_source\":\"hybrid\","
                + "\"fusion_mode\":\"flight_controller_roll_pitch_external_nav_position_yaw\","
                + "\"roll_pitch_source\":\"flight_controller\","
                + "\"yaw_source\":\"motion_capture_external_nav\","
                + "\"expected_drone_id\":%d,\"tracking_age_ms\":0.0,"
                + "\"forward_rate_hz\":20.0,\"orientation_held\":false,"
                + "\"tracking_holdover_active\":false,"
                + "\"last_forwarded_pose\":{\"position_m\":[%.6f,%.6f,%.6f],"
                + "\"roll_pitch_yaw_rad\":[%.6f,%.6f,%.6f]}}",
            System.currentTimeMillis() * 1000L,
            state.systemId(),
            state.northM(),
            state.eastM(),
            state.downM(),
            state.rollRad(),
            state.pitchRad(),
            state.yawRad()
        );
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        socket.send(new DatagramPacket(
            bytes,
            bytes.length,
            InetAddress.getLoopbackAddress(),
            mocapHealthPort
        ));
        healthBeaconsSent.incrementAndGet();
    }
}
