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

    /**
     * The MAVLink system id the single-drone system has always advertised for
     * {@code minecraft_drone_01}.
     *
     * <p>It is part of the published contract, not a free choice: the backend's
     * virtual aircraft link is configured with {@code 54 / 1}, and it discovers
     * aircraft by system id, so a fleet that handed the first drone a different id
     * would leave the backend waiting forever for a vehicle that never appears
     * (observed live: the link stayed {@code awaiting_peer} while 2487 telemetry
     * packets were rejected). Later drones continue from here.
     */
    public static final int FIRST_SYSTEM_ID = 54;

    /** A ceiling, so a stuck key or a loop cannot spawn aircraft without limit. */
    public static final int MAX_DRONES = 8;

    private final Map<String, VirtualDroneState> drones = new LinkedHashMap<>();
    private int nextIndex = 1;
    private int nextSystemId = FIRST_SYSTEM_ID;

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

    /**
     * Takes a drone the world already has back into the fleet, with the identity it was
     * saved with.
     *
     * <p>Placed aircraft are saved with their world, so a fleet rebuilt after a restart is
     * not a fresh fleet: the ids and MAVLink system ids have to come back the same, or every
     * drone the operator has ever commanded appears under a new name. Allocation continues
     * past everything restored, so a drone placed after the restart cannot collide with one
     * that was already there.
     */
    public VirtualDroneState restore(String droneId, int systemId, VehicleModel model) {
        if (droneId == null || droneId.isBlank() || drones.containsKey(droneId)) {
            return null;
        }
        if (drones.size() >= MAX_DRONES) {
            return null;
        }
        int restoredSystemId = systemId > 0 ? systemId : FIRST_SYSTEM_ID + drones.size();
        VirtualDroneState drone =
            new VirtualDroneState(restoredSystemId, AUTOPILOT_COMPONENT_ID, droneId);
        if (model != null) {
            drone.setVehicleModel(model);
        }
        drones.put(droneId, drone);
        nextSystemId = Math.max(nextSystemId, restoredSystemId + 1);
        nextIndex = Math.max(nextIndex, indexFromId(droneId) + 1);
        return drone;
    }

    /** The slot number an id names, or 0 when it is not one of this fleet's ids. */
    static int indexFromId(String droneId) {
        if (droneId == null) {
            return 0;
        }
        int start = droneId.length();
        while (start > 0 && Character.isDigit(droneId.charAt(start - 1))) {
            start--;
        }
        if (start == droneId.length()) {
            return 0;
        }
        try {
            return Integer.parseInt(droneId.substring(start));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    public VirtualDroneState byId(String droneId) {
        return droneId == null ? null : drones.get(droneId);
    }

    /**
     * The drone a MAVLink system id belongs to, or null when none does.
     *
     * <p>System id is the only identity a frame carries, so this is the lookup that makes a
     * fleet addressable from outside: a command for system 55 has to reach the second drone
     * and nothing else.
     */
    public VirtualDroneState bySystemId(int systemId) {
        for (VirtualDroneState drone : drones.values()) {
            if (drone.snapshot().systemId() == systemId) {
                return drone;
            }
        }
        return null;
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
