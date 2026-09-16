package com.vltbr.minidrone.sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The drones this world is flying, indexed by id.
 *
 * <p>Everything before this held exactly one vehicle - {@code primaryDrone} - and the
 * vehicle's own state was already parameterised per drone: {@link VirtualDroneState} takes
 * a system id, a component id and an id string. What was missing was somewhere to keep
 * more than one of them, and a rule for handing out those three.
 *
 * <p>The identities matter outside this class, which is why the rules are written down:
 *
 * <ul>
 *   <li>ids are {@code minecraft_drone_01}, {@code _02}, ... The first one is exactly what
 *       a single-drone world has always advertised, so nothing downstream changes;</li>
 *   <li>the MAVLink system id is allocated per drone and <em>not</em> reused when a drone is
 *       removed. The backend discovers aircraft by system id in its auto-bind mode, so
 *       recycling one would hand a new vehicle an identity the monitoring side still has
 *       state for;</li>
 *   <li>creation order is kept, because the beacon and the telemetry walk the fleet in
 *       order and an order that changed under them would look like drones swapping
 *       places.</li>
 * </ul>
 */
public final class VirtualDroneFleet {
    /** The id the single-drone system has always used. */
    public static final String DEFAULT_DRONE_ID = "minecraft_drone_01";

    /** MAVLink component id 1 is the autopilot; every drone here is one. */
    public static final int AUTOPILOT_COMPONENT_ID = 1;

    /** A ceiling, so a stuck key or a loop cannot spawn aircraft without limit. */
    public static final int MAX_DRONES = 8;

    private final Map<String, VirtualDroneState> drones = new LinkedHashMap<>();
    private int nextIndex = 1;
    private int nextSystemId = 1;

    /** The id a given slot number uses, starting at one. */
    public static String idFor(int index) {
        return String.format("minecraft_drone_%02d", index);
    }

    /**
     * Adds a drone, or returns null when the fleet is full.
     *
     * <p>The model is passed in rather than read here so a caller can tune one vehicle
     * without touching the others.
     */
    public VirtualDroneState create(VehicleModel model) {
        if (drones.size() >= MAX_DRONES) {
            return null;
        }
        String id = idFor(nextIndex);
        int systemId = nextSystemId;
        nextIndex++;
        nextSystemId++;
        VirtualDroneState drone = new VirtualDroneState(systemId, AUTOPILOT_COMPONENT_ID, id);
        if (model != null) {
            drone.setVehicleModel(model);
        }
        drones.put(id, drone);
        return drone;
    }

    public VirtualDroneState byId(String droneId) {
        return droneId == null ? null : drones.get(droneId);
    }

    /** The drone single-drone paths mean: the first one created, or null if there is none. */
    public VirtualDroneState primary() {
        for (VirtualDroneState drone : drones.values()) {
            return drone;
        }
        return null;
    }

    /** Removes a drone, returning whether there was one. Its slot is not handed out again. */
    public boolean remove(String droneId) {
        return droneId != null && drones.remove(droneId) != null;
    }

    /** Every drone, in creation order. */
    public List<VirtualDroneState> all() {
        return new ArrayList<>(drones.values());
    }

    public int size() {
        return drones.size();
    }

    public boolean isEmpty() {
        return drones.isEmpty();
    }

    public boolean isFull() {
        return drones.size() >= MAX_DRONES;
    }

    /** Ids in creation order, for commands and logs. */
    public List<String> ids() {
        return new ArrayList<>(drones.keySet());
    }
}
