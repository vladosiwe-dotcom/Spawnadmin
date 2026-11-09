package com.spawnadmin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.spawnadmin.sky.SkyScene;
import com.spawnadmin.sky.SkyTimeline;
import com.spawnadmin.sky.geom.DomeShape;
import com.spawnadmin.sky.geom.V3;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.HashMap;
import java.util.Map;

/**
 * The film look of the event, drawn over the finished frame (before the held item and HUD):
 * the world dims as it begins and stays a little grey, a star-shaped lens flare where the light
 * breaks through, a spectral shock ring at the snap and at the collapse, prism ghosts, rainbow
 * light leaks while the shards fly, and a pull-warp when the target is dragged back. Plus
 * letterbox bars. Everything is derived from the event clock, so all viewers see the same.
 */
final class Cinema {
    /** Smoothed visibility of each event's light source (terrain may hide it). */
    private static final Map<Integer, Double> VISIBLE = new HashMap<>();
    private static final Vector4f CLIP = new Vector4f();

    private Cinema() {
    }

    // ------------------------------------------------------------------ timeline curves

    /** How much the world is dimmed: a dip when it begins, then a slight, steady dusk. */
    static double darken(double t, double r) {
        if (t < 0) return 0;
        double dip = SkyScene.smooth(SkyTimeline.progress(t, 0, 8)) * (1 - 0.6 * SkyScene.smooth(SkyTimeline.progress(t, 16, 70)));
        return 0.34 * dip * fadeOut(t, r);
    }

    /** How grey the world turns while the sky breaks. */
    static double desaturate(double t, double r) {
        if (t < 0) return 0;
        return 0.5 * SkyScene.smooth(SkyTimeline.progress(t, 0, 50)) * fadeOut(t, r);
    }

    private static double fadeOut(double t, double r) {
        if (t < r) return 1;
        return 1 - SkyScene.smoothstep(0.25, 1.0, SkyScene.restoreProgress(t, r));
    }

    /** Lens flare strength at the light source. */
    static double flare(double t, double r) {
        if (t < 0) return 0;
        // Light pressing through the bent glass; quiet while the cracks spread so they stay readable.
        double f = 0.22 * SkyScene.smooth(SkyTimeline.progress(t, 15, SkyTimeline.FIRST_CRACK));
        if (t >= SkyTimeline.FIRST_CRACK && r > SkyTimeline.FIRST_CRACK) {
            f *= 1 - 0.6 * SkyScene.smooth(SkyTimeline.progress(t, SkyTimeline.FIRST_CRACK, SkyTimeline.FIRST_CRACK + 30));
            f += 1.3 * Math.exp(-(t - SkyTimeline.FIRST_CRACK) / 5.0);
            f += 0.2 * SkyScene.smooth(SkyTimeline.progress(t, SkyTimeline.CONNECT, SkyTimeline.FALL));
        }
        if (t >= SkyTimeline.FALL && r > SkyTimeline.FALL) {
            f += 1.1 * Math.exp(-(t - SkyTimeline.FALL) / 9.0) + 0.2 * SkyScene.smooth(SkyTimeline.progress(t, SkyTimeline.FALL, SkyTimeline.FREEZE));
        }
        if (t >= r) {
            f *= 1 - SkyScene.smoothstep(0.0, 0.45, SkyScene.restoreProgress(t, r));
        }
        return f;
    }

    /** Shock ring: {radius, intensity, thickness} or null. One at the snap, a bigger one at the collapse. */
    static double[] ring(double t, double r) {
        if (t >= SkyTimeline.FALL && t < SkyTimeline.FALL + 34 && r > SkyTimeline.FALL) {
            double p = SkyTimeline.progress(t, SkyTimeline.FALL, SkyTimeline.FALL + 34);
            return new double[]{0.05 + 1.35 * (1 - Math.pow(1 - p, 3)), 1.3 * (1 - p) * (1 - p), 0.06 + 0.1 * p};
        }
        if (t >= SkyTimeline.FIRST_CRACK && t < SkyTimeline.FIRST_CRACK + 26 && r > SkyTimeline.FIRST_CRACK) {
            double p = SkyTimeline.progress(t, SkyTimeline.FIRST_CRACK, SkyTimeline.FIRST_CRACK + 26);
            return new double[]{0.03 + 0.9 * (1 - Math.pow(1 - p, 3)), (1 - p) * (1 - p), 0.035 + 0.06 * p};
        }
        return null;
    }

    /** Rainbow light leaks while glass flies around (and as it flies back). */
    static double leaks(double t, double r) {
        double fall = r > SkyTimeline.FALL ? SkyScene.smooth(SkyTimeline.progress(t, SkyTimeline.FALL, SkyTimeline.FALL + 20))
                * (1 - SkyScene.smooth(SkyTimeline.progress(t, SkyTimeline.FREEZE, SkyTimeline.FREEZE + 60))) : 0;
        double back = 0;
        if (t >= r) {
            double p = SkyScene.restoreProgress(t, r);
            back = 0.6 * SkyScene.smoothstep(0.1, 0.3, p) * (1 - SkyScene.smoothstep(0.55, 0.85, p));
        }
        return Math.max(fall, back);
    }

    // ------------------------------------------------------------------ rendering

    static void render(RenderLevelStageEvent event, Iterable<ClientSkyEvent> events, float partial) {
        Minecraft mc = Minecraft.getInstance();
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        double darken = 0, desat = 0, stop = 0, leaks = 0;
        double flare = 0, fx = 0.5, fy = 0.5, ringR = 0, ringI = 0, ringW = 0.04, ghosts = 0;
        double secs = (System.nanoTime() % 1_000_000_000_000L) / 1e9;
        for (ClientSkyEvent sky : events) {
            SkyScene scene = sky.scene();
            if (scene == null) continue;
            double t = sky.time(partial), r = sky.restoreAt();
            if (t < 0 || sky.finished(t)) continue;
            double d = cam.distanceTo(new Vec3(sky.state.cx(), sky.state.cy(), sky.state.cz()));
            double reach = 1 - SkyScene.smoothstep(220, 320, d);
            if (reach <= 0) continue;
            darken = Math.max(darken, darken(t, r) * reach);
            desat = Math.max(desat, desaturate(t, r) * reach);
            stop = Math.max(stop, scene.timeStop(t, r) * reach);
            leaks = Math.max(leaks, leaks(t, r) * (1 - SkyScene.smoothstep(70, 170, d)));

            // The light comes from the impact while the glass breaks, then from the rift's heart.
            V3 impact = sky.toWorld(DomeShape.pointAt(scene.fracture.impact, 0));
            V3 heart = sky.riftHeart();
            double k = SkyScene.smooth(SkyTimeline.progress(t, SkyTimeline.FALL - 20, SkyTimeline.FALL + 20));
            double sx = sky.state.cx() + impact.x() + (heart.x() - impact.x()) * k;
            double sy = sky.state.cy() + impact.y() + (heart.y() - impact.y()) * k;
            double sz = sky.state.cz() + impact.z() + (heart.z() - impact.z()) * k;
            double f = flare(t, r) * reach;
            double[] ring = ring(t, r);
            if (f <= 0.002 && ring == null) continue;
            float[] screen = project(event.getModelViewMatrix(), event.getProjectionMatrix(), sx - cam.x, sy - cam.y, sz - cam.z);
            double vis = visibility(sky.id, mc, cam, new Vec3(sx, sy, sz), screen != null);
            if (screen == null) continue;
            double edge = Math.min(Math.min(screen[0], 1 - screen[0]), Math.min(screen[1], 1 - screen[1]));
            double onScreen = SkyScene.smoothstep(-0.35, 0.05, edge);
            double strength = f * vis * onScreen;
            if (strength >= flare || ring != null) {
                flare = Math.max(flare, strength);
                fx = screen[0];
                fy = screen[1];
                ghosts = Math.pow(Math.min(1.0, strength), 2) * SkyScene.smoothstep(-0.05, 0.1, edge);
                if (ring != null) {
                    ringR = ring[0];
                    ringI = ring[1] * reach * (0.4 + 0.6 * vis);
                    ringW = ring[2];
                }
            }
        }
        double pull = ClientSkyEvents.INSTANCE.pullWarp(partial);
        if (darken + desat + stop + flare + ringI + leaks + pull < 0.003) {
            return;
        }
        ShaderInstance shader = ModShaders.cinema;
        float aspect = mc.getWindow().getWidth() / (float) Math.max(1, mc.getWindow().getHeight());
        shader.safeGetUniform("Grade").set((float) darken, (float) desat, (float) stop, (float) (0.35 * desat));
        shader.safeGetUniform("Flare").set((float) fx, (float) fy, (float) flare, aspect);
        shader.safeGetUniform("Ring").set((float) ringR, (float) ringI, (float) ringW, (float) (secs * 0.7));
        shader.safeGetUniform("Fx").set((float) (ghosts * 0.8), (float) leaks, (float) pull, (float) (secs % 3600));
        int tex = SceneCopy.capture();
        RenderSystem.setShaderTexture(0, tex);
        RenderSystem.setShader(() -> shader);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_TEX);
        buf.addVertex(-1f, -1f, 0f).setUv(0f, 0f);
        buf.addVertex(1f, -1f, 0f).setUv(1f, 0f);
        buf.addVertex(-1f, 1f, 0f).setUv(0f, 1f);
        buf.addVertex(1f, 1f, 0f).setUv(1f, 1f);
        MeshData mesh = buf.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
    }

    /** Screen position (uv, origin bottom-left like the frame copy) of a camera-relative point, or null behind the camera. */
    private static float[] project(Matrix4f modelView, Matrix4f projection, double x, double y, double z) {
        CLIP.set((float) x, (float) y, (float) z, 1f);
        modelView.transform(CLIP);
        projection.transform(CLIP);
        if (CLIP.w <= 0.05f) {
            return null;
        }
        return new float[]{CLIP.x / CLIP.w * 0.5f + 0.5f, CLIP.y / CLIP.w * 0.5f + 0.5f};
    }

    /** Whether terrain hides the light source, eased so the flare fades instead of popping. */
    private static double visibility(int id, Minecraft mc, Vec3 cam, Vec3 source, boolean inFront) {
        double target = 0;
        if (inFront && mc.level != null) {
            Vec3 to = cam.add(source.subtract(cam).normalize().scale(Math.min(180, cam.distanceTo(source))));
            var hit = mc.level.clip(new ClipContext(cam, to, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
            target = hit.getType() == HitResult.Type.MISS ? 1 : 0;
        }
        double v = VISIBLE.getOrDefault(id, target);
        v += (target - v) * 0.18;
        VISIBLE.put(id, v);
        return v;
    }

    static void forget(int id) {
        VISIBLE.remove(id);
    }

    // ------------------------------------------------------------------ letterbox

    /** Black bars, drawn under the HUD. */
    static void letterbox(RenderGuiEvent.Pre event) {
        double amount = ClientSkyEvents.INSTANCE.letterbox(event.getPartialTick().getGameTimeDeltaPartialTick(false));
        if (amount <= 0.002) {
            return;
        }
        GuiGraphics g = event.getGuiGraphics();
        int bar = (int) Math.round(g.guiHeight() * 0.11 * amount);
        if (bar <= 0) {
            return;
        }
        g.fill(0, 0, g.guiWidth(), bar, 0xFF000000);
        g.fill(0, g.guiHeight() - bar, g.guiWidth(), g.guiHeight(), 0xFF000000);
    }
}
