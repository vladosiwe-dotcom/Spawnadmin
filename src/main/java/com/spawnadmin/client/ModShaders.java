package com.spawnadmin.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.spawnadmin.SpawnAdmin;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

import java.io.IOException;

/** Core shaders of the sky event (assets/spawnadmin/shaders/core). */
public final class ModShaders {
    public static ShaderInstance glass;
    public static ShaderInstance crack;
    public static ShaderInstance rift;
    public static ShaderInstance hand;
    public static ShaderInstance timeStop;

    private ModShaders() {
    }

    static void register(RegisterShadersEvent event) throws IOException {
        var rp = event.getResourceProvider();
        var world = DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL;
        event.registerShader(new ShaderInstance(rp, SpawnAdmin.id("sky_glass"), DefaultVertexFormat.NEW_ENTITY), s -> glass = s);
        event.registerShader(new ShaderInstance(rp, SpawnAdmin.id("sky_crack"), world), s -> crack = s);
        event.registerShader(new ShaderInstance(rp, SpawnAdmin.id("sky_rift"), world), s -> rift = s);
        event.registerShader(new ShaderInstance(rp, SpawnAdmin.id("sky_hand"), world), s -> hand = s);
        event.registerShader(new ShaderInstance(rp, SpawnAdmin.id("sky_timestop"), DefaultVertexFormat.POSITION_TEX), s -> timeStop = s);
    }

    static boolean ready() {
        return glass != null && crack != null && rift != null && hand != null && timeStop != null;
    }
}
