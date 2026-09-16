package com.vltbr.minidrone.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * What a big arena reports to the monitoring side once the world scale is applied.
 *
 * <p>This is the case the operator asked for: build the arena in blocks to suit the room
 * they want to imagine, then say how far a block is. 49 blocks at 0.25 m each is the
 * 12.25 m room, and the vehicle that fits inside one of those blocks is the 0.22 m nano
 * quadcopter - without touching the vehicle's SI limits at all.
 *
 * <p>The scale is read from the JVM properties, so the test sets and restores it; leaving
 * it behind would silently change every other test that measures a field.
 */
class FieldSizeScaleTest {
    private static final double EPSILON = 1.0e-9;
    private final String previous = System.getProperty(WorldScale.PROPERTY);

    @AfterEach
    void restoreScale() {
        if (previous == null) {
            System.clearProperty(WorldScale.PROPERTY);
        } else {
            System.setProperty(WorldScale.PROPERTY, previous);
        }
    }

    private static void setScale(String value) {
        System.setProperty(WorldScale.PROPERTY, value);
    }

    /** A 49-block square is the 12.25 m room at 0.25 m per block. */
    @Test
    void aFortyNineBlockArenaIsTheRoomItStandsFor() {
        setScale("0.25");
        TrainingFieldDefinition field = TrainingFieldDefinition.fromCentreAndSize(
            0, 0, 49, 49, 64, TrainingFieldDefinition.Source.ARENA);

        double[] size = field.sizeM();
        assertEquals(49, field.widthM(), "the blocks are unchanged");
        assertEquals(12.25, size[0], EPSILON, "advertised width in metres");
        assertEquals(12.25, size[1], EPSILON, "advertised depth in metres");
    }

    /** At the default the advertised size is exactly the block count, as it always was. */
    @Test
    void theDefaultScaleLeavesTheSizeAlone() {
        setScale("1.0");
        TrainingFieldDefinition field = TrainingFieldDefinition.fromCentreAndSize(
            0, 0, 49, 49, 64, TrainingFieldDefinition.Source.ARENA);
        assertEquals(49.0, field.sizeM()[0], EPSILON);
        assertEquals(1.0, field.originTransform().metresPerBlock(), EPSILON);
    }

    /** The origin transform carries the scale, so NED really is metres from here on. */
    @Test
    void theOriginTransformCarriesTheScale() {
        setScale("0.25");
        TrainingFieldDefinition field = TrainingFieldDefinition.fromCentreAndSize(
            0, 0, 49, 49, 64, TrainingFieldDefinition.Source.ARENA);
        NedWorldTransform origin = field.originTransform();

        assertEquals(0.25, origin.metresPerBlock(), EPSILON);
        // One metre north of the origin is four blocks north of it, in world terms.
        WorldPose world = origin.toWorldPose(1.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        assertEquals(origin.originZ() - 4.0, world.z(), EPSILON);
        assertNotEquals(origin.originZ() - 1.0, world.z(), "not the unscaled answer");
    }

    /** A field that is not square scales both axes, and the centre offset with them. */
    @Test
    void aRectangularFieldScalesBothAxes() {
        setScale("0.5");
        TrainingFieldDefinition field = TrainingFieldDefinition.fromCentreAndSize(
            0, 0, 21, 9, 64, TrainingFieldDefinition.Source.CORNERS);
        assertEquals(10.5, field.sizeM()[0], EPSILON);
        assertEquals(4.5, field.sizeM()[1], EPSILON);
    }
}
