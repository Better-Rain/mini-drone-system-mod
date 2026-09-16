package com.vltbr.minidrone.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vltbr.minidrone.mavlink.MavlinkProtocol;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * More than one drone, without changing what one drone looks like.
 *
 * <p>The identity rules are the interesting part, because they leave this class: the id and
 * the MAVLink system id are how the backend tells aircraft apart, so the tests pin the
 * first drone's identity to exactly what the single-drone system has always used, and pin
 * the no-reuse rule that keeps a removed identity from being handed to a new vehicle.
 */
class VirtualDroneFleetTest {
    @Test
    void theFirstDroneIsTheIdentityTheSystemAlwaysUsed() {
        VirtualDroneFleet fleet = new VirtualDroneFleet();
        VirtualDroneState first = fleet.create(VehicleModel.DEFAULTS);

        assertNotNull(first);
        assertEquals("minecraft_drone_01", first.snapshot().droneId());
        assertEquals(VirtualDroneFleet.DEFAULT_DRONE_ID, first.snapshot().droneId());
        assertEquals(1, first.snapshot().systemId());
        assertEquals(1, first.snapshot().componentId());
        assertEquals(first, fleet.primary(), "one drone is the primary one");
    }

    @Test
    void eachDroneGetsItsOwnIdAndSystemId() {
        VirtualDroneFleet fleet = new VirtualDroneFleet();
        VirtualDroneState one = fleet.create(VehicleModel.DEFAULTS);
        VirtualDroneState two = fleet.create(VehicleModel.DEFAULTS);
        VirtualDroneState three = fleet.create(VehicleModel.DEFAULTS);

        assertEquals(List.of("minecraft_drone_01", "minecraft_drone_02", "minecraft_drone_03"),
            fleet.ids(), "creation order is kept");
        assertEquals(1, one.snapshot().systemId());
        assertEquals(2, two.snapshot().systemId());
        assertEquals(3, three.snapshot().systemId());
        assertEquals(three, fleet.byId("minecraft_drone_03"));
        assertNull(fleet.byId("minecraft_drone_09"), "unknown ids are not invented");
        assertNull(fleet.byId(null));
    }

    /** A removed identity is not handed to a new vehicle: the monitoring side may still hold it. */
    @Test
    void removingADroneDoesNotRecycleItsIdentity() {
        VirtualDroneFleet fleet = new VirtualDroneFleet();
        fleet.create(VehicleModel.DEFAULTS);
        VirtualDroneState second = fleet.create(VehicleModel.DEFAULTS);
        assertTrue(fleet.remove("minecraft_drone_02"));
        assertFalse(fleet.remove("minecraft_drone_02"), "removing twice is not a removal");
        assertEquals(1, fleet.size());

        VirtualDroneState next = fleet.create(VehicleModel.DEFAULTS);
        assertEquals("minecraft_drone_03", next.snapshot().droneId());
        assertTrue(next.snapshot().systemId() > second.snapshot().systemId(), "and a fresh system id");
    }

    /** Two drones are two vehicles: what happens to one must not appear in the other. */
    @Test
    void droneStateIsIsolated() {
        VirtualDroneFleet fleet = new VirtualDroneFleet();
        VirtualDroneState one = fleet.create(VehicleModel.DEFAULTS);
        VirtualDroneState two = fleet.create(VehicleModel.DEFAULTS);

        one.setMode(MavlinkProtocol.ARDUCOPTER_MODE_GUIDED);
        assertTrue(one.setArmed(true));
        one.takeoff(2.0);
        for (int tick = 0; tick < 60; tick++) {
            one.tick();
        }

        assertTrue(one.snapshot().airborne(), "the first is flying");
        assertFalse(two.snapshot().airborne(), "the second is not");
        assertEquals("minecraft_drone_01", one.snapshot().droneId());
        assertEquals("minecraft_drone_02", two.snapshot().droneId());
        assertTrue(one.snapshot().downM() < -0.5, "it climbed");
        assertEquals(0.0, two.snapshot().downM(), 1.0e-9, "untouched");
    }

    /** Each drone can carry a different airframe, which is the point of the model set. */
    @Test
    void eachDroneCanHaveItsOwnAirframe() {
        VirtualDroneFleet fleet = new VirtualDroneFleet();
        VirtualDroneState heavy = fleet.create(VehicleModel.DEFAULTS.with("mass_kg", 0.09));
        VirtualDroneState nimble = fleet.create(VehicleModel.DEFAULTS.with("thrust_to_weight", 3.5));

        assertEquals(0.09, heavy.vehicleModel().massKg(), 1.0e-9);
        assertEquals(0.03, nimble.vehicleModel().massKg(), 1.0e-9, "the defaults, untouched");
        assertEquals(3.5, nimble.vehicleModel().thrustToWeight(), 1.0e-9);
    }

    @Test
    void theFleetHasACeiling() {
        VirtualDroneFleet fleet = new VirtualDroneFleet();
        for (int i = 0; i < VirtualDroneFleet.MAX_DRONES; i++) {
            assertNotNull(fleet.create(VehicleModel.DEFAULTS), "drone " + (i + 1));
        }
        assertTrue(fleet.isFull());
        assertNull(fleet.create(VehicleModel.DEFAULTS), "no endless spawning");
        assertEquals(VirtualDroneFleet.MAX_DRONES, fleet.size());
    }

    @Test
    void anEmptyFleetHasNoPrimary() {
        VirtualDroneFleet fleet = new VirtualDroneFleet();
        assertTrue(fleet.isEmpty());
        assertNull(fleet.primary());
        assertEquals(List.of(), fleet.all());
        assertFalse(fleet.isFull());
    }
}
