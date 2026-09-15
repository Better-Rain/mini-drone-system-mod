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
 * Right-click a block to put the virtual drone there.
 *
 * <p>It is the manual counterpart of {@code /minidrone drone reset}: the operator
 * carries the vehicle, the field and its origin stay where they are, and the flight
 * controller's local position becomes the offset from that origin - which is what a
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
        VirtualDroneManager.PlacementResult result = DronePlacementHook.placeOn(
            context.getLevel(), pos, player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                ? serverPlayer
                : null);

        switch (result) {
            case PLACED -> {
                // The hook already told the operator where it went.
            }
            case DRONE_ARMED -> player.displayClientMessage(
                Component.literal("Land and disarm the virtual drone before placing it.")
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
