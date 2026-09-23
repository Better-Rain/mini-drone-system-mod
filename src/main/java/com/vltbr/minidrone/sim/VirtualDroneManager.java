package com.vltbr.minidrone.sim;

import com.vltbr.minidrone.MiniDroneMod;
import com.vltbr.minidrone.world.DronePlacement;
import com.vltbr.minidrone.world.DroneWorldController;
import com.vltbr.minidrone.world.NedWorldTransform;
import com.vltbr.minidrone.world.TrainingFieldController;
import com.vltbr.minidrone.world.VirtualFleetSavedData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class VirtualDroneManager implements VirtualFlightController {
    private final MinecraftServer server;

    /**
     * The drones this world is flying.
     *
     * <p>One for now, created through the fleet so its identity comes from the fleet's rules
     * rather than being written here - it is exactly the {@code minecraft_drone_01} of a
     * single-drone world, which is what every existing setup and every backend expects. The
     * fleet is the place the rest of the multi-drone work hangs off; {@link #primaryDrone}
     * is the same object as {@code fleet.primary()}, so single-drone paths read as they
     * always did while the loops below are written to walk every drone.
     */
    private final VirtualDroneFleet fleet = new VirtualDroneFleet();

    private final VirtualDroneState primaryDrone;
    private final AtomicReference<VirtualDroneSnapshot> publishedState = new AtomicReference<>();
    private final DroneWorldController worldController;
    /** The drones this world has, saved with it. */
    private final VirtualFleetSavedData fleetData;
    /**
     * How many more ticks the world is scanned for saved drones. The chunks they stand in are
     * loaded as the world comes up, so one scan on the first tick can miss them.
     */
    private static final int WORLD_FLEET_ADOPTION_TICKS = 100;
    private int worldFleetAdoptionTicksRemaining = WORLD_FLEET_ADOPTION_TICKS;

    public VirtualDroneManager(MinecraftServer server) {
        this(server, new TrainingFieldController(server));
    }

    public VirtualDroneManager(MinecraftServer server, TrainingFieldController fieldController) {
        this.server = server;
        worldController = new DroneWorldController(server, fieldController);
        fleetData = server.overworld().getDataStorage()
            .computeIfAbsent(VirtualFleetSavedData.factory(), VirtualFleetSavedData.DATA_ID);
        primaryDrone = fleet.create(VehicleModel.fromProperties(System.getProperties()));
        // The airframe can be described from the launcher as well as tuned in game;
        // a malformed value leaves the default in place.
        // A world is driving this vehicle, so the world - not the NED plane - is where the
        // ground is.
        primaryDrone.setWorldOwnsTheGround(true);
        publish();
    }

    /**
     * One control step for the whole fleet.
     *
     * <p>Every drone is stepped against the world, in the fleet's creation order, and each
     * one adopts the position the world allowed. Single-drone worlds see exactly what they
     * always did; with more than one, no vehicle can be skipped just because another one
     * was created first.
     */
    public void tick() {
        if (worldFleetAdoptionTicksRemaining > 0) {
            worldFleetAdoptionTicksRemaining--;
            adoptWorldFleet();
        }
        for (VirtualDroneState drone : fleet.all()) {
            tickDrone(drone);
            // Where it is now is what the next load has to put back, and the world may save
            // at any moment after this tick.
            if (worldFleetAdoptionTicksRemaining == 0) {
                rememberDrone(drone);
            }
        }
        publish();
    }

    /**
     * Rebuilds the fleet from the drones the world already has.
     *
     * <p>Placed aircraft are saved with the world, so after a restart the fleet has to adopt
     * them instead of starting over: the ids and MAVLink system ids come back the same, and
     * the operator does not have to place every drone again. A world with no saved drone (a
     * fresh one) keeps the single drone the constructor created, which is what a one-drone
     * world has always been.
     *
     * <p>Run over the first few seconds rather than once: the chunks a drone stands in are
     * loaded as the world comes up, so a single scan on the first tick can miss a drone that
     * is saved and simply not there yet.
     */
    private void adoptWorldFleet() {
        List<VirtualFleetSavedData.SavedDrone> saved = fleetData.drones();
        if (saved.isEmpty()) {
            // A world that has never had a drone placed in it keeps the single drone the
            // constructor created, which is what a one-drone world has always been.
            return;
        }
        worldController.adoptExistingDrones();
        for (VirtualFleetSavedData.SavedDrone entry : saved) {
            final String droneId = entry.droneId();
            VirtualDroneState drone = fleet.byId(droneId);
            if (drone == null) {
                drone = fleet.restore(
                    droneId,
                    entry.systemId(),
                    VehicleModel.fromProperties(System.getProperties()));
                if (drone == null) {
                    MiniDroneMod.LOGGER.warn(
                        "This world has more drones than the fleet can hold; {} stays unflown",
                        droneId);
                    continue;
                }
                drone.setWorldOwnsTheGround(true);
                MiniDroneMod.LOGGER.info("Adopted the saved drone {}", droneId);
            }
            // Where it stood when the world was saved, in the frame the field origin defines.
            // A saved spot can be inside the terrain (an aircraft saved mid-fall); the world
            // controller lifts the entity out of it when it places it, and the simulation
            // adopts the world's position on the next tick.
            drone.setLocalPosition(entry.northM(), entry.eastM(), entry.downM());
        }
    }

    /** Records where a drone stands, so the next load puts it back there. */
    private void rememberDrone(VirtualDroneState drone) {
        VirtualDroneSnapshot snapshot = drone.snapshot();
        fleetData.put(new VirtualFleetSavedData.SavedDrone(
            snapshot.droneId(),
            snapshot.systemId(),
            snapshot.northM(),
            snapshot.eastM(),
            snapshot.downM()
        ));
    }

    private void tickDrone(VirtualDroneState drone) {
        // The world is authoritative for position: adopt where the entity actually is
        // (a player's shove, a piston, anything) before the plant integrates this tick.
        DroneWorldController.StepResult current =
            worldController.entityPosition(drone.snapshot().droneId());
        if (current != null) {
            drone.adoptExternalPosition(
                current.northM(), current.eastM(), current.downM(),
                false, false, false, false, current.supported());
        }
        drone.tick();
        DroneWorldController.StepResult step = worldController.simulateStep(drone.snapshot());
        if (step != null) {
            drone.adoptExternalPosition(
                step.northM(),
                step.eastM(),
                step.downM(),
                step.blockedHorizontally(),
                step.blockedVertically(),
                step.blockedNorth(),
                step.blockedEast(),
                step.supported()
            );
            if (step.impact() == ImpactModel.Outcome.CRASH) {
                // A collision in this world is ordinary physics: the vehicle stops, slides, and
                // nudges whatever it hit, and it keeps flying. The impact scale can still label
                // a hard contact, and this branch is where a future damage source would land -
                // nothing in the world reports CRASH today.
                MiniDroneMod.LOGGER.warn(
                    "Virtual drone {} took a hard contact at NED ({}, {}, {})",
                    drone.snapshot().droneId(),
                    String.format(java.util.Locale.ROOT, "%.2f", step.northM()),
                    String.format(java.util.Locale.ROOT, "%.2f", step.eastM()),
                    String.format(java.util.Locale.ROOT, "%.2f", step.downM()));
            }
        }
    }

    public VirtualDroneState primaryDrone() {
        return primaryDrone;
    }

    /**
     * The drone a MAVLink system id names, seen as a controller.
     *
     * <p>Null means this world does not fly that system id. Everything else about the
     * returned controller is per drone: a command sent to system 55 cannot touch system 54.
     */
    @Override
    public VirtualFlightController vehicleForSystemId(int systemId) {
        VirtualDroneState drone = fleet.bySystemId(systemId);
        return drone == null ? null : new VirtualDroneHandle(drone, this::publish);
    }

    @Override
    public VirtualDroneSnapshot snapshot() {
        return publishedState.get();
    }

    @Override
    public boolean setMode(int customMode) {
        boolean accepted = primaryDrone.setMode(customMode);
        publish();
        return accepted;
    }

    @Override
    public boolean setArmed(boolean armed) {
        boolean accepted = primaryDrone.setArmed(armed);
        publish();
        return accepted;
    }

    @Override
    public boolean takeoff(double altitudeM) {
        boolean accepted = primaryDrone.takeoff(altitudeM);
        publish();
        return accepted;
    }

    @Override
    public boolean land() {
        boolean accepted = primaryDrone.land();
        publish();
        return accepted;
    }

    public boolean setPositionTarget(double northM, double eastM, double downM) {
        return setLocalSetpoint(LocalSetpoint.positionOnly(northM, eastM, downM));
    }

    @Override
    public boolean setLocalSetpoint(LocalSetpoint setpoint) {
        boolean accepted = primaryDrone.setLocalSetpoint(setpoint);
        publish();
        return accepted;
    }

    /** The drones this world is flying, in creation order. */
    public VirtualDroneFleet fleet() {
        return fleet;
    }

    /** The drone a command names, or null when no drone has that id. */
    public VirtualDroneState droneById(String droneId) {
        return fleet.byId(droneId);
    }

    /**
     * Returns one drone to the field origin: the explicit reset.
     *
     * <p>Per drone, because with a fleet "collect" has to mean the vehicle the operator
     * pointed at. That is the whole reason the entity carries its drone id.
     */
    public OriginResetResult resetFlightOrigin(ServerPlayer player, String droneId) {
        if (player.serverLevel() != server.overworld()) {
            return OriginResetResult.WRONG_DIMENSION;
        }
        VirtualDroneState drone = fleet.byId(droneId);
        if (drone == null) {
            return OriginResetResult.UNKNOWN_DRONE;
        }
        if (!drone.resetLocalPosition()) {
            return OriginResetResult.DRONE_ACTIVE;
        }
        // Resetting the vehicle is also how a crash is cleared: the latch exists so the
        // monitoring side stops accepting commands after an impact, and the operator's
        // explicit reset is the act that says "this vehicle is serviceable again".
        drone.clearSafetyLatch();
        publish();
        worldController.resetOrigin(player, drone.snapshot());
        return OriginResetResult.RESET;
    }

    /** The drone the single-drone command paths mean: the first one created. */
    public OriginResetResult resetFlightOrigin(ServerPlayer player) {
        return resetFlightOrigin(player, primaryDrone.snapshot().droneId());
    }

    public NedWorldTransform flightOrigin() {
        return worldController.origin();
    }

    /**
     * Adds a drone at a world point, and says which one it became.
     *
     * <p>This is how a second aircraft appears: the operator points at a block with the
     * placement item, the fleet hands out the next id and MAVLink system id, and the new
     * vehicle starts its life standing where it was put. The field and its origin stay
     * where they are, so the local position of the new drone is its offset from the
     * origin - exactly the manual-placement rule the single-drone path used.
     */
    public PlacementOutcome placeNewDrone(double worldX, double worldY, double worldZ) {
        NedWorldTransform origin = worldController.origin();
        if (origin == null) {
            return new PlacementOutcome(PlacementResult.NO_FIELD, "");
        }
        VirtualDroneState drone = fleet.create(VehicleModel.fromProperties(System.getProperties()));
        if (drone == null) {
            return new PlacementOutcome(PlacementResult.FLEET_FULL, "");
        }
        // A world is driving this vehicle, so the world - not the NED plane - is where the
        // ground is. The same applies to every drone the fleet hands out.
        drone.setWorldOwnsTheGround(true);
        double[] ned = DronePlacement.nedOffsetFor(origin, worldX, worldY, worldZ);
        drone.setLocalPosition(ned[0], ned[1], ned[2]);
        // The world now has one more drone, and the world is what saves it.
        rememberDrone(drone);
        publish();
        String droneId = drone.snapshot().droneId();
        MiniDroneMod.LOGGER.info(
            "Placed {} at world ({}, {}, {})",
            droneId,
            String.format(java.util.Locale.ROOT, "%.2f", worldX),
            String.format(java.util.Locale.ROOT, "%.2f", worldY),
            String.format(java.util.Locale.ROOT, "%.2f", worldZ));
        return new PlacementOutcome(PlacementResult.PLACED, droneId);
    }

    /** What a placement attempt did, so the command and the item can explain it. */
    public enum PlacementResult {
        PLACED,
        /** No field defines an origin, so there is nothing to be relative to. */
        NO_FIELD,
        /** The fleet is at its ceiling; the operator has to remove one first. */
        FLEET_FULL
    }

    /** The outcome of a placement, including which drone it created. */
    public record PlacementOutcome(PlacementResult result, String droneId) {
    }

    public MinecraftServer server() {
        return server;
    }

    public void close() {
        worldController.close();
    }

    private void publish() {
        publishedState.set(primaryDrone.snapshot());
    }

    public enum OriginResetResult {
        RESET,
        DRONE_ACTIVE,
        WRONG_DIMENSION,
        /** The drone named by the operator is not in this world's fleet. */
        UNKNOWN_DRONE
    }
}
