package com.vltbr.minidrone.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * World-persistent training field the operator defined by hand.
 *
 * <p>Separate from {@link TrainingArenaSavedData} on purpose: that one records the
 * blocks the mod generated so they can be removed again, while this one describes
 * the field itself. Keeping them apart is what lets {@code arena clear} remove the
 * generated platform without silently throwing away a field the operator measured
 * and typed in.
 */
public final class TrainingFieldSavedData extends SavedData {
    public static final String DATA_ID = "mini_drone_training_field";
    public static final DataFixTypes DATA_FIX_TYPE = DataFixTypes.LEVEL;

    private boolean present;
    private int minX;
    private int minZ;
    private int maxX;
    private int maxZ;
    private int topY;
    private double originX;
    private double originZ;
    private TrainingFieldDefinition.OriginMode originMode = TrainingFieldDefinition.OriginMode.CENTRE;
    private TrainingFieldDefinition.Source source = TrainingFieldDefinition.Source.CORNERS;

    public static Factory<TrainingFieldSavedData> factory() {
        return new Factory<>(
            TrainingFieldSavedData::new,
            TrainingFieldSavedData::load,
            DATA_FIX_TYPE
        );
    }

    public static TrainingFieldSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        TrainingFieldSavedData data = new TrainingFieldSavedData();
        if (!tag.getBoolean("has_field")) {
            return data;
        }
        try {
            data.minX = tag.getInt("min_x");
            data.minZ = tag.getInt("min_z");
            data.maxX = tag.getInt("max_x");
            data.maxZ = tag.getInt("max_z");
            data.topY = tag.getInt("top_y");
            data.originX = tag.getDouble("origin_x");
            data.originZ = tag.getDouble("origin_z");
            data.originMode = TrainingFieldDefinition.OriginMode.valueOf(tag.getString("origin_mode"));
            data.source = TrainingFieldDefinition.Source.valueOf(tag.getString("source"));
            data.present = true;
        } catch (IllegalArgumentException exception) {
            // A profile written by another version, or edited by hand: treat it as
            // "no field" rather than as a broken world.
            data.present = false;
        }
        return data;
    }

    public boolean hasField() {
        return present;
    }

    /** The stored field, or null when the operator has not defined one. */
    public TrainingFieldDefinition field() {
        if (!present) {
            return null;
        }
        return new TrainingFieldDefinition(
            minX, minZ, maxX, maxZ, topY, originX, originZ, originMode, source);
    }

    public void setField(TrainingFieldDefinition field) {
        minX = field.minX();
        minZ = field.minZ();
        maxX = field.maxX();
        maxZ = field.maxZ();
        topY = field.topY();
        originX = field.originX();
        originZ = field.originZ();
        originMode = field.originMode();
        source = field.source();
        present = true;
        setDirty();
    }

    public void clearField() {
        present = false;
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean("has_field", present);
        if (!present) {
            return tag;
        }
        tag.putInt("min_x", minX);
        tag.putInt("min_z", minZ);
        tag.putInt("max_x", maxX);
        tag.putInt("max_z", maxZ);
        tag.putInt("top_y", topY);
        tag.putDouble("origin_x", originX);
        tag.putDouble("origin_z", originZ);
        tag.putString("origin_mode", originMode.name());
        tag.putString("source", source.name());
        return tag;
    }
}
