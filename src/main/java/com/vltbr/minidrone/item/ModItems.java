package com.vltbr.minidrone.item;

import com.vltbr.minidrone.MiniDroneMod;
import com.vltbr.minidrone.block.ModBlocks;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;

/**
 * The field tools: the selector item, plus a place in the creative menu for it and
 * for the marker blocks so an operator can find them without knowing the ids.
 */
public final class ModItems {
    public static final Item FIELD_SELECTOR = Registry.register(
        BuiltInRegistries.ITEM,
        MiniDroneMod.id("field_selector"),
        new FieldSelectorItem(new Item.Properties().stacksTo(1))
    );
    public static final Item DRONE_PLACEMENT = Registry.register(
        BuiltInRegistries.ITEM,
        MiniDroneMod.id("drone_placement"),
        new DronePlacementItem(new Item.Properties().stacksTo(1))
    );

    public static void initialize() {
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS)
            .register(entries -> {
                entries.accept(ModBlocks.FIELD_CORNER);
                entries.accept(ModBlocks.FIELD_CENTER);
            });
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES)
            .register(entries -> {
                entries.accept(FIELD_SELECTOR);
                entries.accept(DRONE_PLACEMENT);
            });
    }

    private ModItems() {
    }
}
