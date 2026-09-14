package com.vltbr.minidrone.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

/** World-persistent opt-in for the loopback virtual motion-capture source. */
public final class VirtualMocapSettingsSavedData extends SavedData {
    public static final String DATA_ID = "mini_drone_virtual_mocap_settings";

    /**
     * The data-fix type handed to {@link Factory}.
     *
     * <p>It must not be null. Loading an existing file calls
     * {@code DataFixTypes.update(...)} on this value before the deserializer
     * runs, so a null type throws inside the load path - where the exception is
     * caught and only logged. The factory then builds a fresh default, so the
     * setting looks saved but comes back as {@code false} after the next world
     * load, with "Error loading saved data" in the log.
     *
     * <p>{@code LEVEL} is the conventional choice for third-party schemas: the
     * fixers only run when the stored DataVersion differs from the running one.
     */
    public static final DataFixTypes DATA_FIX_TYPE = DataFixTypes.LEVEL;

    private boolean enabled;

    public static Factory<VirtualMocapSettingsSavedData> factory() {
        return new Factory<>(
            VirtualMocapSettingsSavedData::new,
            VirtualMocapSettingsSavedData::load,
            DATA_FIX_TYPE
        );
    }

    public static VirtualMocapSettingsSavedData load(
        CompoundTag tag,
        HolderLookup.Provider registries
    ) {
        VirtualMocapSettingsSavedData data = new VirtualMocapSettingsSavedData();
        data.enabled = tag.getBoolean("enabled");
        return data;
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            this.enabled = enabled;
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean("enabled", enabled);
        return tag;
    }
}
