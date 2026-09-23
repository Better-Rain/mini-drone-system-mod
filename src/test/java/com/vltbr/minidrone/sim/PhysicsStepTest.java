package com.vltbr.minidrone.sim;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules that make the virtual vehicle behave like it is inside the world: it
 * stops at a wall, slides along it, rests on the floor and cannot pass a ceiling.
 *
 * <p>The world is a predicate here, which is the point: these are the rules that used
 * to be wrong, and they are checked without starting the game.
 */
class PhysicsStepTest {
    private static final double HALF_WIDTH = 0.45;
    private static final double HALF_HEIGHT = 0.175;
    private static final double EPSILON = 0.0001;

    /** Nothing is solid. */
    private static final Predicate<double[]> OPEN = box -> true;

    /** Everything above {@code floorY} is air; below it is solid ground. */
    private static Predicate<double[]> floorAt(double floorY) {
        return box -> box[1] >= floorY - EPSILON;
    }

    /**
     * A solid wall plane at {@code wallZ}: everything past it is solid, so a box is
     * free while its far edge is still short of the plane.
     */
    private static Predicate<double[]> wallAtZ(double wallZ) {
        return box -> box[5] <= wallZ + EPSILON;
    }

    @Test
    void movesFreelyWhenNothingIsInTheWay() {
        PhysicsStep.Result result = PhysicsStep.resolve(
            0.0, 10.0, 0.0, 0.07, -0.4, 0.05, HALF_WIDTH, HALF_HEIGHT, OPEN);

        assertEquals(0.07, result.x(), EPSILON);
        assertEquals(9.6, result.y(), EPSILON);
        assertEquals(0.05, result.z(), EPSILON);
        assertFalse(result.blockedHorizontally());
        assertFalse(result.blockedVertically());
    }

    /** Falling onto the ground stops on it instead of sinking into it. */
    @Test
    void restsOnTheFloorInsteadOfFallingThroughIt() {
        Predicate<double[]> world = floorAt(0.0);
        // Start one foot radius above the floor and fall far enough to pass it.
        double startY = HALF_HEIGHT;
        PhysicsStep.Result result = PhysicsStep.resolve(
            0.0, startY, 0.0, 0.0, -0.6, 0.0, HALF_WIDTH, HALF_HEIGHT, world);

        assertTrue(result.blockedVertically());
        assertEquals(startY, result.y(), EPSILON);
        assertTrue(result.y() - HALF_HEIGHT >= -EPSILON, "the box went below the floor");
    }

    @Test
    void descendsTheLastPartOfTheWayToTheFloor() {
        Predicate<double[]> world = floorAt(0.0);
        // One 6 cm step above the floor: the whole step still fits.
        double startY = HALF_HEIGHT + 0.06;
        PhysicsStep.Result result = PhysicsStep.resolve(
            0.0, startY, 0.0, 0.0, -0.06, 0.0, HALF_WIDTH, HALF_HEIGHT, world);

        assertFalse(result.blockedVertically());
        assertEquals(HALF_HEIGHT, result.y(), EPSILON);
    }

    /** A wall stops the horizontal component and leaves the rest of the step alone. */
    @Test
    void slidesAlongAWallAndKeepsTheFreeAxis() {
        Predicate<double[]> world = wallAtZ(1.0);
        PhysicsStep.Result result = PhysicsStep.resolve(
            0.0, 10.0, 0.5, 0.5, 0.0, 0.4, HALF_WIDTH, HALF_HEIGHT, world);

        assertTrue(result.blockedZ());
        assertFalse(result.blockedX(), "the free axis must not be sacrificed to the blocked one");
        assertEquals(0.5, result.x(), EPSILON);
        assertTrue(result.z() <= 1.0 - HALF_WIDTH + EPSILON, "the box crossed the wall");
    }

    /** A ceiling stops a climb; the climb does not push the vehicle through it. */
    @Test
    void stopsAtACeiling() {
        Predicate<double[]> world = box -> box[4] <= 10.0;

        PhysicsStep.Result result = PhysicsStep.resolve(
            0.0, 9.0, 0.0, 0.0, 2.0, 0.0, HALF_WIDTH, HALF_HEIGHT, world);

        assertTrue(result.blockedVertically());
        assertTrue(result.y() + HALF_HEIGHT <= 10.0 + EPSILON, "the box crossed the ceiling");
    }

    /** Blocked in a corner, it stays put rather than being ejected somewhere. */
    @Test
    void keepsABoxThatStartsInsideABlockWhereItIs() {
        Predicate<double[]> world = box -> box[0] >= 0.0;

        PhysicsStep.Result result = PhysicsStep.resolve(
            -0.2, 10.0, 0.0, -0.5, 0.0, 0.0, HALF_WIDTH, HALF_HEIGHT, world);

        assertTrue(result.blockedX());
        assertEquals(-0.2, result.x(), EPSILON);
    }

    /** The step never overshoots: every accepted candidate is one the world allowed. */
    @Test
    void neverAcceptsAPositionTheWorldRejected() {
        List<double[]> asked = new ArrayList<>();
        Predicate<double[]> world = box -> {
            asked.add(box);
            return box[1] >= 4.0;
        };

        PhysicsStep.Result result = PhysicsStep.resolve(
            0.0, 8.0, 0.0, 0.3, -3.0, -0.2, HALF_WIDTH, HALF_HEIGHT, world);

        double feet = result.y() - HALF_HEIGHT;
        assertTrue(feet >= 4.0 - EPSILON, "the accepted position has solid ground under it");
        assertFalse(asked.isEmpty());
    }

    /**
     * A step too long to resolve in one go is swept, not skipped.
     *
     * <p>The caller's cap used to mean "apply it directly without asking the world", and a
     * terminal-velocity fall in a 0.25 m-per-block world is past that cap: the vehicle went
     * through the floor and kept falling.
     */
    @Test
    void sweepsALongFallInsteadOfPassingThroughTheFloor() {
        // 12 blocks in one tick: a fall the old code would have carried straight through.
        PhysicsStep.Result result = PhysicsStep.resolveSwept(
            0.0, 12.0, 0.0, 0.0, -12.0, 0.0, HALF_WIDTH, HALF_HEIGHT, floorAt(0.0), 1.5, 64);

        assertTrue(result.blockedVertically(), "the floor stopped it");
        assertTrue(result.y() - HALF_HEIGHT >= 0.0 - EPSILON,
            "and it is resting on the floor, not under it: y=" + result.y());
    }

    /** The same for a long horizontal step against a wall. */
    @Test
    void sweepsALongStepIntoAWall() {
        PhysicsStep.Result result = PhysicsStep.resolveSwept(
            0.0, 10.0, 0.0, 0.0, 0.0, 12.0, HALF_WIDTH, HALF_HEIGHT, wallAtZ(6.0), 1.5, 64);

        assertTrue(result.blockedZ(), "the wall stopped it");
        assertTrue(result.z() + HALF_WIDTH <= 6.0 + EPSILON,
            "and it did not end up past the wall: z=" + result.z());
    }

    /** Short steps still take the single-resolution path, unchanged. */
    @Test
    void sweepsShortStepsExactlyLikeResolve() {
        Predicate<double[]> world = floorAt(4.0);
        PhysicsStep.Result direct = PhysicsStep.resolve(
            0.1, 8.0, -0.2, 0.07, -0.4, 0.05, HALF_WIDTH, HALF_HEIGHT, world);
        PhysicsStep.Result swept = PhysicsStep.resolveSwept(
            0.1, 8.0, -0.2, 0.07, -0.4, 0.05, HALF_WIDTH, HALF_HEIGHT, world, 1.5, 64);

        assertEquals(direct.x(), swept.x(), EPSILON);
        assertEquals(direct.y(), swept.y(), EPSILON);
        assertEquals(direct.z(), swept.z(), EPSILON);
        assertEquals(direct.blockedY(), swept.blockedY());
    }

    /** A step past the sub-step budget is a carry again: spawning really is a teleport. */
    @Test
    void carriesAStepBeyondTheSubStepBudget() {
        PhysicsStep.Result result = PhysicsStep.resolveSwept(
            0.0, 64.0, 0.0, 0.0, -200.0, 0.0, HALF_WIDTH, HALF_HEIGHT, floorAt(0.0), 1.5, 64);

        assertEquals(-136.0, result.y(), EPSILON);
        assertFalse(result.blockedVertically());
    }
}
