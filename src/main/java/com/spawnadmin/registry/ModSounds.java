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
    public static final DeferredHolder<SoundEvent, SoundEvent> SKY_CRACK = register("sky_crack");
    public static final DeferredHolder<SoundEvent, SoundEvent> SKY_SHATTER = register("sky_shatter");
    public static final DeferredHolder<SoundEvent, SoundEvent> TIME_STOP = register("time_stop");
    public static final DeferredHolder<SoundEvent, SoundEvent> HAND_APPEAR = register("hand_appear");
    public static final DeferredHolder<SoundEvent, SoundEvent> GRAB_TELEPORT = register("grab_teleport");
    public static final DeferredHolder<SoundEvent, SoundEvent> SKY_RESTORE = register("sky_restore");
    public static final DeferredHolder<SoundEvent, SoundEvent> FIRST_CRACK = register("first_crack");
    public static final DeferredHolder<SoundEvent, SoundEvent> RIFT_OPEN = register("rift_open");
    public static final DeferredHolder<SoundEvent, SoundEvent> GRAB_CLOSE = register("grab_close");
    public static final DeferredHolder<SoundEvent, SoundEvent> LIFT_WIND = register("lift_wind");
    public static final DeferredHolder<SoundEvent, SoundEvent> VANISH_FLASH = register("vanish_flash");
    public static final DeferredHolder<SoundEvent, SoundEvent> CLOCK_TICK = register("clock_tick");
    public static final DeferredHolder<SoundEvent, SoundEvent> CLOCK_TOCK = register("clock_tock");
    public static final DeferredHolder<SoundEvent, SoundEvent> HOUR_STRIKE = register("hour_strike");
    public static final DeferredHolder<SoundEvent, SoundEvent> CLOCK_HALT = register("clock_halt");
    /** Minimal score of the event, 28 s, aligned to the timeline from the GLOW tick. */
    public static final DeferredHolder<SoundEvent, SoundEvent> THEME_SUMMONING = register("theme_summoning");
    /** Quiet ambient music of the administrator's realm. */
    public static final DeferredHolder<SoundEvent, SoundEvent> MUSIC_ADMIN_REALM = register("music.admin_realm");

    private static DeferredHolder<SoundEvent, SoundEvent> register(String name) {
        return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(SpawnAdmin.id(name)));
    }

    private ModSounds() {
    }
}
