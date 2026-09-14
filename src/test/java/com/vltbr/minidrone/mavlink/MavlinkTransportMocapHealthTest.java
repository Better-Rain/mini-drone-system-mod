package com.vltbr.minidrone.mavlink;

import com.vltbr.minidrone.sim.VirtualDroneSnapshot;
import com.vltbr.minidrone.sim.VirtualDroneState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavlinkTransportMocapHealthTest {
    private static VirtualDroneSnapshot snapshot() {
        return new VirtualDroneSnapshot(
            54, 1, "minecraft_drone_01", 1234L,
            true, true, true, false, false, 4,
            1.25, -2.5, 0.75,
            0.1, -0.2, 0.3,
            0.04, -0.05, 0.06,
            0.004, -0.005, 0.006,
            87.5
        );
    }

    @Test
    void emitsTheCompleteBackendHealthContractInLocalNedOrder() {
        assertEquals(
            "{\"schema\":\"mocap_relay_health_v1\","
                + "\"source_mode\":\"minecraft_virtual\","
                + "\"wall_time_unix_us\":1787600000000000,"
                + "\"healthy\":true,\"safety_latched\":false,"
                + "\"position_only\":false,\"attitude_source\":\"hybrid\","
                + "\"fusion_mode\":\"flight_controller_roll_pitch_external_nav_position_yaw\","
                + "\"roll_pitch_source\":\"flight_controller\","
                + "\"yaw_source\":\"motion_capture_external_nav\","
                + "\"expected_drone_id\":\"minecraft_drone_01\",\"tracking_age_ms\":0.0,"
                + "\"forward_rate_hz\":20.0,\"orientation_held\":false,"
                + "\"tracking_holdover_active\":false,"
                + "\"forwarding_held\":false,\"forwarding_hold_reason\":\"\","
                + "\"last_forwarded_pose\":{\"position_m\":[1.250000,-2.500000,0.750000],"
                + "\"roll_pitch_yaw_rad\":[0.040000,-0.050000,0.060000]}}",
            MavlinkTransport.mocapHealthPayload(
                snapshot(),
                1787600000000000L,
                "minecraft_drone_01",
                new ForwardingHold()
            )
        );
    }

    @Test
    void reportsAnActiveForwardingHoldInTheBeacon() {
        ForwardingHold hold = new ForwardingHold();
        hold.apply(ForwardingHold.Change.HOLD);

        String payload = MavlinkTransport.mocapHealthPayload(
            snapshot(), 0L, "minecraft_drone_01", hold);

        assertTrue(payload.contains("\"forwarding_held\":true,"), payload);
        assertTrue(payload.contains("\"forwarding_hold_reason\":\"backend_request\","), payload);
        // A forwarding hold is not a safety latch, and it must not make the
        // source look unhealthy: the backend would refuse every command instead
        // of only the position setpoints the hold is about.
        assertTrue(payload.contains("\"healthy\":true"), payload);
        assertTrue(payload.contains("\"safety_latched\":false"), payload);
    }

    // The backend compares the advertised value against its configured text
    // verbatim, so a numeric configuration has to be advertised as text too.
    @Test
    void advertisesAConfiguredNumericIdentityAsText() {
        String payload = MavlinkTransport.mocapHealthPayload(
            snapshot(), 0L, "54", new ForwardingHold());
        assertTrue(payload.contains("\"expected_drone_id\":\"54\","));
    }

    @Test
    void escapesIdsThatWouldOtherwiseBreakTheJsonDocument() {
        assertEquals("\"a\\\"b\\\\c\"", MavlinkTransport.jsonString("a\"b\\c"));
        assertEquals("\"line\\nbreak\"", MavlinkTransport.jsonString("line\nbreak"));
        assertEquals("\"\\u0007bell\"", MavlinkTransport.jsonString("\u0007bell"));
    }

    @Test
    void fallsBackToTheBuiltInIdentityWhenNothingUsableIsConfigured() {
        assertEquals(
            "minecraft_drone_01",
            MavlinkTransport.resolveMocapExpectedDroneId(null)
        );
        assertEquals(
            "minecraft_drone_01",
            MavlinkTransport.resolveMocapExpectedDroneId("   ")
        );
        assertEquals("54", MavlinkTransport.resolveMocapExpectedDroneId(" 54 "));
    }

    /**
     * The backend refuses horizontal setpoints when its newest motion-capture
     * pose is more than 0.10 m away from the newest flight-controller position
     * (kTakeoffMaximumMocapHorizontalErrorM). The pose is frozen between
     * beacons while the position keeps updating, so the worst-case disagreement
     * is the distance the vehicle covers in one beacon period. Exceeding it does
     * not fail cleanly: admission becomes intermittent and looks like a flaky
     * link. Measured at 250 ms with a drone flying at 1.4 m/s, the error reached
     * 0.26 m and 5 of 6 setpoints were rejected.
     */
    @Test
    void keepsTheHealthBeaconInsideTheBackendConsistencyWindowAtTopSpeed() {
        double backendLimitM = 0.10;
        double travelledPerBeaconPeriodM =
            VirtualDroneState.HORIZONTAL_SPEED_LIMIT_MPS * MavlinkTransport.MOCAP_HEALTH_PERIOD_MS / 1000.0;

        assertTrue(
            travelledPerBeaconPeriodM < backendLimitM,
            "a beacon every " + MavlinkTransport.MOCAP_HEALTH_PERIOD_MS
                + " ms lets the pose lag by " + travelledPerBeaconPeriodM
                + " m, which is outside the backend's " + backendLimitM + " m window"
        );
    }

    @Test
    void advertisesTheBeaconRateTheTransportActuallyUses() {
        String payload = MavlinkTransport.mocapHealthPayload(
            snapshot(), 0L, "minecraft_drone_01", new ForwardingHold());
        assertTrue(
            payload.contains("\"forward_rate_hz\":20.0,"),
            "the beacon must not claim a forwarding rate the transport does not use"
        );
    }

    /**
     * The backend pins the peer it learned from the first packet it receives, so
     * a fresh dynamic port on every start leaves it sending to the port the
     * previous session used. Measured against the real backend: with a dynamic
     * port a restarted mod never re-attached (30 attempts over 30 s, session
     * permanently "pending"), while a stable port re-attached on the first
     * command across three consecutive runs.
     */
    @Test
    void prefersAStableLocalPortSoTheBackendCanReAttach() {
        assertEquals(14601, MavlinkTransport.DEFAULT_LOCAL_PORT);
    }

    @Test
    void fallsBackToADynamicPortWhenTheStableOneIsTaken() throws Exception {
        try (java.net.DatagramSocket holder = new java.net.DatagramSocket(
            new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0))) {
            int takenPort = holder.getLocalPort();

            MavlinkTransport.BoundSocket bound = MavlinkTransport.bindLoopbackSocket(takenPort);
            try (java.net.DatagramSocket socket = bound.socket()) {
                assertFalse(bound.usedRequestedPort(), "the fallback must be reported to the caller");
                assertTrue(socket.isBound(), "the transport must still come up");
                assertTrue(socket.getLocalPort() > 0);
                assertNotEquals(takenPort, socket.getLocalPort());
            }
        }
    }

    @Test
    void usesTheRequestedPortWhenItIsFree() throws Exception {
        try (java.net.DatagramSocket reserved = new java.net.DatagramSocket(
            new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0))) {
            int freePort = reserved.getLocalPort();
            reserved.close();

            MavlinkTransport.BoundSocket bound = MavlinkTransport.bindLoopbackSocket(freePort);
            try (java.net.DatagramSocket socket = bound.socket()) {
                assertTrue(bound.usedRequestedPort(), bound.fallbackReason());
                assertEquals(freePort, socket.getLocalPort());
            }
        }
    }
}
