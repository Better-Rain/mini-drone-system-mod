package com.vltbr.minidrone.world;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

/** World-persistent opt-in for the loopback virtual motion-capture source. */
public final class VirtualMocapSettingsSavedData extends SavedData {
    public static final String DATA_ID = "mini_drone_virtual_mocap_settings";

    private boolean enabled;

    public static Factory<VirtualMocapSettingsSavedData> factory() {
        return new Factory<>(
            VirtualMocapSettingsSavedData::new,
            VirtualMocapSettingsSavedData::load,
            null
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
