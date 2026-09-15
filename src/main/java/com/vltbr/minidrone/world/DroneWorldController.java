package com.vltbr.minidrone.world;

import com.vltbr.minidrone.MiniDroneMod;
import com.vltbr.minidrone.entity.DroneEntity;
import com.vltbr.minidrone.entity.ModEntityTypes;
import com.vltbr.minidrone.sim.ImpactModel;
import com.vltbr.minidrone.sim.PhysicsStep;
import com.vltbr.minidrone.sim.VirtualDroneSnapshot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

public final class DroneWorldController implements AutoCloseable {
    private static final double HOME_DISTANCE_FROM_PLAYER = 2.0;
    private static final double HOME_HEIGHT_OFFSET = 0.1;
    /** Half the entity's bounding box height: NED measures to the feet, the entity to its centre. */
    static final double HALF_HEIGHT_M = 0.175;
    static final double HALF_WIDTH_M = 0.45;
    /** A step bigger than this is a carry (spawn, hand placement), not a flight. */
    static final double MAX_PHYSICS_STEP_M = 1.5;

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
        simulateStep(snapshot);
    }

    /**
     * Moves the vehicle by what the flight controller asked for and reports where the
     * world let it go.
     *
     * <p>The plant still integrates its own motion - that is what the setpoints mean -
     * but the world has the last word: collision with block shapes is resolved by
     * {@link PhysicsStep}, with this level as the authority on what is solid, so the
     * vehicle stops at walls, slides along them and rests on whatever it lands on. The
     * position the world allowed is then written back into the simulation, so the
     * telemetry describes the vehicle that is actually there rather than the one the
     * controller wished for.
     *
     * <p>A step larger than {@link #MAX_PHYSICS_STEP_M} is a carry - spawning, hand
     * placement, or the first frame after the operator moved the vehicle - and is
     * applied directly, because that is what carrying something means.
     */
    public StepResult simulateStep(VirtualDroneSnapshot snapshot) {
        if (entity == null || entity.isRemoved()) {
            spawnForFirstPlayer(snapshot);
        }
        if (entity == null || transform == null) {
            return null;
        }

        WorldPose wanted = transform.toWorldPose(snapshot);
        // NED measures to the vehicle feet; the entity position is its centre.
        double wantedCentreY = wanted.y() + HALF_HEIGHT_M;
        Vec3 current = entity.position();
        double dx = wanted.x() - current.x;
        double dy = wantedCentreY - current.y;
        double dz = wanted.z() - current.z;

        PhysicsStep.Result resolved;
        if (Math.sqrt(dx * dx + dy * dy + dz * dz) > MAX_PHYSICS_STEP_M) {
            resolved = new PhysicsStep.Result(
                wanted.x(), wantedCentreY, wanted.z(), false, false, false);
        } else {
            resolved = PhysicsStep.resolve(
                current.x, current.y, current.z,
                dx, dy, dz,
                HALF_WIDTH_M, HALF_HEIGHT_M,
                this::isBoxFree
            );
        }

        entity.setPos(resolved.x(), resolved.y(), resolved.z());
        entity.setYRot(wanted.yawDegrees());
        entity.setXRot(wanted.pitchDegrees());
        entity.setDeltaMovement(new Vec3(
            -snapshot.velocityEastMps(),
            -snapshot.velocityDownMps(),
            -snapshot.velocityNorthMps()
        ));

        double[] ned = DronePlacement.nedOffsetFor(
            transform, resolved.x(), resolved.y() - HALF_HEIGHT_M, resolved.z());
        boolean supported = !isBoxFree(
            PhysicsStep.boxAt(resolved.x(), resolved.y() - SUPPORT_PROBE_M, resolved.z(),
                HALF_WIDTH_M, HALF_HEIGHT_M));

        // What the impact did, not just that there was one: hitting a wall at speed
        // destroys the flight, while touching down at the controlled descent rate does
        // not. A blocked vertical axis only counts as an impact when it stopped a
        // descent - hitting a ceiling on the way up is not a crash.
        double horizontalSpeed = Math.hypot(
            snapshot.velocityNorthMps(), snapshot.velocityEastMps());
        double descentSpeed = Math.max(0.0, snapshot.velocityDownMps());
        ImpactModel.Outcome impact = ImpactModel.Outcome.NONE;
        if (resolved.blockedHorizontally()) {
            impact = ImpactModel.assess(horizontalSpeed, 0.0);
        }
        if (resolved.blockedVertically() && descentSpeed > 0.0) {
            ImpactModel.Outcome vertical = ImpactModel.assess(0.0, descentSpeed);
            if (vertical == ImpactModel.Outcome.CRASH || impact == ImpactModel.Outcome.NONE) {
                impact = vertical == ImpactModel.Outcome.CRASH ? vertical : impact;
                if (vertical == ImpactModel.Outcome.CONTACT && impact == ImpactModel.Outcome.NONE) {
                    impact = vertical;
                }
            }
        }

        return new StepResult(
            ned[0], ned[1], ned[2],
            resolved.blockedHorizontally(), resolved.blockedVertically(),
            resolved.blockedZ(), resolved.blockedX(),
            supported, impact);
    }

    /** Where the world let the vehicle go, what stopped it, and what that meant. */
    public record StepResult(
        double northM, double eastM, double downM,
        boolean blockedHorizontally, boolean blockedVertically,
        boolean blockedNorth, boolean blockedEast,
        boolean supported, ImpactModel.Outcome impact
    ) {
    }

    /** How far below the feet the support probe looks for something solid. */
    private static final double SUPPORT_PROBE_M = 0.05;

    /**
     * Whether the vehicle fits at that box.
     *
     * <p>The level answers with the shapes of the blocks that overlap it, which is the
     * same list the game uses for everything else - so "solid" means whatever this
     * world says it means, slabs, stairs and fences included.
     */
    private boolean isBoxFree(double[] box) {
        AABB candidate = new AABB(box[0], box[1], box[2], box[3], box[4], box[5]);
        return !server.overworld().getBlockCollisions(entity, candidate).iterator().hasNext();
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
