package com.vltbr.minidrone.world;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainingArenaLayoutTest {
    @Test
    void createsFlatPlatformWithBorderAndFourCornerMarkers() {
        TrainingArenaLayout layout = TrainingArenaLayout.centered(10, 70, -4);

        Map<TrainingArenaLayout.Kind, Long> counts = layout.blocks().stream()
            .collect(Collectors.groupingBy(TrainingArenaLayout.RelativeBlock::kind, Collectors.counting()));

        assertEquals(112L, counts.get(TrainingArenaLayout.Kind.PLATFORM));
        assertEquals(48L, counts.get(TrainingArenaLayout.Kind.BORDER));
        assertEquals(4L, counts.get(TrainingArenaLayout.Kind.CORNER_MARKER));
        assertEquals(8L, counts.get(TrainingArenaLayout.Kind.LANDING_PAD));
        assertEquals(1L, counts.get(TrainingArenaLayout.Kind.LANDING_CENTER));
        assertEquals(173, layout.blocks().size());
        assertEquals(10, layout.centerX());
        assertEquals(70, layout.topY());
        assertEquals(-4, layout.centerZ());
    }

    @Test
    void keepsLayoutCenteredAtRequestedCoordinates() {
        TrainingArenaLayout layout = TrainingArenaLayout.centered(10, 70, -4);

        assertTrue(layout.blocks().stream().anyMatch(block ->
            block.dx() == 0 && block.dy() == 0 && block.dz() == 0
                && block.kind() == TrainingArenaLayout.Kind.LANDING_CENTER));
        assertEquals(8, layout.blocks().stream()
            .filter(block -> block.kind() == TrainingArenaLayout.Kind.LANDING_PAD)
            .count());
    }
}
