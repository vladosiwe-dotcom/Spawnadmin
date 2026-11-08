package com.spawnadmin.registry;

import com.spawnadmin.SpawnAdmin;
import com.spawnadmin.realm.ReturnPoint;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

public final class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, SpawnAdmin.MOD_ID);

    /** Where a player came from before entering the administrator's realm. Survives death. */
    public static final Supplier<AttachmentType<ReturnPoint>> RETURN_POINT = ATTACHMENTS.register("return_point",
            () -> AttachmentType.builder(() -> ReturnPoint.EMPTY).serialize(ReturnPoint.CODEC).copyOnDeath().build());

    private ModAttachments() {
    }
}
