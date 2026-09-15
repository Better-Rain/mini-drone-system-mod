package com.vltbr.minidrone.item;

import com.vltbr.minidrone.world.FieldSelectorStore;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

/**
 * The measuring tool for a hand-made field: right-click two opposite corners, then
 * run {@code /minidrone field set selected}.
 *
 * <p>Two points are enough to derive a field from, because a field is axis-aligned;
 * clicking a third block starts a new pair, which is what an operator who misclicked
 * expects. The points live in {@link FieldSelectorStore} for the session only.
 */
public class FieldSelectorItem extends Item {
    public FieldSelectorItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        var player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        if (context.getLevel().isClientSide()) {
            // The server decides and answers; the client only has to show that the
            // click was accepted.
            return InteractionResult.SUCCESS;
        }

        BlockPos pos = context.getClickedPos();
        int count = FieldSelectorStore.record(player.getUUID(), pos.getX(), pos.getY(), pos.getZ());
        if (count < FieldSelectorStore.MAX_POINTS) {
            player.displayClientMessage(
                Component.literal(String.format(
                    "Corner %d recorded: (%d, %d, %d). Right-click the opposite corner.",
                    count, pos.getX(), pos.getY(), pos.getZ())),
                false
            );
        } else {
            player.displayClientMessage(
                Component.literal(String.format(
                    "Two corners recorded: (%d, %d, %d) and the opposite one. Run /minidrone field set selected.",
                    pos.getX(), pos.getY(), pos.getZ()))
                    .withStyle(ChatFormatting.AQUA),
                false
            );
        }
        return InteractionResult.SUCCESS;
    }
}
