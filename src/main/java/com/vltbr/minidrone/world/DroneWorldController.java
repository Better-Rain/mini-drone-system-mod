package com.vltbr.minidrone.world;

import com.vltbr.minidrone.MiniDroneMod;
import com.vltbr.minidrone.entity.DroneEntity;
import com.vltbr.minidrone.entity.ModEntityTypes;
import com.vltbr.minidrone.sim.ImpactModel;
import com.vltbr.minidrone.sim.PhysicsStep;
import com.vltbr.minidrone.sim.VirtualDroneSnapshot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class DroneWorldController implements AutoCloseable {
    private static final double HOME_DISTANCE_FROM_PLAYER = 2.0;
    private static final double HOME_HEIGHT_OFFSET = 0.1;
    /** Half the entity's bounding box height: NED measures to the feet, the entity to its centre. */
    static final double HALF_HEIGHT_M = 0.175;
    static final double HALF_WIDTH_M = 0.45;
    /** A step bigger than this is swept in pieces of this size, not carried through the world. */
    static final double MAX_PHYSICS_STEP_M = 1.5;
    /** How many pieces one step may be swept in before it counts as a carry (a spawn). */
    static final int MAX_PHYSICS_SUBSTEPS = 64;
    /** Keeps the box clear of the world's bottom face, which is solid. */
    private static final double WORLD_FLOOR_MARGIN_M = 0.001;
    /** How far a restored drone may be lifted to find a spot it fits in. */
    private static final double MAX_FIT_LIFT_M = 64.0;
    /** The step that search takes, in metres. */
    private static final double FIT_STEP_M = 0.25;

    private final MinecraftServer server;
    private final TrainingFieldController fieldController;

    /**
     * The entity that shows each drone, indexed by drone id.
     *
     * <p>One entity was all a single-drone world needed. With a fleet, "the entity" is not
     * an answer to anything: a physics step, a position read-back, and a right-click all
     * have to mean <em>that</em> vehicle. The map is insertion-ordered so the world spawns
     * and reports aircraft in the order the fleet created them, which is the order the
     * beacon and the operator's list use.
     */
    private final Map<String, DroneEntity> entities = new LinkedHashMap<>();

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
     * <p>A step longer than {@link #MAX_PHYSICS_STEP_M} is swept in pieces, not carried: a
     * fast fall is still a fall and has to meet the floor. Only a step longer than
     * {@link #MAX_PHYSICS_SUBSTEPS} pieces - a spawn or a hand placement - is applied
     * directly, because that really is carrying something.
     */
    public StepResult simulateStep(VirtualDroneSnapshot snapshot) {
        DroneEntity entity = ensureEntity(snapshot);
        if (entity == null || transform == null) {
            return null;
        }

        WorldPose wanted = transform.toWorldPose(snapshot);

        // Work from the entity's own bounding box rather than from an assumption about
        // which corner of it the entity's position is. Minecraft puts an entity's
        // position at the *bottom* of its box, and the code here used to place the centre
        // of the box at the wanted height while the renderer lifted the model another
        // 0.28 blocks: the vehicle visibly hovered about 45 cm above whatever it had
        // landed on. Deriving the box from the entity and moving by centre deltas cannot
        // go wrong that way.
        AABB box = entity.getBoundingBox();
        double halfWidth = Math.max(0.05, (box.maxX - box.minX) / 2.0);
        double halfHeight = Math.max(0.05, (box.maxY - box.minY) / 2.0);
        double centreX = (box.minX + box.maxX) / 2.0;
        double centreY = (box.minY + box.maxY) / 2.0;
        double centreZ = (box.minZ + box.maxZ) / 2.0;
        // The NED origin sits at the vehicle's feet, so the box bottom is the target.
        double wantedCentreY = wanted.y() + halfHeight;
        double dx = wanted.x() - centreX;
        double dy = wantedCentreY - centreY;
        double dz = wanted.z() - centreZ;

        // Swept, never carried: a fast fall has to meet the floor. The old cap skipped the
        // world entirely once a step was big enough, and at 0.25 m per block a terminal-velocity
        // fall is 32 blocks a second - 1.6 blocks a tick - so an emergency stop from altitude
        // went through the ground and kept going.
        PhysicsStep.Result resolved = PhysicsStep.resolveSwept(
            centreX, centreY, centreZ,
            dx, dy, dz,
            halfWidth, halfHeight,
            probeBox -> isBoxFreeForDrone(entity, probeBox),
            MAX_PHYSICS_STEP_M,
            MAX_PHYSICS_SUBSTEPS);
        // The bottom of the world is solid too, so nothing can leave it downwards even if a
        // step was large enough to be treated as a carry.
        double worldFloorCentreY = server.overworld().getMinBuildHeight() + halfHeight + WORLD_FLOOR_MARGIN_M;
        if (resolved.y() < worldFloorCentreY) {
            resolved = new PhysicsStep.Result(
                resolved.x(), worldFloorCentreY, resolved.z(),
                resolved.blockedX(), true, resolved.blockedZ());
        }

        // Translate, so the placement cannot depend on where the position sits in the box.
        entity.setPos(
            entity.getX() + (resolved.x() - centreX),
            entity.getY() + (resolved.y() - centreY),
            entity.getZ() + (resolved.z() - centreZ));
        entity.setYRot(wanted.yawDegrees());
        entity.setXRot(wanted.pitchDegrees());
        // Roll has no entity rotation of its own and rides on synced data instead, so it has
        // to be pushed every tick the way yaw and pitch are pushed through the entity. Nothing
        // was writing it during flight - only when the entity was first placed - so the model
        // stayed perfectly level while the simulation banked it, and a vehicle flying east or
        // west appeared to have no attitude at all.
        entity.setRollDegrees(wanted.rollDegrees());
        entity.setDeltaMovement(new Vec3(
            -snapshot.velocityEastMps(),
            -snapshot.velocityDownMps(),
            -snapshot.velocityNorthMps()
        ));

        double[] ned = DronePlacement.nedOffsetFor(
            transform, resolved.x(), resolved.y() - halfHeight, resolved.z());
        // A thin slab *below the feet*. Probing a full-height box centred below the box
        // centre included the vehicle's own volume, so on a solid floor layer the
        // neighbouring blocks still counted as support after the one underneath was broken
        // and the vehicle hung in the air. Support means something underneath it.
        double feetY = resolved.y() - halfHeight;
        boolean supported = !isBoxFree(entity, PhysicsStep.boxAt(
            resolved.x(), feetY - SUPPORT_PROBE_M / 2.0, resolved.z(),
            halfWidth, SUPPORT_PROBE_M / 2.0));

        // What stopped it matters as much as that it was stopped. A collision here is the
        // world's ordinary physics: the vehicle stops against whatever it hit, slides, or
        // nudges it - it is never destroyed and never latched. The impact scale still says how
        // hard the contact was, so a bump can be shown as a bump, but it is capped at CONTACT.
        final boolean blockedByEntity =
            (resolved.blockedHorizontally() || resolved.blockedVertically()) &&
            overlapsAnotherEntity(entity, PhysicsStep.boxAt(
                wanted.x(), wantedCentreY, wanted.z(), halfWidth, halfHeight));
        // A blocked vertical axis only counts as a contact when it stopped a descent -
        // hitting a ceiling on the way up is not one.
        double horizontalSpeed = Math.hypot(
            snapshot.velocityNorthMps(), snapshot.velocityEastMps());
        double descentSpeed = Math.max(0.0, snapshot.velocityDownMps());
        ImpactModel.Outcome impact = ImpactModel.Outcome.NONE;
        if (resolved.blockedHorizontally()) {
            impact = contactOnly(ImpactModel.assess(horizontalSpeed, 0.0));
        }
        if (resolved.blockedVertically() && descentSpeed > 0.0) {
            ImpactModel.Outcome vertical = contactOnly(ImpactModel.assess(0.0, descentSpeed));
            if (vertical == ImpactModel.Outcome.CONTACT && impact == ImpactModel.Outcome.NONE) {
                impact = vertical;
            }
        }
        if (blockedByEntity) {
            // The world answers a bump by pushing, so a drone that bumps another one is felt
            // rather than merely stopped. The pushed vehicle adopts that offset on its own
            // next step (see entityPosition).
            nudgeBumpedDrones(
                entity,
                PhysicsStep.boxAt(wanted.x(), wantedCentreY, wanted.z(), halfWidth, halfHeight),
                wanted.x() - centreX,
                wanted.z() - centreZ);
        }

        return new StepResult(
            ned[0], ned[1], ned[2],
            resolved.blockedHorizontally(), resolved.blockedVertically(),
            resolved.blockedZ(), resolved.blockedX(),
            supported, impact);
    }

    /**
     * Where the entity is *right now*, in local NED, without moving anything.
     *
     * <p>The world is the authority for position: a player shoving the drone, a piston,
     * or anything else that moves it has to survive the next physics step, and the only
     * way that works is for the simulation to adopt the entity's actual position before
     * it integrates its own motion. Reading it first is what makes a push stick instead
     * of being undone a tick later.
     */
    public StepResult entityPosition(String droneId) {
        DroneEntity entity = entities.get(droneId);
        if (entity == null || entity.isRemoved() || transform == null) {
            return null;
        }
        AABB box = entity.getBoundingBox();
        double halfWidth = Math.max(0.05, (box.maxX - box.minX) / 2.0);
        double halfHeight = Math.max(0.05, (box.maxY - box.minY) / 2.0);
        double[] ned = DronePlacement.nedOffsetFor(
            transform, (box.minX + box.maxX) / 2.0, box.minY, (box.minZ + box.maxZ) / 2.0);
        boolean supported = !isBoxFree(
            entity,
            PhysicsStep.boxAt(
                (box.minX + box.maxX) / 2.0, box.minY - SUPPORT_PROBE_M / 2.0,
                (box.minZ + box.maxZ) / 2.0, halfWidth, SUPPORT_PROBE_M / 2.0));
        return new StepResult(ned[0], ned[1], ned[2], false, false, false, false, supported, ImpactModel.Outcome.NONE);
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

    /** How much of a bump is passed on to whatever was bumped, as a fraction of the step. */
    private static final double BUMP_NUDGE_FRACTION = 0.5;

    /**
     * Whether the vehicle fits at that box.
     *
     * <p>The level answers with the shapes of the blocks that overlap it, which is the
     * same list the game uses for everything else - so "solid" means whatever this
     * world says it means, slabs, stairs and fences included.
     */
    private boolean isBoxFree(DroneEntity entity, double[] box) {
        AABB candidate = new AABB(box[0], box[1], box[2], box[3], box[4], box[5]);
        return !server.overworld().getBlockCollisions(entity, candidate).iterator().hasNext();
    }

    /**
     * Caps an impact at {@code CONTACT}.
     *
     * <p>The impact scale exists to say how hard a hit was, and a hard hit used to end the
     * flight and latch the vehicle. A collision in this world is ordinary physics instead, so
     * how hard it was can still be reported - it just cannot destroy anything.
     */
    private static ImpactModel.Outcome contactOnly(ImpactModel.Outcome outcome) {
        return outcome == ImpactModel.Outcome.CRASH ? ImpactModel.Outcome.CONTACT : outcome;
    }

    /**
     * Whether that box would touch another drone or a player.
     *
     * <p>Used to tell "a wall stopped it" from "another entity stopped it". The two are not
     * the same event to the vehicle: one is damage, the other is the world's ordinary
     * collision.
     */
    private boolean overlapsAnotherEntity(DroneEntity self, double[] box) {
        AABB candidate = new AABB(box[0], box[1], box[2], box[3], box[4], box[5]);
        for (DroneEntity other : entities.values()) {
            if (other == null || other == self || other.isRemoved()) {
                continue;
            }
            if (other.getBoundingBox().intersects(candidate)) {
                return true;
            }
        }
        for (ServerPlayer player : server.overworld().players()) {
            if (player == null || player.isRemoved() || player.isSpectator()) {
                continue;
            }
            if (player.getBoundingBox().intersects(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Shoves the drones this one bumped into, the way vanilla pushes entities apart.
     *
     * <p>Only drones: a player who walks into a vehicle is already handled by vanilla's own
     * push (see {@code DroneEntity.push}), and moving players from a machine's tick is not
     * this mod's business.
     */
    private void nudgeBumpedDrones(DroneEntity self, double[] box, double dx, double dz) {
        double length = Math.hypot(dx, dz);
        if (length <= 0.0) {
            return;
        }
        AABB candidate = new AABB(box[0], box[1], box[2], box[3], box[4], box[5]);
        double nudge = Math.min(0.2, length) / length * BUMP_NUDGE_FRACTION;
        for (DroneEntity other : entities.values()) {
            if (other == null || other == self || other.isRemoved()) {
                continue;
            }
            if (other.getBoundingBox().intersects(candidate)) {
                other.push(dx / length * nudge, 0.0, dz / length * nudge);
            }
        }
    }

    /**
     * Whether the vehicle fits at that box <em>and</em> is clear of every other drone and
     * every player.
     *
     * <p>The physics step resolves blocks; a drone is not a block, so without this the second
     * aircraft flies straight through the first one and an operator walks into a hovering
     * vehicle that simply is not there. Drone and player alike are treated exactly like a
     * wall - the vehicle stops at it, slides along it, and reports the blocked axis the same
     * way - which is what makes them physical objects to each other.
     */
    private boolean isBoxFreeForDrone(DroneEntity self, double[] box) {
        if (!isBoxFree(self, box)) {
            return false;
        }
        AABB candidate = new AABB(box[0], box[1], box[2], box[3], box[4], box[5]);
        for (DroneEntity other : entities.values()) {
            if (other == null || other == self || other.isRemoved()) {
                continue;
            }
            if (other.getBoundingBox().intersects(candidate)) {
                return false;
            }
        }
        for (ServerPlayer player : server.overworld().players()) {
            if (player == null || player.isRemoved() || player.isSpectator()) {
                continue;
            }
            if (player.getBoundingBox().intersects(candidate)) {
                return false;
            }
        }
        return true;
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

    private DroneEntity ensureEntity(VirtualDroneSnapshot snapshot) {
        String droneId = snapshot.droneId();
        DroneEntity existing = entities.get(droneId);
        if (existing != null && !existing.isRemoved()) {
            return existing;
        }
        if (existing != null) {
            entities.remove(droneId);
        }

        // A drone that was placed before this world was saved already has an entity. Adopting
        // it is what makes placement survive a restart: spawning a fresh one here would leave
        // the saved aircraft standing next to its own duplicate.
        DroneEntity adopted = findEntityInWorld(droneId);
        if (adopted != null) {
            entities.put(droneId, adopted);
            if (transform == null) {
                ServerPlayer player = firstPlayer();
                if (player != null) {
                    transform = chooseOrigin(player);
                }
            }
            if (transform != null) {
                adopted.applySnapshot(snapshot, transform.toWorldPose(snapshot));
                liftOutOfBlocks(adopted);
            }
            return adopted;
        }

        ServerLevel level = server.overworld();
        ServerPlayer player = firstPlayer();
        if (player == null) {
            return null;
        }

        if (transform == null) {
            transform = chooseOrigin(player);
        }
        DroneEntity spawned = new DroneEntity(ModEntityTypes.DRONE, level);
        // Which drone this entity is: read back by the collect path and by the logs, so a
        // right-click can only ever mean the vehicle that was clicked - and saved with the
        // entity, so the same vehicle comes back after a restart.
        spawned.setDroneId(droneId);
        spawned.setSystemIdHint(snapshot.systemId());
        spawned.applySnapshot(snapshot, transform.toWorldPose(snapshot));
        liftOutOfBlocks(spawned);
        if (!level.addFreshEntity(spawned)) {
            MiniDroneMod.LOGGER.warn("Unable to spawn the virtual drone entity for {}", droneId);
            return null;
        }
        entities.put(droneId, spawned);
        logOrigin("Spawned", snapshot);
        return spawned;
    }

    /**
     * Lifts an entity that has just been placed inside solid blocks to the first spot above it
     * that it fits in.
     *
     * <p>A saved drone is put back exactly where it was, and "where it was" can be inside the
     * terrain: an aircraft that tunnelled through the floor was saved mid-fall and came back
     * buried, which is not a vehicle anyone can fly. The world owns the position, so the
     * entity is what has to move: correcting only the simulation is undone on the next tick,
     * when the simulation adopts the entity's position back.
     */
    private void liftOutOfBlocks(DroneEntity entity) {
        AABB box = entity.getBoundingBox();
        double halfWidth = Math.max(0.05, (box.maxX - box.minX) / 2.0);
        double halfHeight = Math.max(0.05, (box.maxY - box.minY) / 2.0);
        double centreX = (box.minX + box.maxX) / 2.0;
        double centreY = (box.minY + box.maxY) / 2.0;
        double centreZ = (box.minZ + box.maxZ) / 2.0;
        if (isBoxFreeForDrone(entity, PhysicsStep.boxAt(centreX, centreY, centreZ, halfWidth, halfHeight))) {
            return;
        }
        for (double lift = FIT_STEP_M; lift <= MAX_FIT_LIFT_M; lift += FIT_STEP_M) {
            double candidateY = centreY + lift;
            if (isBoxFreeForDrone(entity, PhysicsStep.boxAt(centreX, candidateY, centreZ, halfWidth, halfHeight))) {
                entity.setPos(entity.getX(), entity.getY() + lift, entity.getZ());
                MiniDroneMod.LOGGER.info(
                    "Lifted {} out of the ground: its saved spot was inside blocks",
                    entity.droneId());
                return;
            }
        }
    }

    private ServerPlayer firstPlayer() {
        return server.overworld()
            .getPlayers(candidate -> !candidate.isSpectator())
            .stream()
            .findFirst()
            .orElse(null);
    }

    /** The entity showing this drone that the world already has, or null. */
    private DroneEntity findEntityInWorld(String droneId) {
        if (droneId == null || droneId.isBlank()) {
            return null;
        }
        for (DroneEntity entity : droneEntitiesInWorld()) {
            if (droneId.equals(entity.droneId())) {
                return entity;
            }
        }
        return null;
    }

    /**
     * Every drone entity the world has right now, in ascending id order.
     *
     * <p>The order is what lets the fleet rebuild the same identities: the MAVLink system id
     * a drone gets is allocated in creation order, and a world reloaded from disk has to hand
     * out the same ones it handed out before.
     */
    public List<DroneEntity> droneEntitiesInWorld() {
        List<DroneEntity> found = new ArrayList<>();
        for (Entity entity : server.overworld().getAllEntities()) {
            if (entity instanceof DroneEntity drone && !drone.isRemoved()) {
                String droneId = drone.droneId();
                if (droneId != null && !droneId.isBlank()) {
                    found.add(drone);
                }
            }
        }
        found.sort(Comparator.comparing(DroneEntity::droneId));
        return found;
    }

    /** Registers the entities the world already has, so they are driven instead of duplicated. */
    public void adoptExistingDrones() {
        for (DroneEntity entity : droneEntitiesInWorld()) {
            entities.putIfAbsent(entity.droneId(), entity);
        }
    }

    /** The entity showing a drone, or null when the world has not spawned it (yet). */
    public DroneEntity entityFor(String droneId) {
        DroneEntity entity = entities.get(droneId);
        return entity == null || entity.isRemoved() ? null : entity;
    }

    /** The drone ids that currently have an entity, in spawn order. */
    public Set<String> entityDroneIds() {
        return Set.copyOf(entities.keySet());
    }

    /** Removes the entity of a drone the fleet no longer has. */
    public boolean releaseDrone(String droneId) {
        DroneEntity entity = entities.remove(droneId);
        if (entity == null) {
            return false;
        }
        if (!entity.isRemoved()) {
            entity.discard();
        }
        return true;
    }

    /**
     * Drops the entities of drones that are no longer in the fleet, so a removed vehicle
     * cannot leave a ghost in the world that the physics step keeps ticking.
     */
    public void retainDrones(Collection<String> liveDroneIds) {
        for (String droneId : new ArrayList<>(entities.keySet())) {
            if (!liveDroneIds.contains(droneId)) {
                releaseDrone(droneId);
            }
        }
    }

    public void resetOrigin(ServerPlayer player, VirtualDroneSnapshot snapshot) {
        transform = chooseOrigin(player);
        DroneEntity entity = ensureEntity(snapshot);
        if (entity == null) {
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

    /**
     * Lets go of the world's drones without removing them.
     *
     * <p>This runs when the server stops, which is *before* the world is saved: a placed drone
     * is part of the world now, so discarding it here deleted exactly what was about to be
     * written down - the operator's second drone never came back after a restart. The entities
     * stay in the level, and whoever loads the world next adopts them.
     */
    @Override
    public void close() {
        entities.clear();
        transform = null;
    }
}
