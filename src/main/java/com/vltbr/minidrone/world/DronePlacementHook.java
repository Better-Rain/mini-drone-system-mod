package com.vltbr.minidrone.world;

import com.vltbr.minidrone.sim.VirtualDroneManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * Bridge between the placement item and whichever drone manager owns this world.
 *
 * <p>Same shape as {@link FieldMarkerHook}, and for the same reason: items are
 * registered statically while the manager lives per world. Placement needs the
 * player as well, because the answer is shown in chat.
 */
public final class DronePlacementHook {
    private static volatile VirtualDroneManager manager;

    private DronePlacementHook() {
    }

    public static void install(VirtualDroneManager active) {
        manager = active;
    }

    public static void uninstall(VirtualDroneManager active) {
        if (manager == active) {
            manager = null;
        }
    }

    public static boolean isActive() {
        return manager != null;
    }

    /** Carries the drone to the top of the block the operator clicked. */
    public static VirtualDroneManager.PlacementResult placeOn(Level level, BlockPos pos, ServerPlayer player) {
        VirtualDroneManager active = manager;
        if (active == null || !(level instanceof net.minecraft.server.level.ServerLevel)) {
            return VirtualDroneManager.PlacementResult.NO_FIELD;
        }
        VirtualDroneManager.PlacementResult result = active.placeAt(
            pos.getX() + 0.5,
            pos.getY() + 1.0,
            pos.getZ() + 0.5
        );
        if (result == VirtualDroneManager.PlacementResult.PLACED && player != null) {
            player.displayClientMessage(
                net.minecraft.network.chat.Component.literal(String.format(
                    "Drone placed at (%d, %d, %d). The field and its origin did not move.",
                    pos.getX(), pos.getY() + 1, pos.getZ())),
                false
            );
        }
        return result;
    }
}
