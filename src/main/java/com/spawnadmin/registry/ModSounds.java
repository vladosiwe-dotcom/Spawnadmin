package com.spawnadmin.registry;

import com.spawnadmin.SpawnAdmin;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, SpawnAdmin.MOD_ID);

    public static final DeferredHolder<SoundEvent, SoundEvent> HEAVEN_HORN = register("heaven_horn");
    public static final DeferredHolder<SoundEvent, SoundEvent> SKY_GLOW = register("sky_glow");
    public static final DeferredHolder<SoundEvent, SoundEvent> SKY_CRACK = register("sky_crack");
    public static final DeferredHolder<SoundEvent, SoundEvent> SKY_SHATTER = register("sky_shatter");
    public static final DeferredHolder<SoundEvent, SoundEvent> TIME_STOP = register("time_stop");
    public static final DeferredHolder<SoundEvent, SoundEvent> HAND_APPEAR = register("hand_appear");
    public static final DeferredHolder<SoundEvent, SoundEvent> GRAB_TELEPORT = register("grab_teleport");
    public static final DeferredHolder<SoundEvent, SoundEvent> SKY_RESTORE = register("sky_restore");

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(SpawnAdmin.id(name)));
    }

    private ModSounds() {
    }
}
