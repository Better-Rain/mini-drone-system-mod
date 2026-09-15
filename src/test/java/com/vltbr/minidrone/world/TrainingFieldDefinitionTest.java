package com.vltbr.minidrone.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainingFieldDefinitionTest {
    private static final double EPSILON = 0.0001;

    @Test
    void describesAFieldFromTwoOppositeCorners() {
        TrainingFieldDefinition field = TrainingFieldDefinition.fromCorners(
            0, 0, 20, 8, 64, TrainingFieldDefinition.Source.CORNERS);

        assertEquals(21, field.widthM());
        assertEquals(9, field.depthM());
        assertEquals(0, field.minX());
        assertEquals(20, field.maxX());
        assertEquals(0, field.minZ());
        assertEquals(8, field.maxZ());
        assertTrue(field.isCentred());
        assertEquals(10.5, field.centreX(), EPSILON);
        assertEquals(4.5, field.centreZ(), EPSILON);
        // A centred field needs no offset: the main project draws around the origin.
        assertEquals(0.0, field.centerOffsetM()[0], EPSILON);
        assertEquals(0.0, field.centerOffsetM()[1], EPSILON);
        assertTrue(field.containsBlock(0, 0));
        assertTrue(field.containsBlock(20, 8));
        assertFalse(field.containsBlock(21, 8));
        assertFalse(field.containsBlock(10, 9));
    }

    @Test
    void acceptsCornersInAnyOrder() {
        TrainingFieldDefinition field = TrainingFieldDefinition.fromCorners(
            20, 8, 0, 0, 64, TrainingFieldDefinition.Source.SELECTOR);

        assertEquals(0, field.minX());
        assertEquals(20, field.maxX());
        assertEquals(0, field.minZ());
        assertEquals(8, field.maxZ());
    }

    @Test
    void describesAFieldFromACentreAndASize() {
        TrainingFieldDefinition field = TrainingFieldDefinition.fromCentreAndSize(
            -1, 17, 13, 13, -60, TrainingFieldDefinition.Source.CENTRE_AND_SIZE);

        assertEquals(-7, field.minX());
        assertEquals(5, field.maxX());
        assertEquals(11, field.minZ());
        assertEquals(23, field.maxZ());
        // The origin is the centre block, and the field is centred on it.
        assertEquals(-0.5, field.originX(), EPSILON);
        assertEquals(17.5, field.originZ(), EPSILON);
        assertEquals(0.0, field.centerOffsetM()[0], EPSILON);
        assertEquals(0.0, field.centerOffsetM()[1], EPSILON);
    }

    /**
     * An even-sized field has no centre block, so its centre lies between two
     * blocks: the rectangle cannot be symmetric about a block centre, and the
     * resulting half-metre offset has to be reported rather than hidden.
     */
    @Test
    void keepsAnEvenSizedFieldCentreBetweenBlocks() {
        TrainingFieldDefinition field = TrainingFieldDefinition.fromCentreAndSize(
            0, 0, 10, 4, 64, TrainingFieldDefinition.Source.CENTRE_AND_SIZE);

        assertEquals(-5, field.minX());
        assertEquals(4, field.maxX());
        assertEquals(10, field.widthM());
        // The geometric centre is between blocks, half a metre from the origin
        // block the operator named.
        assertEquals(0.0, field.centreX(), EPSILON);
        assertEquals(0.0, field.centreZ(), EPSILON);
        assertEquals(0.5, field.originX(), EPSILON);
        assertEquals(0.5, field.originZ(), EPSILON);
        assertEquals(-0.5, field.centerOffsetM()[0], EPSILON);
        assertEquals(-0.5, field.centerOffsetM()[1], EPSILON);
    }

    /**
     * A corner origin is the real-room convention, and then the monitoring side has
     * to be told where the field lies: without the offset it would draw the ground
     * around the origin and the vehicle's position relative to it would be wrong.
     */
    @Test
    void reportsTheFieldCentreOffsetWhenTheOriginIsNotTheCentre() {
        TrainingFieldDefinition field = TrainingFieldDefinition.fromCorners(
            0, 0, 20, 8, 64, TrainingFieldDefinition.Source.MARKERS).withOriginAtCorner();

        assertFalse(field.isCentred());
        assertEquals(TrainingFieldDefinition.OriginMode.CORNER, field.originMode());
        assertEquals(0.5, field.originX(), EPSILON);
        assertEquals(0.5, field.originZ(), EPSILON);
        // 21 x 9 m field, origin on its low corner: the centre is 10 x 4 m away.
        assertEquals(10.0, field.centerOffsetM()[0], EPSILON);
        assertEquals(4.0, field.centerOffsetM()[1], EPSILON);
        // Moving the origin does not move the field.
        assertEquals(21, field.widthM());
        assertEquals(9, field.depthM());
    }

    /** The origin sits one block above the surface layer, same as the arena rule. */
    @Test
    void putsTheOriginOneBlockAboveTheSurfaceLayer() {
        TrainingFieldDefinition field = TrainingFieldDefinition.fromCorners(
            -1, 17, 5, 23, -60, TrainingFieldDefinition.Source.CORNERS);

        NedWorldTransform origin = field.originTransform();
        assertEquals(2.5, origin.originX(), EPSILON);
        assertEquals(-59.0, origin.originY(), EPSILON);
        assertEquals(20.5, origin.originZ(), EPSILON);
    }

    @Test
    void rejectsFieldsThatCannotBeAdvertised() {
        assertThrows(IllegalArgumentException.class, () -> new TrainingFieldDefinition(
            5, 0, 0, 0, 64, 0.5, 0.5,
            TrainingFieldDefinition.OriginMode.CENTRE, TrainingFieldDefinition.Source.CORNERS));
        assertThrows(IllegalArgumentException.class, () -> TrainingFieldDefinition.fromCentreAndSize(
            0, 0, 0, 4, 64, TrainingFieldDefinition.Source.CENTRE_AND_SIZE));
        assertThrows(IllegalArgumentException.class, () -> TrainingFieldDefinition.fromCentreAndSize(
            0, 0, 4, 400, 64, TrainingFieldDefinition.Source.CENTRE_AND_SIZE));
        assertThrows(IllegalArgumentException.class, () -> TrainingFieldDefinition.fromCorners(
            0, 0, 4, 4, 64, TrainingFieldDefinition.Source.CORNERS)
            .withOrigin(Double.NaN, 0.0, TrainingFieldDefinition.OriginMode.EXPLICIT));
    }

    /** A rectangular field keeps its two axes independent, like the arena layout. */
    @Test
    void keepsWidthAndDepthIndependent() {
        TrainingFieldDefinition field = TrainingFieldDefinition.fromCorners(
            -3, -2, 7, 6, 70, TrainingFieldDefinition.Source.CORNERS);

        assertEquals(11, field.widthM());
        assertEquals(9, field.depthM());
        assertEquals(-3, field.minX());
        assertEquals(7, field.maxX());
        assertEquals(-2, field.minZ());
        assertEquals(6, field.maxZ());
    }
}
