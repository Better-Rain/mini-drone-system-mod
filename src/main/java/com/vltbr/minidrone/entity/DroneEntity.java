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

    public float batteryPercent() {
        return entityData.get(BATTERY_PERCENT);
    }

    @Override
    public void tick() {
        super.tick();
        setNoGravity(true);
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
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }
}
