package com.vltbr.minidrone.item;

import com.vltbr.minidrone.sim.VirtualDroneManager;
import com.vltbr.minidrone.world.DronePlacementHook;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

/**
 * Right-click a block to add a drone there, or a drone to collect that one.
 *
 * <p>Placing is how a fleet grows: every right-click on a block adds another aircraft with
 * the next id and MAVLink system id, at that spot. It used to carry the single vehicle
 * around - the manual counterpart of {@code /minidrone drone reset} - and that behaviour is
 * now the other half of this item: a right-click on an existing drone brings <em>that</em>
 * vehicle back to the field origin. The field and its origin stay where they are either way,
 * so the flight controller's local position is the offset from that origin, which is what a
 * real drone sitting away from the room origin would report.
 */
public class DronePlacementItem extends Item {
    public DronePlacementItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        var player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (context.getLevel().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        BlockPos pos = context.getClickedPos();
        VirtualDroneManager.PlacementOutcome outcome = DronePlacementHook.placeOn(
            context.getLevel(), pos, player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                ? serverPlayer
                : null);

        switch (outcome.result()) {
            case PLACED -> {
                // The hook already told the operator which drone went where.
            }
            case FLEET_FULL -> player.displayClientMessage(
                Component.literal(String.format(
                    "The fleet is full (%d drones). Collect one before adding another.",
                    com.vltbr.minidrone.sim.VirtualDroneFleet.MAX_DRONES))
                    .withStyle(ChatFormatting.RED),
                false
            );
            case NO_FIELD -> player.displayClientMessage(
                Component.literal(
                    "No training field is defined yet, so there is no origin for the drone to be placed relative to.")
                    .withStyle(ChatFormatting.RED),
                false
            );
        }
        return InteractionResult.SUCCESS;
    }
}
