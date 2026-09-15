package com.vltbr.minidrone.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The corner points an operator recorded with the field selector item.
 *
 * <p>Session state on purpose: it is a measuring tape, not a definition. The field
 * it produces is stored in the world once {@code /minidrone field set selected}
 * turns the two points into one, and losing the points on a restart only means
 * clicking two blocks again - while persisting them would invite a stale pair to be
 * applied to a world it was never measured in.
 */
public final class FieldSelectorStore {
    /** The two most recent points define the field; older ones are dropped. */
    public static final int MAX_POINTS = 2;

    private static final Map<UUID, List<int[]>> POINTS = new ConcurrentHashMap<>();

    private FieldSelectorStore() {
    }

    /** Records a point and returns how many are held now. */
    public static int record(UUID player, int x, int y, int z) {
        List<int[]> points = POINTS.computeIfAbsent(player, key -> new ArrayList<>());
        synchronized (points) {
            points.add(new int[] {x, y, z});
            while (points.size() > MAX_POINTS) {
                points.remove(0);
            }
            return points.size();
        }
    }

    public static List<int[]> points(UUID player) {
        List<int[]> points = POINTS.get(player);
        if (points == null) {
            return List.of();
        }
        synchronized (points) {
            List<int[]> copy = new ArrayList<>(points.size());
            for (int[] point : points) {
                copy.add(new int[] {point[0], point[1], point[2]});
            }
            return Collections.unmodifiableList(copy);
        }
    }

    public static void clear(UUID player) {
        POINTS.remove(player);
    }

    static void clearAll() {
        POINTS.clear();
    }
}
