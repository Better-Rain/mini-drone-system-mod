package com.vltbr.minidrone.world;

import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

/**
 * Instance-level field defaults, read from JVM properties.
 *
 * <p>They exist for two reasons: an operator can pin a field for a launcher
 * profile without typing anything in game, and a field can be exercised on a
 * headless run. Either form describes the same axis-aligned rectangle as the
 * in-game commands:
 *
 * <pre>
 * -Dmini_drone.field.corners=x1,z1,x2,z2[,y]
 * -Dmini_drone.field.center=x,z,widthM,depthM[,y]
 * </pre>
 *
 * <p>A malformed value is ignored rather than fatal: a typo in a launcher argument
 * must not stop the world from loading.
 */
public final class TrainingFieldDefaults {
    public static final String CORNERS_PROPERTY = "mini_drone.field.corners";
    public static final String CENTER_PROPERTY = "mini_drone.field.center";
    /**
     * Placeholder for "the surface layer is wherever the ground is". The world side
     * replaces it with the height at the field centre, so an operator does not have
     * to know the Y of their own floor.
     */
    public static final int DERIVE_Y_FROM_GROUND = Integer.MIN_VALUE;

    private TrainingFieldDefaults() {
    }

    public static Optional<TrainingFieldDefinition> fromSystemProperties() {
        return fromProperties(System.getProperties());
    }

    public static Optional<TrainingFieldDefinition> fromProperties(Properties properties) {
        if (properties == null) {
            return Optional.empty();
        }
        Optional<TrainingFieldDefinition> fromCorners =
            parseCorners(properties.getProperty(CORNERS_PROPERTY));
        if (fromCorners.isPresent()) {
            return fromCorners;
        }
        return parseCenter(properties.getProperty(CENTER_PROPERTY));
    }

    static Optional<TrainingFieldDefinition> parseCorners(String value) {
        double[] numbers = parseNumbers(value, 4, 5);
        if (numbers == null) {
            return Optional.empty();
        }
        int y = numbers.length == 5 ? (int) Math.round(numbers[4]) : DERIVE_Y_FROM_GROUND;
        try {
            return Optional.of(TrainingFieldDefinition.fromCorners(
                (int) Math.round(numbers[0]),
                (int) Math.round(numbers[1]),
                (int) Math.round(numbers[2]),
                (int) Math.round(numbers[3]),
                y,
                TrainingFieldDefinition.Source.CORNERS
            ));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    static Optional<TrainingFieldDefinition> parseCenter(String value) {
        double[] numbers = parseNumbers(value, 4, 5);
        if (numbers == null) {
            return Optional.empty();
        }
        int y = numbers.length == 5 ? (int) Math.round(numbers[4]) : DERIVE_Y_FROM_GROUND;
        try {
            return Optional.of(TrainingFieldDefinition.fromCentreAndSize(
                (int) Math.round(numbers[0]),
                (int) Math.round(numbers[1]),
                (int) Math.round(numbers[2]),
                (int) Math.round(numbers[3]),
                y,
                TrainingFieldDefinition.Source.CENTRE_AND_SIZE
            ));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /** Parses {@code a,b,c,...} into {@code min..max} numbers, or null. */
    private static double[] parseNumbers(String value, int min, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String[] parts = value.split(",");
        if (parts.length < min || parts.length > max) {
            return null;
        }
        double[] numbers = new double[parts.length];
        for (int index = 0; index < parts.length; index++) {
            try {
                numbers[index] = Double.parseDouble(parts[index].trim());
            } catch (NumberFormatException exception) {
                return null;
            }
            if (!Double.isFinite(numbers[index])) {
                return null;
            }
        }
        return numbers;
    }

    /** Documentation string for chat and logs; ASCII only. */
    public static String describeProperties() {
        return String.format(
            Locale.ROOT,
            "%s=x1,z1,x2,z2[,y] or %s=x,z,widthM,depthM[,y]",
            CORNERS_PROPERTY,
            CENTER_PROPERTY
        );
    }
}
