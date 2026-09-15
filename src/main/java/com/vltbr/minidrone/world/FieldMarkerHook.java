package com.vltbr.minidrone.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * Bridge between the marker blocks and the field controller that owns this world.
 *
 * <p>The block classes are registered statically, so they cannot hold a reference to
 * the per-world controller; this holder is pointed at the active one while a world
 * runs and cleared when it stops. A call that arrives while no world is running is
 * dropped rather than queued: there is nothing to update, and the definition is
 * re-derived from the recorded markers on the next load anyway.
 */
public final class FieldMarkerHook {
    private static volatile TrainingFieldController controller;

    private FieldMarkerHook() {
    }

    public static void install(TrainingFieldController active) {
        controller = active;
    }

    public static void uninstall(TrainingFieldController active) {
        if (controller == active) {
            controller = null;
        }
    }

    /** Called by the marker blocks when one is placed or removed. */
    public static void markerChanged(Level level, BlockPos pos, boolean centre, boolean placed) {
        if (!(level instanceof ServerLevel)) {
            return;
        }
        TrainingFieldController active = controller;
        if (active == null) {
            return;
        }
        active.markerChanged(pos, centre, placed);
    }
}
