package com.vltbr.minidrone.sim;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * The vehicle the simulation flies.
 *
 * <p>Everything the plant does used to be a constant in the code: how fast it turns,
 * how far it leans, how hard a bump has to be to destroy it. That works until an
 * operator wants a heavier quad, a tighter room or a softer obstacle course, and then
 * the answer is "edit Java and rebuild". This record is that answer instead: every
 * number the motion depends on is a named, documented, overridable parameter.
 *
 * <p>The defaults describe a small indoor quadcopter - around 30 g, thrust about twice
 * its weight, motors that take about 80 ms to change thrust, and a 35 degree lean limit
 * - which is the class of vehicle this Minecraft field is a stand-in for.
 *
 * <p>Overrides come from JVM properties ({@code -Dmini_drone.vehicle.<name>=<value>}),
 * and the same values can be changed while the game runs with
 * {@code /minidrone physics set <name> <value>}. A malformed value is ignored rather
 * than fatal: a typo in a launcher argument must not stop a world from loading.
 */
public record VehicleModel(
    double massKg,
    double thrustToWeight,
    double motorTimeConstantS,
    double maxTiltDeg,
    double maxYawRateRadS,
    double attitudeTimeConstantS,
    double dragCoefficient,
    double maxHorizontalSpeedMps,
    double climbRateMps,
    double descentRateMps,
    double collisionHalfWidthM,
    double collisionHalfHeightM,
    double restitution,
    double friction,
    double crashHorizontalMps,
    double crashVerticalMps
) {
    public static final double GRAVITY_MPS2 = 9.81;
    public static final String PROPERTY_PREFIX = "mini_drone.vehicle.";

    /** Small indoor quadcopter, the class this field stands in for. */
    public static final VehicleModel DEFAULTS = new VehicleModel(
        0.03,    // massKg: about 30 g
        2.2,     // thrustToWeight: thrust is 2.2x weight, so it hovers near 45%
        0.08,    // motorTimeConstantS: first-order thrust lag
        35.0,    // maxTiltDeg: how far it can lean before it cannot hold altitude
        3.0,     // maxYawRateRadS
        0.12,    // attitudeTimeConstantS: how fast the rate loop reaches a commanded rate
        0.06,    // dragCoefficient: k in F = -k*v*|v|, per axis
        1.4,     // maxHorizontalSpeedMps: what the flight controller may ask for
        0.8,     // climbRateMps
        0.6,     // descentRateMps: the controlled descent, and the "not a crash" bound
        0.45,    // collisionHalfWidthM: half the collision box in x and z
        0.175,   // collisionHalfHeightM: half the collision box in y
        0.25,    // restitution: how much a light touch bounces off
        0.6,     // friction: how much of the tangential motion survives a touch
        0.8,     // crashHorizontalMps: a run into a wall at this speed ends the flight
        1.5      // crashVerticalMps: and so does an arrival this fast
    );

    public VehicleModel {
        requireRange("massKg", massKg, 0.001, 50.0);
        requireRange("thrustToWeight", thrustToWeight, 1.0, 10.0);
        requireRange("motorTimeConstantS", motorTimeConstantS, 0.0, 2.0);
        requireRange("maxTiltDeg", maxTiltDeg, 1.0, 85.0);
        requireRange("maxYawRateRadS", maxYawRateRadS, 0.1, 20.0);
        requireRange("attitudeTimeConstantS", attitudeTimeConstantS, 0.01, 2.0);
        requireRange("dragCoefficient", dragCoefficient, 0.0, 5.0);
        requireRange("maxHorizontalSpeedMps", maxHorizontalSpeedMps, 0.05, 30.0);
        requireRange("climbRateMps", climbRateMps, 0.05, 20.0);
        requireRange("descentRateMps", descentRateMps, 0.05, 20.0);
        requireRange("collisionHalfWidthM", collisionHalfWidthM, 0.02, 2.0);
        requireRange("collisionHalfHeightM", collisionHalfHeightM, 0.02, 2.0);
        requireRange("restitution", restitution, 0.0, 1.0);
        requireRange("friction", friction, 0.0, 1.0);
        requireRange("crashHorizontalMps", crashHorizontalMps, 0.05, 30.0);
        requireRange("crashVerticalMps", crashVerticalMps, 0.05, 30.0);
    }

    private static void requireRange(String name, double value, double min, double max) {
        if (!Double.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(
                name + " must be within " + min + " and " + max + ", got " + value);
        }
    }

    /** Weight in newtons. */
    public double weightN() {
        return massKg * GRAVITY_MPS2;
    }

    /** Thrust available at full throttle, newtons. */
    public double maxThrustN() {
        return thrustToWeight * weightN();
    }

    /** Throttle that holds altitude, 0..1. */
    public double hoverThrottle() {
        return 1.0 / thrustToWeight;
    }

    /** Maximum lean in radians. */
    public double maxTiltRad() {
        return Math.toRadians(maxTiltDeg);
    }

    /**
     * The speed at which drag balances the thrust a full lean can point sideways.
     *
     * <p>The vehicle moves sideways with the horizontal part of its thrust vector, and
     * the lean limit caps what that can produce at {@code g * tan(maxTiltRad())} - so the
     * speed where quadratic drag balances it is
     * {@code sqrt(massKg * GRAVITY_MPS2 * tan(maxTiltRad()) / dragCoefficient)}, which is
     * 1.86 m/s for the defaults. If the flight controller is allowed to ask for more than
     * this, the vehicle simply cannot reach it - which is the point of having drag at
     * all: the top speed is a property of the airframe rather than a number somebody
     * typed in.
     */
    public double topSpeedMps() {
        if (dragCoefficient <= 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        double horizontalAcceleration = GRAVITY_MPS2 * Math.tan(maxTiltRad());
        return Math.sqrt(massKg * horizontalAcceleration / dragCoefficient);
    }

    /** Seconds to accelerate from rest to 63% of a speed the drag allows. */
    public double speedResponseS(double targetSpeedMps) {
        if (dragCoefficient <= 0.0 || targetSpeedMps <= 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        double horizontalForce = maxThrustN() * Math.sin(maxTiltRad());
        // Linearised about rest: dv/dt = (F_horizontal - k v^2) / m.
        return massKg * targetSpeedMps / Math.max(1.0e-6, horizontalForce);
    }

    /** The same vehicle with one parameter replaced, by its property name. */
    public VehicleModel with(String name, double value) {
        return switch (name) {
            case "mass_kg" -> new VehicleModel(
                value, thrustToWeight, motorTimeConstantS, maxTiltDeg, maxYawRateRadS,
                attitudeTimeConstantS, dragCoefficient, maxHorizontalSpeedMps, climbRateMps,
                descentRateMps, collisionHalfWidthM, collisionHalfHeightM, restitution,
                friction, crashHorizontalMps, crashVerticalMps);
            case "thrust_to_weight" -> new VehicleModel(
                massKg, value, motorTimeConstantS, maxTiltDeg, maxYawRateRadS,
                attitudeTimeConstantS, dragCoefficient, maxHorizontalSpeedMps, climbRateMps,
                descentRateMps, collisionHalfWidthM, collisionHalfHeightM, restitution,
                friction, crashHorizontalMps, crashVerticalMps);
            case "motor_time_constant_s" -> new VehicleModel(
                massKg, thrustToWeight, value, maxTiltDeg, maxYawRateRadS,
                attitudeTimeConstantS, dragCoefficient, maxHorizontalSpeedMps, climbRateMps,
                descentRateMps, collisionHalfWidthM, collisionHalfHeightM, restitution,
                friction, crashHorizontalMps, crashVerticalMps);
            case "max_tilt_deg" -> new VehicleModel(
                massKg, thrustToWeight, motorTimeConstantS, value, maxYawRateRadS,
                attitudeTimeConstantS, dragCoefficient, maxHorizontalSpeedMps, climbRateMps,
                descentRateMps, collisionHalfWidthM, collisionHalfHeightM, restitution,
                friction, crashHorizontalMps, crashVerticalMps);
            case "max_yaw_rate_rad_s" -> new VehicleModel(
                massKg, thrustToWeight, motorTimeConstantS, maxTiltDeg, value,
                attitudeTimeConstantS, dragCoefficient, maxHorizontalSpeedMps, climbRateMps,
                descentRateMps, collisionHalfWidthM, collisionHalfHeightM, restitution,
                friction, crashHorizontalMps, crashVerticalMps);
            case "attitude_time_constant_s" -> new VehicleModel(
                massKg, thrustToWeight, motorTimeConstantS, maxTiltDeg, maxYawRateRadS,
                value, dragCoefficient, maxHorizontalSpeedMps, climbRateMps,
                descentRateMps, collisionHalfWidthM, collisionHalfHeightM, restitution,
                friction, crashHorizontalMps, crashVerticalMps);
            case "drag_coefficient" -> new VehicleModel(
                massKg, thrustToWeight, motorTimeConstantS, maxTiltDeg, maxYawRateRadS,
                attitudeTimeConstantS, value, maxHorizontalSpeedMps, climbRateMps,
                descentRateMps, collisionHalfWidthM, collisionHalfHeightM, restitution,
                friction, crashHorizontalMps, crashVerticalMps);
            case "max_horizontal_speed_mps" -> new VehicleModel(
                massKg, thrustToWeight, motorTimeConstantS, maxTiltDeg, maxYawRateRadS,
                attitudeTimeConstantS, dragCoefficient, value, climbRateMps,
                descentRateMps, collisionHalfWidthM, collisionHalfHeightM, restitution,
                friction, crashHorizontalMps, crashVerticalMps);
            case "climb_rate_mps" -> new VehicleModel(
                massKg, thrustToWeight, motorTimeConstantS, maxTiltDeg, maxYawRateRadS,
                attitudeTimeConstantS, dragCoefficient, maxHorizontalSpeedMps, value,
                descentRateMps, collisionHalfWidthM, collisionHalfHeightM, restitution,
                friction, crashHorizontalMps, crashVerticalMps);
            case "descent_rate_mps" -> new VehicleModel(
                massKg, thrustToWeight, motorTimeConstantS, maxTiltDeg, maxYawRateRadS,
                attitudeTimeConstantS, dragCoefficient, maxHorizontalSpeedMps, climbRateMps,
                value, collisionHalfWidthM, collisionHalfHeightM, restitution,
                friction, crashHorizontalMps, crashVerticalMps);
            case "collision_half_width_m" -> new VehicleModel(
                massKg, thrustToWeight, motorTimeConstantS, maxTiltDeg, maxYawRateRadS,
                attitudeTimeConstantS, dragCoefficient, maxHorizontalSpeedMps, climbRateMps,
                descentRateMps, value, collisionHalfHeightM, restitution,
                friction, crashHorizontalMps, crashVerticalMps);
            case "collision_half_height_m" -> new VehicleModel(
                massKg, thrustToWeight, motorTimeConstantS, maxTiltDeg, maxYawRateRadS,
                attitudeTimeConstantS, dragCoefficient, maxHorizontalSpeedMps, climbRateMps,
                descentRateMps, collisionHalfWidthM, value, restitution,
                friction, crashHorizontalMps, crashVerticalMps);
            case "restitution" -> new VehicleModel(
                massKg, thrustToWeight, motorTimeConstantS, maxTiltDeg, maxYawRateRadS,
                attitudeTimeConstantS, dragCoefficient, maxHorizontalSpeedMps, climbRateMps,
                descentRateMps, collisionHalfWidthM, collisionHalfHeightM, value,
                friction, crashHorizontalMps, crashVerticalMps);
            case "friction" -> new VehicleModel(
                massKg, thrustToWeight, motorTimeConstantS, maxTiltDeg, maxYawRateRadS,
                attitudeTimeConstantS, dragCoefficient, maxHorizontalSpeedMps, climbRateMps,
                descentRateMps, collisionHalfWidthM, collisionHalfHeightM, restitution,
                value, crashHorizontalMps, crashVerticalMps);
            case "crash_horizontal_mps" -> new VehicleModel(
                massKg, thrustToWeight, motorTimeConstantS, maxTiltDeg, maxYawRateRadS,
                attitudeTimeConstantS, dragCoefficient, maxHorizontalSpeedMps, climbRateMps,
                descentRateMps, collisionHalfWidthM, collisionHalfHeightM, restitution,
                friction, value, crashVerticalMps);
            case "crash_vertical_mps" -> new VehicleModel(
                massKg, thrustToWeight, motorTimeConstantS, maxTiltDeg, maxYawRateRadS,
                attitudeTimeConstantS, dragCoefficient, maxHorizontalSpeedMps, climbRateMps,
                descentRateMps, collisionHalfWidthM, collisionHalfHeightM, restitution,
                friction, crashHorizontalMps, value);
            default -> throw new IllegalArgumentException("unknown vehicle parameter: " + name);
        };
    }

    /** The parameter names, in the order they are documented and displayed. */
    public static java.util.List<String> parameterNames() {
        return java.util.List.of(
            "mass_kg",
            "thrust_to_weight",
            "motor_time_constant_s",
            "max_tilt_deg",
            "max_yaw_rate_rad_s",
            "attitude_time_constant_s",
            "drag_coefficient",
            "max_horizontal_speed_mps",
            "climb_rate_mps",
            "descent_rate_mps",
            "collision_half_width_m",
            "collision_half_height_m",
            "restitution",
            "friction",
            "crash_horizontal_mps",
            "crash_vertical_mps"
        );
    }

    /** This model's values, by parameter name, in the documented order. */
    public Map<String, Double> byName() {
        Map<String, Double> values = new LinkedHashMap<>();
        for (String name : parameterNames()) {
            values.put(name, valueOf(name));
        }
        return values;
    }

    public double valueOf(String name) {
        return switch (name) {
            case "mass_kg" -> massKg;
            case "thrust_to_weight" -> thrustToWeight;
            case "motor_time_constant_s" -> motorTimeConstantS;
            case "max_tilt_deg" -> maxTiltDeg;
            case "max_yaw_rate_rad_s" -> maxYawRateRadS;
            case "attitude_time_constant_s" -> attitudeTimeConstantS;
            case "drag_coefficient" -> dragCoefficient;
            case "max_horizontal_speed_mps" -> maxHorizontalSpeedMps;
            case "climb_rate_mps" -> climbRateMps;
            case "descent_rate_mps" -> descentRateMps;
            case "collision_half_width_m" -> collisionHalfWidthM;
            case "collision_half_height_m" -> collisionHalfHeightM;
            case "restitution" -> restitution;
            case "friction" -> friction;
            case "crash_horizontal_mps" -> crashHorizontalMps;
            case "crash_vertical_mps" -> crashVerticalMps;
            default -> throw new IllegalArgumentException("unknown vehicle parameter: " + name);
        };
    }
    /** Applies every {@code -Dmini_drone.vehicle.<name>} override that parses. */
    public static VehicleModel fromProperties(Properties properties) {
        VehicleModel model = DEFAULTS;
        if (properties == null) {
            return model;
        }
        for (String name : parameterNames()) {
            String raw = properties.getProperty(PROPERTY_PREFIX + name);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            Double parsed = parse(raw);
            if (parsed == null) {
                continue;
            }
            try {
                model = model.with(name, parsed);
            } catch (IllegalArgumentException ignored) {
                // Out of range: keep the default rather than refusing to start.
            }
        }
        return model;
    }

    /** One parameter, as text, for the command's output and for JVM arguments. */
    public String describe() {
        StringBuilder report = new StringBuilder();
        for (Map.Entry<String, Double> entry : byName().entrySet()) {
            report.append(String.format(Locale.ROOT, "%n  %-26s %s", entry.getKey(), format(entry.getValue())));
        }
        report.append(String.format(
            Locale.ROOT,
            "%n  derived: weight %.3f N, max thrust %.3f N, hover throttle %.0f%%, top speed %.2f m/s",
            weightN(), maxThrustN(), hoverThrottle() * 100.0, topSpeedMps()));
        return report.toString();
    }

    private static String format(double value) {
        if (value == Math.rint(value)) {
            return String.format(Locale.ROOT, "%.0f", value);
        }
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static Double parse(String raw) {
        try {
            double value = Double.parseDouble(raw.trim());
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
