package com.vltbr.minidrone.entity;

import com.vltbr.minidrone.MiniDroneMod;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class ModEntityTypes {
    public static final EntityType<DroneEntity> DRONE = Registry.register(
        BuiltInRegistries.ENTITY_TYPE,
        MiniDroneMod.id("drone"),
        EntityType.Builder.of(DroneEntity::new, MobCategory.MISC)
            .sized(0.9F, 0.35F)
            .clientTrackingRange(10)
            .updateInterval(1)
            .noSave()
            .noSummon()
            .build(MiniDroneMod.id("drone").toString())
    );

    private ModEntityTypes() {
    }

    public static void initialize() {
        // Loading this class performs the registry operation above.
    }
}

