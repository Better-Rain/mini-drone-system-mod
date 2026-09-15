package com.vltbr.minidrone.sim;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Touching something without being destroyed by it.
 *
 * <p>The rules decide whether the vehicle slides along a gate frame or sticks to it,
 * and whether a slightly fast touchdown settles or bounces off - so they are pinned by
 * arithmetic rather than by flying into things.
 */
class ContactResponseTest {
    private static final double EPSILON = 0.0001;
    private static final double TICK = 0.05;

    @Test
    void aTouchBouncesBackByTheRestitution() {
        // 1 m/s into a wall with restitution 0.25 comes back at 0.25 m/s.
        assertEquals(-0.25, ContactResponse.rebound(1.0, 0.25), EPSILON);
        // A dead airframe stops against it instead.
        assertEquals(0.0, ContactResponse.rebound(1.0, 0.0), EPSILON);
        // Whichever side the surface is on, the component that ran into it is reversed:
        // the caller only asks about an axis whose motion was blocked, so the sign here
        // is "the direction it was going", and the answer is "the other way".
        assertEquals(0.25, ContactResponse.rebound(-1.0, 0.25), EPSILON);
    }

    @Test
    void restitutionIsClampedToSomethingPhysical() {
        assertEquals(-1.0, ContactResponse.rebound(1.0, 3.0), EPSILON);
        assertEquals(0.0, ContactResponse.rebound(1.0, -1.0), EPSILON);
    }

    /** Sliding friction removes a fixed amount of speed per second, not a fraction. */
    @Test
    void frictionDeceleratesASlideAtMuTimesGravity() {
        // friction 0.6 => 5.886 m/s^2, so one 50 ms tick removes 0.2943 m/s.
        double expected = 1.0 - 0.6 * VehicleModel.GRAVITY_MPS2 * TICK;
        assertEquals(expected, ContactResponse.slide(1.0, 0.6, TICK), EPSILON);
        assertEquals(-expected, ContactResponse.slide(-1.0, 0.6, TICK), EPSILON);
    }

    @Test
    void slidingNeverReversesTheMotion() {
        assertEquals(0.0, ContactResponse.slide(0.1, 1.0, TICK), EPSILON);
        assertEquals(0.0, ContactResponse.slide(-0.01, 1.0, TICK), EPSILON);
        // Half a second of contact does not push the vehicle backwards.
        double speed = 2.0;
        for (int tick = 0; tick < 40; tick++) {
            speed = ContactResponse.slide(speed, 0.6, TICK);
            assertTrue(speed >= 0.0, "a slide must not turn into a push");
        }
        assertEquals(0.0, speed, EPSILON);
    }

    /** Slicker surfaces keep the vehicle moving: that is what makes a graze a graze. */
    @Test
    void aSlickSurfaceKeepsMoreSpeed() {
        double slick = ContactResponse.slide(1.0, 0.05, TICK);
        double grippy = ContactResponse.slide(1.0, 0.9, TICK);
        assertTrue(slick > grippy);
    }
}
