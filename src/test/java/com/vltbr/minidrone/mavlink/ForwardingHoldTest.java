package com.vltbr.minidrone.mavlink;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForwardingHoldTest {
    @Test
    void startsRunningAndAcceptsSetpoints() {
        ForwardingHold hold = new ForwardingHold();

        assertFalse(hold.held());
        assertTrue(hold.acceptsSetpoints());
        assertEquals("", hold.reason());
    }

    @Test
    void appliesTheBackendHoldAndResumeCommands() {
        ForwardingHold hold = new ForwardingHold();

        assertTrue(hold.apply(ForwardingHold.Change.HOLD));
        assertTrue(hold.held());
        assertFalse(hold.acceptsSetpoints());
        assertEquals("backend_request", hold.reason());

        assertFalse(hold.apply(ForwardingHold.Change.RESUME));
        assertFalse(hold.held());
        assertTrue(hold.acceptsSetpoints());
        assertEquals("", hold.reason());
    }

    @Test
    void aStatusProbeNeverChangesTheState() {
        ForwardingHold running = new ForwardingHold();
        ForwardingHold held = new ForwardingHold();
        held.apply(ForwardingHold.Change.HOLD);

        assertFalse(running.apply(ForwardingHold.Change.UNCHANGED));
        assertTrue(held.apply(ForwardingHold.Change.UNCHANGED));
    }
}
