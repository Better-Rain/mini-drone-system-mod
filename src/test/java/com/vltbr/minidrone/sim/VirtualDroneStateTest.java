package com.vltbr.minidrone.sim;

import com.vltbr.minidrone.mavlink.MavlinkProtocol;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualDroneStateTest {
    @Test
    void requiresGuidedModeBeforeArmingAndTakeoff() {
        VirtualDroneState drone = new VirtualDroneState(54, 1, "minecraft_drone_01");

        assertFalse(drone.setArmed(true));
        assertTrue(drone.setMode(MavlinkProtocol.ARDUCOPTER_MODE_GUIDED));
        assertTrue(drone.setArmed(true));
        assertTrue(drone.takeoff(1.0));

        for (int tick = 0; tick < 30; tick++) {
            drone.tick();
        }

        VirtualDroneSnapshot airborne = drone.snapshot();
        assertTrue(airborne.armed());
        assertTrue(airborne.airborne());
        assertFalse(airborne.takingOff());
        assertEquals(-1.0, airborne.downM(), 0.0001);
    }

    @Test
    void landingReachesGroundAndDisarms() {
        VirtualDroneState drone = new VirtualDroneState(54, 1, "minecraft_drone_01");
        drone.setMode(MavlinkProtocol.ARDUCOPTER_MODE_GUIDED);
        drone.setArmed(true);
        drone.takeoff(0.5);
        for (int tick = 0; tick < 20; tick++) {
            drone.tick();
        }

        assertTrue(drone.land());
        for (int tick = 0; tick < 30; tick++) {
            drone.tick();
        }

        VirtualDroneSnapshot landed = drone.snapshot();
        assertFalse(landed.armed());
        assertFalse(landed.airborne());
        assertEquals(0.0, landed.downM(), 0.0001);
        assertEquals(9, landed.customMode());
    }

    @Test
    void followsPositionTargetAtBoundedHorizontalSpeed() {
        VirtualDroneState drone = new VirtualDroneState(54, 1, "minecraft_drone_01");
        drone.setMode(MavlinkProtocol.ARDUCOPTER_MODE_GUIDED);
        drone.setArmed(true);
        drone.takeoff(1.0);
        for (int tick = 0; tick < 30; tick++) {
            drone.tick();
        }

        assertTrue(drone.setPositionTarget(2.0, 1.0, -1.0));
        drone.tick();
        VirtualDroneSnapshot moving = drone.snapshot();
        assertTrue(Math.hypot(moving.northM(), moving.eastM()) > 0.0);
        assertEquals(1.4, Math.hypot(moving.velocityNorthMps(), moving.velocityEastMps()), 0.0001);
        assertEquals(0.0, moving.velocityDownMps(), 0.0001);

        for (int tick = 0; tick < 40; tick++) {
            drone.tick();
        }
        VirtualDroneSnapshot reached = drone.snapshot();
        assertEquals(2.0, reached.northM(), 0.0001);
        assertEquals(1.0, reached.eastM(), 0.0001);
        assertEquals(-1.0, reached.downM(), 0.0001);
        assertEquals(0.0, reached.velocityNorthMps(), 0.0001);
        assertEquals(0.0, reached.velocityEastMps(), 0.0001);
    }

    @Test
    void resetsLocalPositionOnlyAfterLandingAndDisarm() {
        VirtualDroneState drone = new VirtualDroneState(54, 1, "minecraft_drone_01");
        drone.setMode(MavlinkProtocol.ARDUCOPTER_MODE_GUIDED);
        drone.setArmed(true);
        drone.takeoff(0.5);
        for (int tick = 0; tick < 20; tick++) {
            drone.tick();
        }

        assertFalse(drone.resetLocalPosition());
        drone.land();
        for (int tick = 0; tick < 30; tick++) {
            drone.tick();
        }
        assertTrue(drone.resetLocalPosition());
        assertEquals(0.0, drone.snapshot().northM(), 0.0001);
        assertEquals(0.0, drone.snapshot().eastM(), 0.0001);
        assertEquals(0.0, drone.snapshot().downM(), 0.0001);
    }
}
