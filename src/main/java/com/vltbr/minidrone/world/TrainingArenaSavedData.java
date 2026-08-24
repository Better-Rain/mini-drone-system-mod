package com.vltbr.minidrone.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** World-persistent list of blocks actually placed by the training arena. */
public final class TrainingArenaSavedData extends SavedData {
    public static final String DATA_ID = "mini_drone_training_arena";

    private int centerX;
    private int topY;
    private int centerZ;
    private final List<PlacedBlock> placedBlocks = new ArrayList<>();

    public static Factory<TrainingArenaSavedData> factory() {
        return new Factory<>(TrainingArenaSavedData::new, TrainingArenaSavedData::load, null);
    }

    public static TrainingArenaSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        TrainingArenaSavedData data = new TrainingArenaSavedData();
        data.centerX = tag.getInt("center_x");
        data.topY = tag.getInt("top_y");
        data.centerZ = tag.getInt("center_z");
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
        return TrainingArenaLayout.centered(centerX, topY, centerZ);
    }

    public List<PlacedBlock> placedBlocks() {
        return Collections.unmodifiableList(placedBlocks);
    }

    public void replaceWith(TrainingArenaLayout layout, List<PlacedBlock> blocks) {
        centerX = layout.centerX();
        topY = layout.topY();
        centerZ = layout.centerZ();
        placedBlocks.clear();
        placedBlocks.addAll(blocks);
        setDirty();
    }

    public void clearArena() {
        centerX = 0;
        topY = 0;
        centerZ = 0;
        placedBlocks.clear();
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("center_x", centerX);
        tag.putInt("top_y", topY);
        tag.putInt("center_z", centerZ);
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
