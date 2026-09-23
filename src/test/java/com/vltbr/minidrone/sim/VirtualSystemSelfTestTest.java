package com.vltbr.minidrone.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualSystemSelfTestTest {
    @Test
    void deterministicClosedLoopPassesAllChecks() {
        VirtualSystemSelfTest.Report report = VirtualSystemSelfTest.run();

        assertTrue(report.successful(), report::summary);
        assertEquals(11, report.total());
        assertEquals(11, report.passed());
        assertTrue(report.failures().isEmpty());
    }
}
