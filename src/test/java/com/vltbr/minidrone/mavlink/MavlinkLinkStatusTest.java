package com.vltbr.minidrone.mavlink;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavlinkLinkStatusTest {
    private static final String ADVERTISED_ID = "minecraft_drone_01";

    @Test
    void classifiesSocketAndFreshBackendStates() {
        MavlinkLinkStatus waiting = new MavlinkLinkStatus(
            true, true, 40123, "127.0.0.1", 14561,
            0, 0, 20, 0, 0, 1_000, -1, "-", false, ADVERTISED_ID, 18152, false);
        assertEquals(MavlinkLinkStatus.LinkState.WAITING_FOR_BACKEND, waiting.state());
        assertFalse(waiting.backendFresh(1_001));

        MavlinkLinkStatus connected = new MavlinkLinkStatus(
            true, true, 40123, "127.0.0.1", 14561,
            4, 4, 20, 0, 10_000, 11_000, 0, "127.0.0.1:14561", false, ADVERTISED_ID, 18152, false);
        assertTrue(connected.backendSeen());
        assertTrue(connected.backendFresh(12_000));
        assertEquals(MavlinkLinkStatus.LinkState.CONNECTED, connected.state(12_000));
        assertFalse(connected.backendFresh(13_001));

        MavlinkLinkStatus down = new MavlinkLinkStatus(
            false, false, 0, "127.0.0.1", 14561,
            4, 4, 20, 0, 10_000, 11_000, 0, "127.0.0.1:14561", false, ADVERTISED_ID, 18152, false);
        assertEquals(MavlinkLinkStatus.LinkState.DOWN, down.state());
    }

    @Test
    void carriesTheAdvertisedMotionCaptureIdentity() {
        MavlinkLinkStatus status = new MavlinkLinkStatus(
            true, true, 40123, "127.0.0.1", 14561,
            0, 0, 20, 0, 0, 1_000, -1, "-", true, "54", 18152, true);
        assertEquals("54", status.mocapExpectedDroneId());
        assertTrue(status.mocapControlBound());
    }
}
