package com.vltbr.minidrone.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
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
    // Positions of the marker blocks the operator placed. They are recorded as they
    // are placed and removed so a change can re-derive the field in constant time;
    // a full sweep of the area is only needed to pick up markers this registry never
    // saw (placed by another tool, or before the field existed).
    private final java.util.List<int[]> cornerMarkers = new java.util.ArrayList<>();
    private final java.util.List<int[]> centreMarkers = new java.util.ArrayList<>();

    public static Factory<TrainingFieldSavedData> factory() {
        return new Factory<>(
            TrainingFieldSavedData::new,
            TrainingFieldSavedData::load,
            DATA_FIX_TYPE
        );
    }

    public static TrainingFieldSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        TrainingFieldSavedData data = new TrainingFieldSavedData();
        data.readMarkers(tag.getList("corner_markers", Tag.TAG_COMPOUND), data.cornerMarkers);
        data.readMarkers(tag.getList("centre_markers", Tag.TAG_COMPOUND), data.centreMarkers);
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

    private void readMarkers(ListTag tag, java.util.List<int[]> target) {
        for (int index = 0; index < tag.size(); index++) {
            CompoundTag entry = tag.getCompound(index);
            target.add(new int[] {entry.getInt("x"), entry.getInt("y"), entry.getInt("z")});
        }
    }

    private static ListTag writeMarkers(java.util.List<int[]> positions) {
        ListTag tag = new ListTag();
        for (int[] position : positions) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("x", position[0]);
            entry.putInt("y", position[1]);
            entry.putInt("z", position[2]);
            tag.add(entry);
        }
        return tag;
    }

    /** Recorded corner marker positions, as {@code {x, y, z}} triples. */
    public java.util.List<int[]> cornerMarkers() {
        return java.util.Collections.unmodifiableList(cornerMarkers);
    }

    /** Recorded centre marker positions, as {@code {x, y, z}} triples. */
    public java.util.List<int[]> centreMarkers() {
        return java.util.Collections.unmodifiableList(centreMarkers);
    }

    private static boolean samePosition(int[] position, int x, int y, int z) {
        return position[0] == x && position[1] == y && position[2] == z;
    }

    /** Records or drops a marker position. Returns true when the registry changed. */
    public boolean setMarker(boolean centre, int x, int y, int z, boolean placed) {
        java.util.List<int[]> positions = centre ? centreMarkers : cornerMarkers;
        boolean removed = positions.removeIf(position -> samePosition(position, x, y, z));
        if (placed) {
            positions.add(new int[] {x, y, z});
        }
        setDirty();
        return placed || removed;
    }

    /** Replaces the whole marker registry, which is what a sweep produces. */
    public void replaceMarkers(
        java.util.List<int[]> corners,
        java.util.List<int[]> centres
    ) {
        cornerMarkers.clear();
        cornerMarkers.addAll(corners);
        centreMarkers.clear();
        centreMarkers.addAll(centres);
        setDirty();
    }

    public void clearMarkers() {
        cornerMarkers.clear();
        centreMarkers.clear();
        setDirty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("corner_markers", writeMarkers(cornerMarkers));
        tag.put("centre_markers", writeMarkers(centreMarkers));
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
