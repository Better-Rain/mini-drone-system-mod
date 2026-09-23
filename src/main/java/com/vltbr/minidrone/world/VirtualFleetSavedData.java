package com.vltbr.minidrone.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The drones this world has, saved with it.
 *
 * <p>Placed aircraft belong to the world: the operator put them there, so they have to be
 * there again after a restart. The entity that shows a drone is written with the world too,
 * but a drone is more than its entity - it has an id and a MAVLink system id that the
 * monitoring side already knows, and a position in the training field - so the fleet keeps
 * its own record. Losing it meant every restart left the operator with one aircraft and a
 * second one to place by hand.
 *
 * <p>Positions are stored in local NED, the frame the flight controller and the beacon use,
 * so the field origin can move (a new arena, a new origin) without dragging the drones with
 * it: whoever loads the world puts them back where they were relative to that origin.
 */
public final class VirtualFleetSavedData extends SavedData {
    public static final String DATA_ID = "mini_drone_fleet";

    /** The same type the arena uses: third-party schema, fixed up on a version change. */
    public static final DataFixTypes DATA_FIX_TYPE = DataFixTypes.LEVEL;

    /** One saved drone: what it is called, its MAVLink identity, and where it stands. */
    public record SavedDrone(String droneId, int systemId, double northM, double eastM, double downM) {
    }

    private final Map<String, SavedDrone> drones = new LinkedHashMap<>();

    public static Factory<VirtualFleetSavedData> factory() {
        return new Factory<>(
            VirtualFleetSavedData::new,
            VirtualFleetSavedData::load,
            DATA_FIX_TYPE
        );
    }

    public static VirtualFleetSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        VirtualFleetSavedData data = new VirtualFleetSavedData();
        ListTag drones = tag.getList("drones", Tag.TAG_COMPOUND);
        for (int index = 0; index < drones.size(); index++) {
            CompoundTag drone = drones.getCompound(index);
            String droneId = drone.getString("drone_id");
            if (droneId.isBlank()) {
                // An entry without an id names no vehicle; skipping it is better than
                // inventing one.
                continue;
            }
            data.drones.put(droneId, new SavedDrone(
                droneId,
                drone.getInt("system_id"),
                drone.getDouble("north_m"),
                drone.getDouble("east_m"),
                drone.getDouble("down_m")
            ));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag drones = new ListTag();
        for (SavedDrone drone : this.drones.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("drone_id", drone.droneId());
            entry.putInt("system_id", drone.systemId());
            entry.putDouble("north_m", drone.northM());
            entry.putDouble("east_m", drone.eastM());
            entry.putDouble("down_m", drone.downM());
            drones.add(entry);
        }
        tag.put("drones", drones);
        return tag;
    }

    /** The saved drones, in the order they were recorded. */
    public List<SavedDrone> drones() {
        return Collections.unmodifiableList(new ArrayList<>(drones.values()));
    }

    public boolean isEmpty() {
        return drones.isEmpty();
    }

    /** Records a drone, or refreshes where a known one stands. */
    public void put(SavedDrone drone) {
        SavedDrone previous = drones.get(drone.droneId());
        if (previous != null &&
            previous.systemId() == drone.systemId() &&
            previous.northM() == drone.northM() &&
            previous.eastM() == drone.eastM() &&
            previous.downM() == drone.downM()) {
            return;
        }
        drones.put(drone.droneId(), drone);
        setDirty();
    }

    /** Forgets a drone that is no longer part of this world. */
    public void remove(String droneId) {
        if (drones.remove(droneId) != null) {
            setDirty();
        }
    }

    public void clear() {
        if (!drones.isEmpty()) {
            drones.clear();
            setDirty();
        }
    }
}
