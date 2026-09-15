package com.vltbr.minidrone.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** World-persistent list of blocks actually placed by the training arena. */
public final class TrainingArenaSavedData extends SavedData {
    public static final String DATA_ID = "mini_drone_training_arena";

    /**
     * The data-fix type handed to {@link Factory}.
     *
     * <p>It must not be null. Loading an existing file calls
     * {@code DataFixTypes.update(...)} on this value before the deserializer
     * runs, so a null type throws inside the load path - where the exception is
     * caught and only logged. The factory then builds a fresh default and the
     * recorded arena looks like it was never saved, while its blocks are still
     * in the world and {@code arena clear} can no longer remove them.
     *
     * <p>{@code LEVEL} is the conventional choice for third-party schemas: the
     * fixers only run when the stored DataVersion differs from the running one.
     */
    public static final DataFixTypes DATA_FIX_TYPE = DataFixTypes.LEVEL;

    private int centerX;
    private int topY;
    private int centerZ;
    // The footprint is stored with the arena, not read from the JVM properties at
    // load time: an arena that was built as a rectangle must keep reporting the
    // size it actually occupies even if the properties change later.
    private int radiusX = TrainingArenaLayout.RADIUS;
    private int radiusZ = TrainingArenaLayout.RADIUS;
    private final List<PlacedBlock> placedBlocks = new ArrayList<>();

    public static Factory<TrainingArenaSavedData> factory() {
        return new Factory<>(
            TrainingArenaSavedData::new,
            TrainingArenaSavedData::load,
            DATA_FIX_TYPE
        );
    }

    public static TrainingArenaSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        TrainingArenaSavedData data = new TrainingArenaSavedData();
        data.centerX = tag.getInt("center_x");
        data.topY = tag.getInt("top_y");
        data.centerZ = tag.getInt("center_z");
        // Arenas saved before the footprint existed were square at the default
        // radius, which is exactly what the fallback produces.
        data.radiusX = tag.contains("radius_x")
            ? tag.getInt("radius_x")
            : TrainingArenaLayout.RADIUS;
        data.radiusZ = tag.contains("radius_z")
            ? tag.getInt("radius_z")
            : TrainingArenaLayout.RADIUS;
        ListTag blocks = tag.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompound(i);
            try {
                data.placedBlocks.add(new PlacedBlock(
                    block.getInt("x"), block.getInt("y"), block.getInt("z"),
                    TrainingArenaLayout.Kind.valueOf(block.getString("kind"))
                ));
            } catch (IllegalArgumentException ignored) {
                // Ignore entries from an incompatible or manually edited save.
            }
        }
        return data;
    }

    public boolean hasArena() {
        return !placedBlocks.isEmpty();
    }

    public TrainingArenaLayout layout() {
        return TrainingArenaLayout.centered(centerX, topY, centerZ, radiusX, radiusZ);
    }

    public List<PlacedBlock> placedBlocks() {
        return Collections.unmodifiableList(placedBlocks);
    }

    public void replaceWith(TrainingArenaLayout layout, List<PlacedBlock> blocks) {
        centerX = layout.centerX();
        topY = layout.topY();
        centerZ = layout.centerZ();
        radiusX = layout.radiusX();
        radiusZ = layout.radiusZ();
        placedBlocks.clear();
        placedBlocks.addAll(blocks);
        setDirty();
    }

    public void clearArena() {
        centerX = 0;
        topY = 0;
        centerZ = 0;
        radiusX = TrainingArenaLayout.RADIUS;
        radiusZ = TrainingArenaLayout.RADIUS;
        placedBlocks.clear();
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("center_x", centerX);
        tag.putInt("top_y", topY);
        tag.putInt("center_z", centerZ);
        tag.putInt("radius_x", radiusX);
        tag.putInt("radius_z", radiusZ);
        ListTag blocks = new ListTag();
        for (PlacedBlock placed : placedBlocks) {
            CompoundTag block = new CompoundTag();
            block.putInt("x", placed.x());
            block.putInt("y", placed.y());
            block.putInt("z", placed.z());
            block.putString("kind", placed.kind().name());
            blocks.add(block);
        }
        tag.put("blocks", blocks);
        return tag;
    }

    public record PlacedBlock(int x, int y, int z, TrainingArenaLayout.Kind kind) {
    }
}
