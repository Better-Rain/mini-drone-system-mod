package com.vltbr.minidrone.world;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainingFieldDefaultsTest {
    private static final double EPSILON = 0.0001;

    @Test
    void readsAFieldFromCornerProperties() {
        Properties properties = new Properties();
        properties.setProperty(TrainingFieldDefaults.CORNERS_PROPERTY, "-10,5,10,25,64");

        TrainingFieldDefinition field = TrainingFieldDefaults.fromProperties(properties).orElseThrow();

        assertEquals(21, field.widthM());
        assertEquals(21, field.depthM());
        assertEquals(64, field.topY());
        assertEquals(TrainingFieldDefinition.Source.CORNERS, field.source());
        assertTrue(field.isCentred());
        assertEquals(0.5, field.originX(), EPSILON);
    }

    @Test
    void readsAFieldFromCentreProperties() {
        Properties properties = new Properties();
        properties.setProperty(TrainingFieldDefaults.CENTER_PROPERTY, "-1,17,13,9,-60");

        TrainingFieldDefinition field = TrainingFieldDefaults.fromProperties(properties).orElseThrow();

        assertEquals(13, field.widthM());
        assertEquals(9, field.depthM());
        assertEquals(-60, field.topY());
        assertEquals(TrainingFieldDefinition.Source.CENTRE_AND_SIZE, field.source());
    }

    /** Without a Y the field plane is wherever the ground is, resolved in world. */
    @Test
    void leavesTheSurfaceLayerToTheWorldWhenItIsNotGiven() {
        Properties properties = new Properties();
        properties.setProperty(TrainingFieldDefaults.CORNERS_PROPERTY, "0,0,10,10");

        TrainingFieldDefinition field = TrainingFieldDefaults.fromProperties(properties).orElseThrow();

        assertEquals(TrainingFieldDefaults.DERIVE_Y_FROM_GROUND, field.topY());
    }

    @Test
    void cornersWinOverCentreWhenBothAreConfigured() {
        Properties properties = new Properties();
        properties.setProperty(TrainingFieldDefaults.CORNERS_PROPERTY, "0,0,4,4,64");
        properties.setProperty(TrainingFieldDefaults.CENTER_PROPERTY, "0,0,40,40,64");

        TrainingFieldDefinition field = TrainingFieldDefaults.fromProperties(properties).orElseThrow();

        assertEquals(5, field.widthM());
    }

    /**
     * A typo in a launcher argument must not stop the world from loading, so a bad
     * value means "no default field" rather than an exception.
     */
    @Test
    void ignoresMalformedOrImpossibleValues() {
        assertTrue(TrainingFieldDefaults.fromProperties(new Properties()).isEmpty());

        Properties tooFew = new Properties();
        tooFew.setProperty(TrainingFieldDefaults.CORNERS_PROPERTY, "1,2,3");
        assertTrue(TrainingFieldDefaults.fromProperties(tooFew).isEmpty());

        Properties notNumbers = new Properties();
        notNumbers.setProperty(TrainingFieldDefaults.CENTER_PROPERTY, "a,b,c,d");
        assertTrue(TrainingFieldDefaults.fromProperties(notNumbers).isEmpty());

        Properties inverted = new Properties();
        inverted.setProperty(TrainingFieldDefaults.CORNERS_PROPERTY, "0,0,300,4,64");
        assertTrue(TrainingFieldDefaults.fromProperties(inverted).isEmpty());

        assertFalse(TrainingFieldDefaults.describeProperties().isEmpty());
    }
}
