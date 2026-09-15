package com.vltbr.minidrone.sim;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The airframe's numbers, and the guarantees derived from them.
 *
 * <p>These are the values an operator tunes to change how the vehicle feels, so the
 * derivations matter as much as the defaults: hover throttle, the speed drag actually
 * allows, and the rule that a malformed launcher argument is ignored rather than fatal.
 */
class VehicleModelTest {
    private static final double EPSILON = 0.0001;

    @Test
    void derivesHoverThrottleFromTheThrustToWeightRatio() {
        assertEquals(0.5, new VehicleModel(
            0.03, 2.0, 0.08, 35, 3, 0.12, 0.06, 1.4, 0.8, 0.6, 0.45, 0.175, 0.25, 0.6, 0.8, 1.5
        ).hoverThrottle(), EPSILON);
        assertTrue(VehicleModel.DEFAULTS.hoverThrottle() < 0.5,
            "the default vehicle should have thrust to spare while hovering");
    }

    @Test
    void weightAndThrustFollowFromMassAndRatio() {
        assertEquals(0.03 * 9.81, VehicleModel.DEFAULTS.weightN(), EPSILON);
        assertEquals(2.2 * 0.03 * 9.81, VehicleModel.DEFAULTS.maxThrustN(), EPSILON);
    }

    /**
     * The speed the airframe can actually reach is a property of the airframe: drag
     * balances the thrust that can be pointed sideways. It has to sit above what the
     * controller may ask for, or the controller limit would be meaningless.
     */
    @Test
    void dragDecidesTheRealTopSpeed() {
        double top = VehicleModel.DEFAULTS.topSpeedMps();
        assertTrue(top > VehicleModel.DEFAULTS.maxHorizontalSpeedMps(),
            "top speed " + top + " should exceed the requested limit");
        assertEquals(
            Math.sqrt(VehicleModel.DEFAULTS.maxThrustN()
                * Math.sin(VehicleModel.DEFAULTS.maxTiltRad())
                / VehicleModel.DEFAULTS.dragCoefficient()),
            top,
            EPSILON
        );

        // More drag, lower top speed; no drag, no ceiling.
        VehicleModel draggy = VehicleModel.DEFAULTS.with("drag_coefficient", 0.24);
        assertTrue(draggy.topSpeedMps() < top);
        assertTrue(VehicleModel.DEFAULTS.with("drag_coefficient", 0.0).topSpeedMps()
            == Double.POSITIVE_INFINITY);
    }

    @Test
    void readsOverridesFromProperties() {
        Properties properties = new Properties();
        properties.setProperty("mini_drone.vehicle.thrust_to_weight", "1.6");
        properties.setProperty("mini_drone.vehicle.max_tilt_deg", "20");

        VehicleModel model = VehicleModel.fromProperties(properties);

        assertEquals(1.6, model.thrustToWeight(), EPSILON);
        assertEquals(20.0, model.maxTiltDeg(), EPSILON);
        assertEquals(VehicleModel.DEFAULTS.massKg(), model.massKg(), EPSILON);
    }

    @Test
    void ignoresMalformedOrOutOfRangeOverrides() {
        Properties notANumber = new Properties();
        notANumber.setProperty("mini_drone.vehicle.mass_kg", "heavy");
        assertEquals(VehicleModel.DEFAULTS.massKg(), VehicleModel.fromProperties(notANumber).massKg(), EPSILON);

        Properties outOfRange = new Properties();
        outOfRange.setProperty("mini_drone.vehicle.thrust_to_weight", "0.2");
        assertEquals(
            VehicleModel.DEFAULTS.thrustToWeight(),
            VehicleModel.fromProperties(outOfRange).thrustToWeight(),
            EPSILON);

        assertThrows(IllegalArgumentException.class,
            () -> VehicleModel.DEFAULTS.with("thrust_to_weight", 0.2));
        assertThrows(IllegalArgumentException.class,
            () -> VehicleModel.DEFAULTS.with("no_such_parameter", 1.0));
    }

    /** Every parameter round trips through its own name, which the command relies on. */
    @Test
    void everyParameterCanBeSetThroughItsName() {
        for (String name : VehicleModel.parameterNames()) {
            VehicleModel touched = VehicleModel.DEFAULTS.with(name, VehicleModel.DEFAULTS.valueOf(name));
            assertEquals(VehicleModel.DEFAULTS.valueOf(name), touched.valueOf(name), EPSILON,
                "parameter " + name + " did not round trip");
        }
        assertTrue(VehicleModel.DEFAULTS.describe().contains("hover throttle"));
        assertTrue(VehicleModel.DEFAULTS.describe().contains("mass_kg"));
    }
}