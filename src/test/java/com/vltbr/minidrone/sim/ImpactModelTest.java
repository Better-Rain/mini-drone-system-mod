package com.vltbr.minidrone.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a collision does. The two thresholds are pinned against the speeds this plant
 * actually flies at, because getting them wrong turns either every landing or every
 * wall tap into a crash.
 */
class ImpactModelTest {
    @Test
    void aControlledTouchdownIsNotACrash() {
        assertEquals(
            ImpactModel.Outcome.CONTACT,
            ImpactModel.assess(0.0, ImpactModel.CONTROLLED_DESCENT_MPS)
        );
    }

    @Test
    void aFullSpeedRunIntoAWallIsACrash() {
        // The horizontal limit of this plant is 1.4 m/s; anything close to it that
        // stops abruptly is not a flight that continues.
        assertEquals(ImpactModel.Outcome.CRASH, ImpactModel.assess(1.4, 0.0));
        assertEquals(ImpactModel.Outcome.CRASH, ImpactModel.assess(-1.4, 0.0));
        assertEquals(
            ImpactModel.Outcome.CRASH,
            ImpactModel.assess(ImpactModel.CRASH_HORIZONTAL_MPS, 0.0)
        );
    }

    @Test
    void aGentleContactIsNotACrash() {
        assertEquals(ImpactModel.Outcome.CONTACT, ImpactModel.assess(0.2, 0.0));
        assertEquals(ImpactModel.Outcome.CONTACT, ImpactModel.assess(0.0, 0.3));
    }

    /** A free fall ends in a crash, which is what a lost vehicle looks like. */
    @Test
    void aFreeFallEndsInACrash() {
        assertEquals(ImpactModel.Outcome.CRASH, ImpactModel.assess(0.0, 4.0));
        assertEquals(
            ImpactModel.Outcome.CRASH,
            ImpactModel.assess(0.0, ImpactModel.CRASH_VERTICAL_MPS)
        );
    }

    /** Climbing into a ceiling is not an impact: nothing was descending. */
    @Test
    void climbingIsNotAnImpact() {
        assertEquals(ImpactModel.Outcome.NONE, ImpactModel.assess(0.0, -1.0));
        assertEquals(ImpactModel.Outcome.NONE, ImpactModel.assess(0.0, 0.0));
    }

    @Test
    void describesWhatItDecided() {
        String text = ImpactModel.describe(ImpactModel.Outcome.CRASH, 1.4, 0.0);
        assertEquals(true, text.contains("CRASH"));
        assertEquals(true, text.contains("1.40"));
    }
}
