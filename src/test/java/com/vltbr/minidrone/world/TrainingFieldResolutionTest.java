package com.vltbr.minidrone.world;

import com.vltbr.minidrone.world.TrainingArenaController.ArenaInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The precedence between a hand-made field, the generated arena and an instance
 * default. Clearing the generated arena must not throw away a field the operator
 * measured by hand, which is the case this pins down.
 */
class TrainingFieldResolutionTest {
    private static final ArenaInfo ARENA = new ArenaInfo(true, -1, -60, 17, 13, 13, 173);
    private static final ArenaInfo NO_ARENA = new ArenaInfo(false, 0, 0, 0, 0, 0, 0);

    @Test
    void prefersTheHandMadeField() {
        TrainingFieldDefinition manual = TrainingFieldDefinition.fromCorners(
            -20, 0, 0, 8, 64, TrainingFieldDefinition.Source.CORNERS);
        TrainingFieldDefinition instanceDefault = TrainingFieldDefinition.fromCorners(
            0, 0, 4, 4, 64, TrainingFieldDefinition.Source.CORNERS);

        assertSame(manual, TrainingFieldResolution.resolve(manual, ARENA, instanceDefault));
    }

    @Test
    void fallsBackToTheGeneratedArena() {
        TrainingFieldDefinition resolved = TrainingFieldResolution.resolve(null, ARENA, null);

        assertEquals(13, resolved.widthM());
        assertEquals(13, resolved.depthM());
        assertEquals(-60, resolved.topY());
        assertEquals(TrainingFieldDefinition.Source.ARENA, resolved.source());
        // The arena is centred on its own landing pad, so the origin is its centre.
        assertEquals(-0.5, resolved.originX(), 0.0001);
        assertEquals(17.5, resolved.originZ(), 0.0001);
        assertEquals(0.0, resolved.centerOffsetM()[0], 0.0001);
    }

    @Test
    void fallsBackToTheInstanceDefaultOnlyWhenNothingElseExists() {
        TrainingFieldDefinition instanceDefault = TrainingFieldDefinition.fromCorners(
            0, 0, 4, 4, 64, TrainingFieldDefinition.Source.CORNERS);

        assertSame(instanceDefault, TrainingFieldResolution.resolve(null, NO_ARENA, instanceDefault));
        assertNull(TrainingFieldResolution.resolve(null, NO_ARENA, null));
    }

    /** A player-relative origin needs no field at all, and must not invent one. */
    @Test
    void reportsNoFieldWhenTheArenaIsAbsent() {
        assertNull(TrainingFieldResolution.fromArena(NO_ARENA));
        assertNull(TrainingFieldResolution.fromArena(null));
    }
}
