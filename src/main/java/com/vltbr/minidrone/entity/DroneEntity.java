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
        noPhysics = true;
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
        noPhysics = true;
        setNoGravity(true);
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canCollideWith(Entity other) {
        return false;
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
