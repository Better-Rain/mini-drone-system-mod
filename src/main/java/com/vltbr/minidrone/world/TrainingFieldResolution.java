package com.vltbr.minidrone.world;

import com.vltbr.minidrone.world.TrainingArenaController.ArenaInfo;

/**
 * Which field is in effect.
 *
 * <p>Three things can describe a field and they must not fight: what the operator
 * defined by hand (commands, marker blocks, the selector item), the platform the
 * mod generated with {@code /minidrone arena create}, and the defaults an instance
 * started with ({@code -Dmini_drone.field.*}). The manual definition wins, because
 * it is the most specific statement about this world; the generated arena is next;
 * instance defaults only apply while nothing else says otherwise. A consequence
 * worth stating: clearing the generated arena must not silently drop a hand-made
 * field, and it does not - the manual definition simply stays in effect.
 *
 * <p>Kept free of Minecraft world classes so the rule is unit-tested, not observed.
 */
public final class TrainingFieldResolution {
    private TrainingFieldResolution() {
    }

    /** The arena, as far as this rule needs to know about it. */
    public static TrainingFieldDefinition fromArena(ArenaInfo arena) {
        if (arena == null || !arena.present()) {
            return null;
        }
        return TrainingFieldDefinition.fromCentreAndSize(
            arena.centerX(),
            arena.centerZ(),
            arena.widthM(),
            arena.depthM(),
            arena.topY(),
            TrainingFieldDefinition.Source.ARENA
        );
    }

    public static TrainingFieldDefinition resolve(
        TrainingFieldDefinition manual,
        ArenaInfo arena,
        TrainingFieldDefinition instanceDefault
    ) {
        if (manual != null) {
            return manual;
        }
        TrainingFieldDefinition generated = fromArena(arena);
        if (generated != null) {
            return generated;
        }
        return instanceDefault;
    }
}
