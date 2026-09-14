package com.vltbr.minidrone.mavlink;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavlinkLinkStatusTest {
    @Test
    void classifiesSocketAndFreshBackendStates() {
        MavlinkLinkStatus waiting = new MavlinkLinkStatus(
            true, true, 40123, "127.0.0.1", 14561,
            0, 0, 20, 0, 0, 1_000, -1, "-", false, 18152, false);
        assertEquals(MavlinkLinkStatus.LinkState.WAITING_FOR_BACKEND, waiting.state());
        assertFalse(waiting.backendFresh(1_001));

        MavlinkLinkStatus connected = new MavlinkLinkStatus(
            true, true, 40123, "127.0.0.1", 14561,
            4, 4, 20, 0, 10_000, 11_000, 0, "127.0.0.1:14561", false, 18152, false);
        assertTrue(connected.backendSeen());
        assertTrue(connected.backendFresh(12_000));
        assertEquals(MavlinkLinkStatus.LinkState.CONNECTED, connected.state(12_000));
        assertFalse(connected.backendFresh(13_001));

        MavlinkLinkStatus down = new MavlinkLinkStatus(
            false, false, 0, "127.0.0.1", 14561,
            4, 4, 20, 0, 10_000, 11_000, 0, "127.0.0.1:14561", false, 18152, false);
        assertEquals(MavlinkLinkStatus.LinkState.DOWN, down.state());
    }
}
