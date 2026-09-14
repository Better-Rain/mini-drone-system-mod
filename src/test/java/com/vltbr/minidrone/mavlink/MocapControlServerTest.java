package com.vltbr.minidrone.mavlink;

import org.junit.jupiter.api.Test;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MocapControlServerTest {
    @Test
    void statusProbeReturnsTheDiscoveryContract() throws Exception {
        try (TestServer server = new TestServer(); DatagramSocket client = new DatagramSocket()) {
            assertEquals(
                "{\"schema\":\"mocap_relay_control_v1\",\"ok\":true,\"action\":\"status\","
                    + "\"message\":\"virtual motion-capture source is online\","
                    + "\"source_packet_age_ms\":0,\"safety_latched\":false,"
                    + "\"forwarding_held\":false}",
                sendAndReceive(client, server.port(), "VLT_RELAY_STATUS_V1")
            );
        }
    }

    @Test
    void reconnectProbeAcceptsTrailingCrLf() throws Exception {
        try (TestServer server = new TestServer(); DatagramSocket client = new DatagramSocket()) {
            String response = sendAndReceive(client, server.port(), "VLT_RELAY_RECONNECT_V1\r\n");
            assertTrue(response.contains("\"action\":\"reconnect\""));
            assertTrue(response.contains("\"ok\":true"));
        }
    }

    // The backend holds forwarding when the last vehicle link is disconnected and
    // resumes it when a link is taken back. Answering with a valid
    // mocap_relay_control_v1 reply is what keeps it from reporting
    // mocap.forwarding_hold_failed against a source that is behaving correctly.
    @Test
    void holdAndResumeReportTheStateAndStatusCarriesIt() throws Exception {
        try (TestServer server = new TestServer(); DatagramSocket client = new DatagramSocket()) {
            String hold = sendAndReceive(client, server.port(), "VLT_RELAY_HOLD_FORWARDING_V1");
            assertTrue(hold.contains("\"action\":\"hold\""), hold);
            assertTrue(hold.contains("\"ok\":true"), hold);
            assertTrue(hold.contains("\"forwarding_held\":true"), hold);
            assertTrue(server.hold().held());

            String status = sendAndReceive(client, server.port(), "VLT_RELAY_STATUS_V1");
            assertTrue(status.contains("\"forwarding_held\":true"), status);
            assertTrue(server.hold().held(), "a status probe must not resume forwarding");

            String resume = sendAndReceive(client, server.port(), "VLT_RELAY_RESUME_FORWARDING_V1");
            assertTrue(resume.contains("\"action\":\"resume\""), resume);
            assertTrue(resume.contains("\"forwarding_held\":false"), resume);
            assertFalse(server.hold().held());
        }
    }

    @Test
    void aReconnectClearsAnActiveHold() throws Exception {
        try (TestServer server = new TestServer(); DatagramSocket client = new DatagramSocket()) {
            assertTrue(sendAndReceive(client, server.port(), "VLT_RELAY_HOLD_FORWARDING_V1")
                .contains("\"forwarding_held\":true"));
            assertTrue(server.hold().held());

            String response = sendAndReceive(client, server.port(), "VLT_RELAY_RECONNECT_V1");
            assertTrue(response.contains("\"action\":\"reconnect\""), response);
            assertTrue(response.contains("\"forwarding_held\":false"), response);
            assertFalse(server.hold().held());
        }
    }

    @Test
    void unknownProbeIsIgnoredWithoutStoppingTheEndpoint() throws Exception {
        try (TestServer server = new TestServer(); DatagramSocket client = new DatagramSocket()) {
            client.setSoTimeout(120);
            byte[] unknown = "VLT_RELAY_UNSUPPORTED_V1".getBytes(StandardCharsets.US_ASCII);
            client.send(new DatagramPacket(unknown, unknown.length, server.address(), server.port()));
            try {
                client.receive(new DatagramPacket(new byte[512], 512));
                throw new AssertionError("unknown probe unexpectedly received a response");
            } catch (java.net.SocketTimeoutException expected) {
                // An unknown request intentionally has no response.
            }

            assertEquals(
                "status",
                actionFrom(sendAndReceive(client, server.port(), "VLT_RELAY_STATUS_V1"))
            );
            assertTrue(server.isBound());
        }
    }

    @Test
    void isUnboundUntilItsRunLoopStarts() {
        MocapControlServer server = new MocapControlServer(0, new ForwardingHold());
        assertFalse(server.isBound());
        assertEquals(0, server.boundPort());
    }

    @Test
    void closesThePortAndCanBeStartedAgainOnTheSamePort() throws Exception {
        int port;
        try (TestServer server = new TestServer()) {
            port = server.port();
            server.close();
            assertFalse(server.isBound());
            assertEquals(0, server.port());
            server.close();
        }

        try (TestServer restarted = new TestServer(port)) {
            assertEquals(port, restarted.port());
            try (DatagramSocket client = new DatagramSocket()) {
                assertEquals(
                    "status",
                    actionFrom(sendAndReceive(client, restarted.port(), "VLT_RELAY_STATUS_V1"))
                );
            }
        }
    }

    private static String sendAndReceive(DatagramSocket client, int port, String request) throws Exception {
        client.setSoTimeout(1_000);
        byte[] requestBytes = request.getBytes(StandardCharsets.US_ASCII);
        client.send(new DatagramPacket(requestBytes, requestBytes.length, TestServer.LOOPBACK, port));
        DatagramPacket response = new DatagramPacket(new byte[512], 512);
        client.receive(response);
        return new String(
            response.getData(), response.getOffset(), response.getLength(), StandardCharsets.UTF_8
        );
    }

    private static String actionFrom(String response) {
        return response.contains("\"action\":\"status\"") ? "status" : "unknown";
    }

    private static final class TestServer implements AutoCloseable {
        private static final java.net.InetAddress LOOPBACK = java.net.InetAddress.getLoopbackAddress();

        private final AtomicBoolean running = new AtomicBoolean(true);
        private final ForwardingHold hold = new ForwardingHold();
        private final MocapControlServer server;
        private final Thread thread = new Thread(this::run, "mocap-control-test");

        TestServer() throws Exception {
            this(0);
        }

        TestServer(int port) throws Exception {
            server = new MocapControlServer(port, hold);
            thread.start();
            long deadline = System.nanoTime() + 1_000_000_000L;
            while (!server.isBound() && System.nanoTime() < deadline) {
                Thread.sleep(5L);
            }
            assertTrue(server.isBound(), "control endpoint did not bind within one second");
        }

        ForwardingHold hold() {
            return hold;
        }

        java.net.InetAddress address() {
            return LOOPBACK;
        }

        int port() {
            return server.boundPort();
        }

        boolean isBound() {
            return server.isBound();
        }

        @Override
        public void close() throws Exception {
            running.set(false);
            server.close();
            thread.join(1_000L);
            assertFalse(thread.isAlive(), "control endpoint did not stop within one second");
        }

        private void run() {
            try {
                server.run(running, ignored -> {});
            } catch (Exception exception) {
                throw new AssertionError("control endpoint stopped unexpectedly", exception);
            }
        }
    }
}
