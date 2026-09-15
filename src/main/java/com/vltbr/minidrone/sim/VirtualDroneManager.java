package com.vltbr.minidrone.sim;

import com.vltbr.minidrone.world.DroneWorldController;
import com.vltbr.minidrone.world.NedWorldTransform;
import com.vltbr.minidrone.world.TrainingArenaController;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.atomic.AtomicReference;

public final class VirtualDroneManager implements VirtualFlightController {
    private final MinecraftServer server;
    private final VirtualDroneState primaryDrone = new VirtualDroneState(54, 1, "minecraft_drone_01");
    private final AtomicReference<VirtualDroneSnapshot> publishedState = new AtomicReference<>();
    private final DroneWorldController worldController;

    public VirtualDroneManager(MinecraftServer server) {
        this(server, new TrainingArenaController(server));
    }

    public VirtualDroneManager(MinecraftServer server, TrainingArenaController arenaController) {
        this.server = server;
        worldController = new DroneWorldController(server, arenaController);
        publish();
    }

    public void tick() {
        primaryDrone.tick();
        publish();
        worldController.tick(snapshot());
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

    public OriginResetResult resetFlightOrigin(ServerPlayer player) {
        if (player.serverLevel() != server.overworld()) {
            return OriginResetResult.WRONG_DIMENSION;
        }
        if (!primaryDrone.resetLocalPosition()) {
            return OriginResetResult.DRONE_ACTIVE;
        }
        publish();
        worldController.resetOrigin(player, snapshot());
        return OriginResetResult.RESET;
    }

    public NedWorldTransform flightOrigin() {
        return worldController.origin();
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
