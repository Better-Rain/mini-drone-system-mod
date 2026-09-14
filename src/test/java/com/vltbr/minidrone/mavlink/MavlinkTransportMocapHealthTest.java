package com.vltbr.minidrone.mavlink;

import com.vltbr.minidrone.sim.VirtualDroneSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MavlinkTransportMocapHealthTest {
    @Test
    void emitsTheCompleteBackendHealthContractInLocalNedOrder() {
        VirtualDroneSnapshot state = new VirtualDroneSnapshot(
            54, 1, "minecraft_drone_01", 1234L,
            true, true, true, false, false, 4,
            1.25, -2.5, 0.75,
            0.1, -0.2, 0.3,
            0.04, -0.05, 0.06,
            0.004, -0.005, 0.006,
            87.5
        );

        assertEquals(
            "{\"schema\":\"mocap_relay_health_v1\","
                + "\"source_mode\":\"minecraft_virtual\","
                + "\"wall_time_unix_us\":1787600000000000,"
                + "\"healthy\":true,\"safety_latched\":false,"
                + "\"position_only\":false,\"attitude_source\":\"hybrid\","
                + "\"fusion_mode\":\"flight_controller_roll_pitch_external_nav_position_yaw\","
                + "\"roll_pitch_source\":\"flight_controller\","
                + "\"yaw_source\":\"motion_capture_external_nav\","
                + "\"expected_drone_id\":54,\"tracking_age_ms\":0.0,"
                + "\"forward_rate_hz\":20.0,\"orientation_held\":false,"
                + "\"tracking_holdover_active\":false,"
                + "\"last_forwarded_pose\":{\"position_m\":[1.250000,-2.500000,0.750000],"
                + "\"roll_pitch_yaw_rad\":[0.040000,-0.050000,0.060000]}}",
            MavlinkTransport.mocapHealthPayload(state, 1787600000000000L)
        );
    }
}
