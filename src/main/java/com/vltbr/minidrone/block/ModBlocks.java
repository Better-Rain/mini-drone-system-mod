package com.vltbr.minidrone.block;

import com.vltbr.minidrone.MiniDroneMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * The marker blocks an operator uses to describe a training field by hand.
 *
 * <p>Both are ordinary full blocks carrying a {@link BlockItem}: the field is a
 * property of the world (see {@code TrainingFieldSavedData}), not of the block, so
 * there is no block entity to keep in sync and an operator can replace a marker
 * without leaving anything behind. They glow slightly, so a field laid out in a
 * dark room can still be found.
 */
public final class ModBlocks {
    public static final Block FIELD_CORNER = registerMarker(
        "field_corner", FieldMarkerBlock.Kind.CORNER, 4);
    public static final Block FIELD_CENTER = registerMarker(
        "field_center", FieldMarkerBlock.Kind.CENTRE, 8);

    private static Block registerMarker(String name, FieldMarkerBlock.Kind kind, int light) {
        var id = MiniDroneMod.id(name);
        Block block = Registry.register(
            BuiltInRegistries.BLOCK,
            id,
            new FieldMarkerBlock(
                kind,
                BlockBehaviour.Properties.of()
                    .strength(0.4F)
                    .sound(SoundType.STONE)
                    .lightLevel(state -> light)
            )
        );
        Registry.register(BuiltInRegistries.ITEM, id, new BlockItem(block, new Item.Properties()));
        return block;
    }

    public static void initialize() {
        // Loading this class performs the registry operations above.
    }

    private ModBlocks() {
    }
}
