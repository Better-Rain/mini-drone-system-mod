package com.vltbr.minidrone.entity;

import com.vltbr.minidrone.sim.VirtualDroneSnapshot;
import com.vltbr.minidrone.world.WorldPose;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

public final class DroneEntity extends Entity {
    /** How far one shove moves the vehicle, per tick of contact. */
    private static final double ENTITY_SHOVE_MPS = 0.06;

    private static final EntityDataAccessor<Boolean> ARMED = SynchedEntityData.defineId(
        DroneEntity.class,
        EntityDataSerializers.BOOLEAN
    );
    private static final EntityDataAccessor<Boolean> AIRBORNE = SynchedEntityData.defineId(
        DroneEntity.class,
        EntityDataSerializers.BOOLEAN
    );
    private static final EntityDataAccessor<Float> ROLL_DEGREES = SynchedEntityData.defineId(
        DroneEntity.class,
        EntityDataSerializers.FLOAT
    );
    private static final EntityDataAccessor<Float> BATTERY_PERCENT = SynchedEntityData.defineId(
        DroneEntity.class,
        EntityDataSerializers.FLOAT
    );

    /**
     * Which drone in the fleet this entity is.
     *
     * <p>Synced so both sides agree, and defaulted to the id a single-drone world has always
     * used, so nothing observable changes while there is only one. Multi-drone work needs it
     * before anything else: a right-click on an entity has to mean <em>that</em> vehicle, and
     * the world controller and the health beacon have to be able to say which one they are
     * talking about.
     */
    private static final String DEFAULT_DRONE_ID = "minecraft_drone_01";

    private static final EntityDataAccessor<String> DRONE_ID = SynchedEntityData.defineId(
        DroneEntity.class,
        EntityDataSerializers.STRING
    );

    /** The id of the drone this entity shows, which is what commands and the beacon key on. */
    public String droneId() {
        return entityData.get(DRONE_ID);
    }

    /** Points this entity at another drone in the fleet. */
    public void setDroneId(String droneId) {
        entityData.set(DRONE_ID, droneId == null || droneId.isBlank() ? DEFAULT_DRONE_ID : droneId);
    }

    /**
     * The MAVLink system id this drone had when it was last saved.
     *
     * <p>Saved alongside the id so a world that is loaded again rebuilds the same identities
     * the monitoring side already knows: the fleet hands out system ids, and re-deriving them
     * from scratch would rename every aircraft the operator has ever commanded.
     */
    public int systemIdHint() {
        return systemIdHint;
    }

    public void setSystemIdHint(int systemId) {
        this.systemIdHint = systemId;
    }

    public DroneEntity(EntityType<? extends DroneEntity> entityType, Level level) {
        super(entityType, level);
        // Deliberately NOT noPhysics: vanilla's entity pushing starts with
        // `if (!entity.noPhysics && !this.noPhysics)`, so a noPhysics vehicle can never be
        // shoved by a player walking into it - which is exactly what the operator found.
        // Block collision for this vehicle is resolved by the physics step against
        // getBoundingBox(), not by Entity.move, so nothing here depends on the flag.
        setNoGravity(true);
        setInvulnerable(true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(ARMED, false);
        builder.define(AIRBORNE, false);
        builder.define(ROLL_DEGREES, 0.0F);
        builder.define(BATTERY_PERCENT, 100.0F);
        builder.define(DRONE_ID, DEFAULT_DRONE_ID);
    }

    public void applySnapshot(VirtualDroneSnapshot snapshot, WorldPose pose) {
        entityData.set(ARMED, snapshot.armed());
        entityData.set(AIRBORNE, snapshot.airborne());
        entityData.set(ROLL_DEGREES, pose.rollDegrees());
        entityData.set(BATTERY_PERCENT, (float) snapshot.batteryPercent());

        setPos(pose.x(), pose.y(), pose.z());
        setYRot(pose.yawDegrees());
        setXRot(pose.pitchDegrees());
        setDeltaMovement(new Vec3(
            -snapshot.velocityEastMps(),
            -snapshot.velocityDownMps(),
            -snapshot.velocityNorthMps()
        ));

        String phase = snapshot.landing()
            ? "LANDING"
            : snapshot.takingOff()
                ? "TAKEOFF"
                : snapshot.armed() ? "ARMED" : "SAFE";
        setCustomName(Component.literal(String.format(Locale.ROOT,
            "%s | %s | %.0f%%",
            snapshot.droneId(),
            phase,
            snapshot.batteryPercent()
        )));
        setCustomNameVisible(true);
    }

    public boolean isArmed() {
        return entityData.get(ARMED);
    }

    public boolean isAirborne() {
        return entityData.get(AIRBORNE);
    }

    public float rollDegrees() {
        return entityData.get(ROLL_DEGREES);
    }

    /**
     * The bank angle the renderer applies.
     *
     * <p>Yaw and pitch ride on the entity's own rotation, which the physics step sets every
     * tick; roll has no entity field of its own and lives in synced data instead. Nothing was
     * writing it during flight - only when the entity was first placed - so a vehicle sliding
     * sideways rendered perfectly level while the simulation was banking it.
     */
    public void setRollDegrees(float rollDegrees) {
        entityData.set(ROLL_DEGREES, rollDegrees);
    }

    public float batteryPercent() {
        return entityData.get(BATTERY_PERCENT);
    }

    @Override
    public void tick() {
        super.tick();
        setNoGravity(true);
    }

    /**
     * Right-clicking the drone with the placement item picks it up.
     *
     * <p>So the item reads as "carry the drone": right-click the world to set it down
     * somewhere, right-click the drone to bring it home. Before this the only way back was
     * the {@code /minidrone drone reset} command, which is not what an operator reaches for
     * after carrying the vehicle to the far side of the arena.
     *
     * <p>This lives on the entity rather than the item because the vehicle is a plain
     * {@link Entity}, not a living one, so {@code Item.interactLivingEntity} never fires for
     * it; the client sends the interaction to the entity itself.
     */
    @Override
    public net.minecraft.world.InteractionResult interact(
        net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand
    ) {
        if (!(player.getItemInHand(hand).getItem()
            instanceof com.vltbr.minidrone.item.DronePlacementItem)) {
            return net.minecraft.world.InteractionResult.PASS;
        }
        if (level().isClientSide()
            || !(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) {
            return net.minecraft.world.InteractionResult.SUCCESS;
        }
        com.vltbr.minidrone.sim.VirtualDroneManager.OriginResetResult result =
            com.vltbr.minidrone.world.DronePlacementHook.collect(serverPlayer, droneId());
        if (result == com.vltbr.minidrone.sim.VirtualDroneManager.OriginResetResult.DRONE_ACTIVE) {
            player.displayClientMessage(
                net.minecraft.network.chat.Component.literal(
                    "Land and disarm the virtual drone before collecting it.")
                    .withStyle(net.minecraft.ChatFormatting.RED),
                false
            );
        } else if (result != com.vltbr.minidrone.sim.VirtualDroneManager.OriginResetResult.RESET) {
            player.displayClientMessage(
                net.minecraft.network.chat.Component.literal(
                    "The virtual drone belongs to the training world; it cannot be collected here.")
                    .withStyle(net.minecraft.ChatFormatting.RED),
                false
            );
        }
        return net.minecraft.world.InteractionResult.SUCCESS;
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean isPushable() {
        // A player who walks into the drone shoves it: it is an entity in the world first
        // and a motion-capture target second, so the world is allowed to move it.
        return true;
    }

    /**
     * A shove moves the vehicle directly.
     *
     * <p>A plain {@link Entity} never applies its own delta movement - that lives in the
     * mobile subclasses - so recording a delta here would be forgotten on the next tick.
     * Moving the position instead lets the simulation adopt wherever the world put the
     * vehicle, which is what makes a push stick. The physics step runs with the world as
     * the authority for position, so this is not fighting it.
     */
    @Override
    public void push(double x, double y, double z) {
        // Horizontal only: the physics step resolves vertical motion against the blocks,
        // and a downward shove would push the vehicle into the ground it is standing on.
        setPos(getX() + x, getY(), getZ() + z);
    }

    /**
     * Being shoved by another entity, computed here rather than inherited.
     *
     * <p>Vanilla's version is skipped for vehicles whose {@code noPhysics} is set, and it
     * only records a delta movement - which a plain {@link Entity} never applies. This
     * vehicle is a physical object in the world, so a shove moves it outright and the
     * simulation adopts the new position on its next tick.
     */
    @Override
    public void push(Entity entity) {
        double dx = getX() - entity.getX();
        double dz = getZ() - entity.getZ();
        double distance = Math.max(Math.abs(dx), Math.abs(dz));
        if (distance < 1.0E-4) {
            return;
        }
        double shove = ENTITY_SHOVE_MPS;
        push(dx / distance * shove, 0.0, dz / distance * shove);
    }

    @Override
    public boolean canCollideWith(Entity other) {
        return true;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean shouldBeSaved() {
        // A placed drone is part of the world: the operator put it there, so it has to be
        // there again after a restart. Not saving it meant every restart left the operator
        // with one aircraft and a second one to place by hand.
        return true;
    }

    private static final String DRONE_ID_TAG = "MiniDroneId";
    private static final String SYSTEM_ID_TAG = "MiniDroneSystemId";

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains(DRONE_ID_TAG)) {
            setDroneId(tag.getString(DRONE_ID_TAG));
        }
        if (tag.contains(SYSTEM_ID_TAG)) {
            systemIdHint = tag.getInt(SYSTEM_ID_TAG);
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putString(DRONE_ID_TAG, droneId());
        tag.putInt(SYSTEM_ID_TAG, systemIdHint);
    }

    /** The MAVLink system id this entity was saved with, or 0 when it never had one. */
    private int systemIdHint;
}
