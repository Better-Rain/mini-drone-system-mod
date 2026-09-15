package com.vltbr.minidrone.block;

import com.vltbr.minidrone.world.FieldMarkerHook;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A block the operator places to describe the training field.
 *
 * <p>{@code field_corner} marks a corner: two diagonal corners are enough to derive
 * the rectangle, and four give the same rectangle. {@code field_center} marks the
 * point that becomes the local NED origin, which is how a room whose capture origin
 * is off centre is set up.
 *
 * <p>Both kinds report placement and removal to the field controller, so the field
 * follows the blocks the operator moves. Nothing else is stored in the block: the
 * definition belongs to the world's field data, not to a block entity, which keeps
 * it readable (and re-derivable) even if the blocks are later replaced.
 */
public class FieldMarkerBlock extends Block {
    public enum Kind {
        CORNER,
        CENTRE
    }

    private final Kind kind;

    public FieldMarkerBlock(Kind kind, Properties properties) {
        super(properties);
        this.kind = kind;
    }

    public Kind markerKind() {
        return kind;
    }

    @Override
    public void setPlacedBy(
        Level level,
        BlockPos pos,
        BlockState state,
        LivingEntity placer,
        ItemStack stack
    ) {
        super.setPlacedBy(level, pos, state, placer, stack);
        FieldMarkerHook.markerChanged(level, pos, kind == Kind.CENTRE, true);
    }

    @Override
    protected void onRemove(
        BlockState state,
        Level level,
        BlockPos pos,
        BlockState newState,
        boolean movedByPiston
    ) {
        super.onRemove(state, level, pos, newState, movedByPiston);
        // Replacing a marker with any other block removes the marker just as
        // breaking it does, and the field has to follow either way.
        if (!state.is(newState.getBlock())) {
            FieldMarkerHook.markerChanged(level, pos, kind == Kind.CENTRE, false);
        }
    }
}
