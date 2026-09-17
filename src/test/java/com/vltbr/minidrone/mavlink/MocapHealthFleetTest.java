package com.vltbr.minidrone.mavlink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vltbr.minidrone.sim.VirtualDroneFleet;
import com.vltbr.minidrone.sim.VirtualDroneState;
import com.vltbr.minidrone.sim.VehicleModel;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The fleet block on the health beacon, and the promise that one drone changes nothing.
 *
 * <p>This ships before the backend reads the block, so the test that matters most is the
 * first one: with a single drone the payload must come back byte for byte, because every
 * parser and every existing setup - including the real mocap path, which never touches this
 * code - must see exactly what it saw before.
 */
class MocapHealthFleetTest {
    private static final String SAMPLE = "{\"schema\":\"mocap_relay_health_v1\","
        + "\"expected_drone_id\":\"minecraft_drone_01\","
        + "\"last_source_pose\":{\"position_m\":[0.000000,0.000000,0.000000]}}";

    private static VirtualDroneState drone(VirtualDroneFleet fleet) {
        VirtualDroneState created = fleet.create(VehicleModel.DEFAULTS);
        created.setMode(MavlinkProtocol.ARDUCOPTER_MODE_GUIDED);
        created.setArmed(true);
        created.takeoff(1.5);
        for (int tick = 0; tick < 60; tick++) {
            created.tick();
        }
        return created;
    }

    @Test
    void oneDroneLeavesThePayloadExactlyAsItWas() {
        VirtualDroneFleet fleet = new VirtualDroneFleet();
        VirtualDroneState only = drone(fleet);

        String unchanged = MocapHealthFleet.appendFleetDrones(SAMPLE, List.of(only));
        assertEquals(SAMPLE, unchanged, "byte for byte, with one drone");
    }

    @Test
    void anEmptyFleetAlsoLeavesItAlone() {
        assertEquals(SAMPLE, MocapHealthFleet.appendFleetDrones(SAMPLE, List.of()));
        assertEquals(SAMPLE, MocapHealthFleet.appendFleetDrones(SAMPLE, null));
        assertEquals(null, MocapHealthFleet.appendFleetDrones(null, List.of()));
    }

    /** With two drones the block appears, both are named, and the payload stays one object. */
    @Test
    void twoDronesAreBothReported() {
        VirtualDroneFleet fleet = new VirtualDroneFleet();
        VirtualDroneState one = drone(fleet);
        VirtualDroneState two = drone(fleet);

        String payload = MocapHealthFleet.appendFleetDrones(SAMPLE, List.of(one, two));

        assertTrue(payload.startsWith("{"), "still one JSON object");
        assertTrue(payload.endsWith("}"), "and still closed");
        assertTrue(payload.contains("\"drones\":["), "the block is there");
        assertTrue(payload.contains("\"drone_id\":\"minecraft_drone_01\""), "first drone");
        assertTrue(payload.contains("\"drone_id\":\"minecraft_drone_02\""), "second drone");
        assertTrue(payload.contains("\"system_id\":2"), "with its own MAVLink identity");
        assertTrue(payload.contains("\"safety_latched\":false"), "and its own latch state");
        // The original fields survive: the block was appended, not woven in.
        assertTrue(payload.contains("\"schema\":\"mocap_relay_health_v1\""));
        assertTrue(payload.contains("\"expected_drone_id\":\"minecraft_drone_01\""));
        assertTrue(payload.contains("\"last_source_pose\""), "the single-drone pose is still there");
    }

    /** The latch is per drone: one crashed vehicle must not mark the others. */
    @Test
    void aLatchedDroneIsMarkedOnItsOwnEntry() {
        VirtualDroneFleet fleet = new VirtualDroneFleet();
        VirtualDroneState one = drone(fleet);
        VirtualDroneState two = drone(fleet);
        one.crash();

        String payload = MocapHealthFleet.appendFleetDrones(SAMPLE, List.of(one, two));

        assertTrue(one.safetyLatched(), "the crash latched the first");
        assertFalse(two.safetyLatched(), "and only the first");
        int firstEntry = payload.indexOf("minecraft_drone_01");
        int secondEntry = payload.indexOf("minecraft_drone_02");
        assertTrue(firstEntry < secondEntry, "creation order is kept");
        assertTrue(
            payload.substring(firstEntry, secondEntry).contains("\"safety_latched\":true"),
            "the crashed drone carries the latch"
        );
        assertTrue(
            payload.substring(secondEntry).contains("\"safety_latched\":false"),
            "the other one does not"
        );
    }
}
