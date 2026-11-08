package com.spawnadmin.realm;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Saved position a player returns to when leaving the administrator's realm. */
public record ReturnPoint(boolean valid, ResourceKey<Level> dimension, Vec3 pos, float yRot, float xRot) {
    public static final ReturnPoint EMPTY = new ReturnPoint(false, Level.OVERWORLD, Vec3.ZERO, 0f, 0f);

    public static final Codec<ReturnPoint> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.BOOL.fieldOf("valid").forGetter(ReturnPoint::valid),
            ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(ReturnPoint::dimension),
            Vec3.CODEC.fieldOf("pos").forGetter(ReturnPoint::pos),
            Codec.FLOAT.fieldOf("y_rot").forGetter(ReturnPoint::yRot),
            Codec.FLOAT.fieldOf("x_rot").forGetter(ReturnPoint::xRot)
    ).apply(i, ReturnPoint::new));
}
