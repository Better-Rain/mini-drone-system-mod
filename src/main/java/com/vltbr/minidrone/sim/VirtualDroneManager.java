package com.vltbr.minidrone.sim;

import com.vltbr.minidrone.MiniDroneMod;
import com.vltbr.minidrone.world.DronePlacement;
import com.vltbr.minidrone.world.DroneWorldController;
import com.vltbr.minidrone.world.NedWorldTransform;
import com.vltbr.minidrone.world.TrainingFieldController;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

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

    public VirtualDroneManager(MinecraftServer server) {
        this(server, new TrainingFieldController(server));
    }

    public VirtualDroneManager(MinecraftServer server, TrainingFieldController fieldController) {
        this.server = server;
        worldController = new DroneWorldController(server, fieldController);
        primaryDrone = fleet.create(VehicleModel.fromProperties(System.getProperties()));
        // The airframe can be described from the launcher as well as tuned in game;
        // a malformed value leaves the default in place.
        // A world is driving this vehicle, so the world - not the NED plane - is where the
        // ground is.
        primaryDrone.setWorldOwnsTheGround(true);
        publish();
    }

    /**
     * One control step: the plant integrates what the setpoints asked for, the world
     * resolves that against the blocks, and the world's answer becomes the vehicle's
     * position before anything is published. Publishing first and moving afterwards -
     * which is what this used to do - put a wish into the telemetry and the truth in
     * the entity.
     */
    public void tick() {
        // The world is authoritative for position: adopt where the entity actually is
        // (a player's shove, a piston, anything) before the plant integrates this tick.
        DroneWorldController.StepResult current = worldController.entityPosition();
        if (current != null) {
            primaryDrone.adoptExternalPosition(
                current.northM(), current.eastM(), current.downM(),
                false, false, false, false, current.supported());
        }
        primaryDrone.tick();
        DroneWorldController.StepResult step = worldController.simulateStep(primaryDrone.snapshot());
        if (step != null) {
            primaryDrone.adoptExternalPosition(
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
                // Stopping dead is not what a multirotor does when it meets a wall: the
                // flight is over, so it falls from where it hit and the event is latched
                // until the operator resets it.
                MiniDroneMod.LOGGER.warn(
                    "Virtual drone crashed into the world at NED ({}, {}, {})",
                    String.format(java.util.Locale.ROOT, "%.2f", step.northM()),
                    String.format(java.util.Locale.ROOT, "%.2f", step.eastM()),
                    String.format(java.util.Locale.ROOT, "%.2f", step.downM()));
                primaryDrone.crash();
            }
        }
        publish();
    }

    public VirtualDroneState primaryDrone() {
        return primaryDrone;
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

    public OriginResetResult resetFlightOrigin(ServerPlayer player) {
        if (player.serverLevel() != server.overworld()) {
            return OriginResetResult.WRONG_DIMENSION;
        }
        if (!primaryDrone.resetLocalPosition()) {
            return OriginResetResult.DRONE_ACTIVE;
        }
        // Resetting the vehicle is also how a crash is cleared: the latch exists so the
        // monitoring side stops accepting commands after an impact, and the operator's
        // explicit reset is the act that says "this vehicle is serviceable again".
        primaryDrone.clearSafetyLatch();
        publish();
        worldController.resetOrigin(player, snapshot());
        return OriginResetResult.RESET;
    }

    public NedWorldTransform flightOrigin() {
        return worldController.origin();
    }

    /**
     * Carries the drone to a world point, keeping the field and its origin where they
     * are.
     *
     * <p>This is the manual placement path: the operator says "the vehicle is here",
     * so its local NED position becomes the offset from the origin. It is deliberately
     * the opposite of changing the field, where the origin moves and the vehicle
     * stays put.
     */
    public PlacementResult placeAt(double worldX, double worldY, double worldZ) {
        NedWorldTransform origin = worldController.origin();
        if (origin == null) {
            return PlacementResult.NO_FIELD;
        }
        double[] ned = DronePlacement.nedOffsetFor(origin, worldX, worldY, worldZ);
        if (!primaryDrone.setLocalPosition(ned[0], ned[1], ned[2])) {
            return PlacementResult.DRONE_ARMED;
        }
        publish();
        return PlacementResult.PLACED;
    }

    /** What a placement attempt did, so the command and the item can explain it. */
    public enum PlacementResult {
        PLACED,
        /** No field defines an origin, so there is nothing to be relative to. */
        NO_FIELD,
        /** An armed vehicle is not carried around. */
        DRONE_ARMED
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
        WRONG_DIMENSION
    }
}
