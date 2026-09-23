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

    /**
     * Adds a drone on top of the block the operator clicked.
     *
     * <p>This is how a second aircraft appears: the fleet hands out the next id and MAVLink
     * system id, and the vehicle starts its life standing where it was put. It used to carry
     * the one vehicle to that block; with a fleet, "put the drone here" can only mean "this
     * is where a drone is", because there is no other way to ask for a second one.
     */
    public static VirtualDroneManager.PlacementOutcome placeOn(
        Level level, BlockPos pos, ServerPlayer player
    ) {
        VirtualDroneManager active = manager;
        if (active == null || !(level instanceof net.minecraft.server.level.ServerLevel)) {
            return new VirtualDroneManager.PlacementOutcome(
                VirtualDroneManager.PlacementResult.NO_FIELD, "");
        }
        VirtualDroneManager.PlacementOutcome outcome = active.placeNewDrone(
            pos.getX() + 0.5,
            pos.getY() + 1.0,
            pos.getZ() + 0.5
        );
        if (outcome.result() == VirtualDroneManager.PlacementResult.PLACED && player != null) {
            player.displayClientMessage(
                net.minecraft.network.chat.Component.literal(String.format(
                    "%s placed at (%d, %d, %d). The field and its origin did not move.",
                    outcome.droneId(), pos.getX(), pos.getY() + 1, pos.getZ())),
                false
            );
        }
        return outcome;
    }

    /**
     * Picks one drone back up: right-clicking a vehicle returns <em>that</em> vehicle to the
     * field origin.
     *
     * <p>The counterpart of placing it, and per drone: the clicked entity says which one it
     * is, so collecting the second aircraft never moves the first. Before the entity carried
     * a drone id, this could only mean the primary vehicle.
     */
    public static VirtualDroneManager.OriginResetResult collect(ServerPlayer player, String droneId) {
        VirtualDroneManager active = manager;
        if (active == null) {
            // Distinguishable in the log, because "no manager installed" and "wrong
            // dimension" used to come back as the same answer and the message then blamed
            // the dimension for both.
            LOGGER.info("Collect refused: no virtual drone manager is installed for this world.");
            return VirtualDroneManager.OriginResetResult.WRONG_DIMENSION;
        }
        VirtualDroneManager.OriginResetResult result = active.resetFlightOrigin(player, droneId);
        LOGGER.info(
            "Collect for {} in {} returned {}",
            player.getName().getString(),
            player.serverLevel().dimension().location(),
            result);
        if (result == VirtualDroneManager.OriginResetResult.RESET) {
            player.displayClientMessage(
                net.minecraft.network.chat.Component.literal(
                    droneId + " returned to the field origin."),
                false
            );
        }
        return result;
    }

    private static final org.slf4j.Logger LOGGER =
        org.slf4j.LoggerFactory.getLogger("mini-drone-placement");
}
