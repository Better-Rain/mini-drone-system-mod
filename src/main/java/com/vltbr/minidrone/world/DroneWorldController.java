package com.vltbr.minidrone.world;

import com.vltbr.minidrone.MiniDroneMod;
import com.vltbr.minidrone.entity.DroneEntity;
import com.vltbr.minidrone.entity.ModEntityTypes;
import com.vltbr.minidrone.sim.VirtualDroneSnapshot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

public final class DroneWorldController implements AutoCloseable {
    private static final double HOME_DISTANCE_FROM_PLAYER = 2.0;
    private static final double HOME_HEIGHT_OFFSET = 0.1;

    private final MinecraftServer server;
    private final TrainingFieldController fieldController;
    private DroneEntity entity;
    private NedWorldTransform transform;

    public DroneWorldController(MinecraftServer server) {
        this(server, new TrainingFieldController(server));
    }

    public DroneWorldController(MinecraftServer server, TrainingFieldController fieldController) {
        this.server = server;
        this.fieldController = fieldController;
    }

    public void tick(VirtualDroneSnapshot snapshot) {
        if (entity == null || entity.isRemoved()) {
            spawnForFirstPlayer(snapshot);
        }
        if (entity != null && transform != null) {
            entity.applySnapshot(snapshot, transform.toWorldPose(snapshot));
        }
    }

    /**
     * The virtual world origin: the training field's origin when one is defined
     * (hand-made, or the generated arena, or an instance default), otherwise two
     * blocks in front of the player.
     *
     * <p>The main project has to know where that origin is relative to the field,
     * which is exactly what the field definition carries: {@code field_size_m} and,
     * when the origin is not the centre, {@code field_center_m}. Without a field
     * there is nothing to line up with and the origin stays a convenience for free
     * flight.
     */
    private NedWorldTransform chooseOrigin(ServerPlayer player) {
        NedWorldTransform origin = fieldController == null ? null : fieldController.originTransform();
        return origin != null ? origin : transformInFrontOf(player);
    }

    /** Which rule produced the current origin, for the log line. */
    public String originRule() {
        if (fieldController == null || !fieldController.hasField()) {
            return "in front of the player (no field defined)";
        }
        return fieldController.summary();
    }

    private void spawnForFirstPlayer(VirtualDroneSnapshot snapshot) {
        ServerLevel level = server.overworld();
        ServerPlayer player = level.getPlayers(candidate -> !candidate.isSpectator())
            .stream()
            .findFirst()
            .orElse(null);
        if (player == null) {
            return;
        }

        transform = chooseOrigin(player);
        DroneEntity spawned = new DroneEntity(ModEntityTypes.DRONE, level);
        spawned.applySnapshot(snapshot, transform.toWorldPose(snapshot));
        if (!level.addFreshEntity(spawned)) {
            transform = null;
            MiniDroneMod.LOGGER.warn("Unable to spawn the virtual drone entity");
            return;
        }
        entity = spawned;
        logOrigin("Spawned", snapshot);
    }

    public void resetOrigin(ServerPlayer player, VirtualDroneSnapshot snapshot) {
        transform = chooseOrigin(player);
        if (entity == null || entity.isRemoved()) {
            spawnForFirstPlayer(snapshot);
            return;
        }
        entity.applySnapshot(snapshot, transform.toWorldPose(snapshot));
        logOrigin("Reset origin for", snapshot);
    }

    public NedWorldTransform origin() {
        return transform;
    }

    private NedWorldTransform transformInFrontOf(ServerPlayer player) {
        Vec3 look = player.getLookAngle();
        Vec3 horizontalLook = new Vec3(look.x, 0.0, look.z);
        if (horizontalLook.lengthSqr() < 1.0E-6) {
            horizontalLook = new Vec3(0.0, 0.0, 1.0);
        } else {
            horizontalLook = horizontalLook.normalize();
        }
        double homeX = player.getX() + horizontalLook.x * HOME_DISTANCE_FROM_PLAYER;
        double homeY = player.getY() + HOME_HEIGHT_OFFSET;
        double homeZ = player.getZ() + horizontalLook.z * HOME_DISTANCE_FROM_PLAYER;

        return new NedWorldTransform(homeX, homeY, homeZ);
    }

    private void logOrigin(String action, VirtualDroneSnapshot snapshot) {
        MiniDroneMod.LOGGER.info(
            "{} {} at Minecraft home [{}, {}, {}] ({})",
            action,
            snapshot.droneId(),
            String.format(Locale.ROOT, "%.2f", transform.originX()),
            String.format(Locale.ROOT, "%.2f", transform.originY()),
            String.format(Locale.ROOT, "%.2f", transform.originZ()),
            originRule()
        );
    }

    @Override
    public void close() {
        if (entity != null && !entity.isRemoved()) {
            entity.discard();
        }
        entity = null;
        transform = null;
    }
}
