package com.spawnadmin.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.spawnadmin.sky.HandRig;
import com.spawnadmin.sky.SkyScene;
import com.spawnadmin.sky.SkyTimeline;
import com.spawnadmin.sky.geom.CrackSegment;
import com.spawnadmin.sky.geom.DomeShape;
import com.spawnadmin.sky.geom.Quat;
import com.spawnadmin.sky.geom.ShardData;
import com.spawnadmin.sky.geom.V2;
import com.spawnadmin.sky.geom.V3;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.lwjgl.opengl.GL11;

import java.util.Arrays;

/**
 * Draws the shattering glass sky. Everything is immediate-mode geometry built from the shared
 * {@link SkyScene}; there are no entities, no block changes and no edits to the world sky.
 *
 * <p>Order per event (after weather, so it sits in front of the real sky and terrain):</p>
 * <ol>
 *   <li>windows into the other reality where shards have broken free,</li>
 *   <li>copy of the frame, then the glass shards sampling it for refraction (back to front),</li>
 *   <li>branching cracks, additive,</li>
 *   <li>the hand of light, additive.</li>
 * </ol>
 * Time stop is a fullscreen pass after the whole level.
 */
final class SkyRenderer {
    private static final double CRACK_LIFT = -DomeShape.THICKNESS * 0.5 - 0.04;
    private static final double RIFT_LIFT = DomeShape.THICKNESS * 0.5 + 0.35;
    /** Longest straight piece of a crack before it is split to follow the dome's curvature. */
    private static final double CRACK_PIECE = 3.5;

    private static Integer[] order = new Integer[0];
    private static double[] depth = new double[0];

    private SkyRenderer() {
    }

    static void onRenderStage(RenderLevelStageEvent event) {
        if (!ModShaders.ready()) {
            return;
        }
        var events = ClientSkyEvents.INSTANCE.all();
        if (events.isEmpty()) {
            return;
        }
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            Camera camera = event.getCamera();
            float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            for (ClientSkyEvent sky : events) {
                renderEvent(sky, camera, partial);
            }
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            double stop = 0;
            Vec3 cam = event.getCamera().getPosition();
            for (ClientSkyEvent sky : events) {
                double t = sky.time(partial);
                double d = cam.distanceTo(new Vec3(sky.state.cx(), sky.state.cy(), sky.state.cz()));
                double reach = 1 - SkyScene.smoothstep(220, 320, d);
                stop = Math.max(stop, sky.scene.timeStop(t, sky.restoreAt()) * reach);
            }
            if (stop > 0.002) {
                renderTimeStop((float) stop);
            }
        }
    }

    // ================================================================== per event

    private static void renderEvent(ClientSkyEvent sky, Camera camera, float partial) {
        double t = sky.time(partial);
        double r = sky.restoreAt();
        if (t < 0 || sky.finished(t)) {
            return;
        }
        Vec3 cam = camera.getPosition();
        V3 base = new V3(sky.state.cx() - cam.x, sky.state.cy() - cam.y, sky.state.cz() - cam.z);
        SkyScene scene = sky.scene;
        sky.evaluate(t);

        double glow = scene.shellGlow(t, r);
        double pulse = scene.skyPulse(t, r);
        double stop = scene.timeStop(t, r);
        if (glow <= 0.001 && !anyFree(sky)) {
            return;
        }

        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();

        drawRift(sky, base, glow, t);
        int sceneTex = SceneCopy.capture();
        drawGlass(sky, base, glow, pulse, stop, sceneTex);
        drawCracks(sky, base, t, r, pulse);
        drawHand(sky, base, t, r);

        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
    }

    private static boolean anyFree(ClientSkyEvent sky) {
        for (double f : sky.free) {
            if (f > 0) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ rift windows

    private static void drawRift(ClientSkyEvent sky, V3 base, double glow, double t) {
        if (!anyFree(sky)) {
            return;
        }
        ShaderInstance shader = ModShaders.rift;
        V3 heart = base.add(HandRig.riftHeart());
        shader.safeGetUniform("RiftCenter").set((float) heart.x(), (float) heart.y(), (float) heart.z(), 1.0f);
        RenderSystem.setShader(() -> shader);
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL);
        for (ShardData s : sky.scene.fracture.shards) {
            double open = sky.free[s.index];
            if (open <= 0.002) {
                continue;
            }
            V3 c = base.add(DomeShape.pointAt(s.centroid2, RIFT_LIFT));
            int n = s.outline.length;
            for (int k = 0; k < n; k++) {
                V3 a = base.add(DomeShape.pointAt(s.outline[k], RIFT_LIFT));
                V3 b = base.add(DomeShape.pointAt(s.outline[(k + 1) % n], RIFT_LIFT));
                riftVertex(buf, c, s.normal, open);
                riftVertex(buf, a, s.normal, open);
                riftVertex(buf, b, s.normal, open);
            }
        }
        draw(buf);
    }

    private static void riftVertex(BufferBuilder buf, V3 p, V3 n, double alpha) {
        buf.addVertex((float) p.x(), (float) p.y(), (float) p.z()).setUv(0, 0)
                .setColor(1f, 1f, 1f, (float) alpha).setNormal((float) n.x(), (float) n.y(), (float) n.z());
    }

    // ------------------------------------------------------------------ glass

    private static void drawGlass(ClientSkyEvent sky, V3 base, double glow, double pulse, double stop, int sceneTex) {
        var shards = sky.scene.fracture.shards;
        int count = shards.size();
        if (order.length < count) {
            order = new Integer[count];
            depth = new double[count];
        }
        for (int i = 0; i < count; i++) {
            order[i] = i;
            V3 p = base.add(shards.get(i).origin).add(sky.poses[i].offset);
            depth[i] = p.dot(p);
        }
        Arrays.sort(order, 0, count, (a, b) -> Double.compare(depth[b], depth[a]));

        ShaderInstance shader = ModShaders.glass;
        shader.safeGetUniform("GlassParams").set((float) glow, 0.035f, (float) pulse, (float) stop);
        RenderSystem.setShaderTexture(0, sceneTex);
        RenderSystem.setShader(() -> shader);
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL);
        for (int o = 0; o < count; o++) {
            int i = order[o];
            ShardData s = shards.get(i);
            SkyScene.ShardPose pose = sky.poses[i];
            double free = pose.free;
            double alpha = pose.alpha * Math.max(glow, free) * (0.62 + 0.25 * free);
            if (alpha <= 0.003) {
                continue;
            }
            double edgeGlow = Math.min(1.0, free * 0.9 + pose.flash * 1.5);
            emitShard(buf, s, pose, base, (float) alpha, (float) edgeGlow);
        }
        draw(buf);
    }

    private static void emitShard(BufferBuilder buf, ShardData s, SkyScene.ShardPose pose, V3 base, float alpha, float edgeGlow) {
        Quat q = pose.rotation;
        V3 center = base.add(s.origin).add(pose.offset);
        double h = DomeShape.THICKNESS * 0.5;
        V3 nOut = q.rotate(s.normal);
        V3 cOut = center.add(q.rotate(s.normal.mul(h)));
        V3 cIn = center.add(q.rotate(s.normal.mul(-h)));
        // Subtle per-shard tint so neighbouring panes catch the light differently.
        float tr = (float) (0.80 + 0.12 * s.tint);
        float tg = (float) (0.90 + 0.06 * s.tint);
        float tb = 1.0f;
        int n = s.outline.length;
        V3[] out = new V3[n];
        V3[] in = new V3[n];
        for (int k = 0; k < n; k++) {
            out[k] = center.add(q.rotate(s.outer[k]));
            in[k] = center.add(q.rotate(s.inner[k]));
        }
        V3 nIn = nOut.mul(-1);
        for (int k = 0; k < n; k++) {
            int k2 = (k + 1) % n;
            glassVertex(buf, cOut, nOut, 0f, edgeGlow, tr, tg, tb, alpha);
            glassVertex(buf, out[k], nOut, 1f, edgeGlow, tr, tg, tb, alpha);
            glassVertex(buf, out[k2], nOut, 1f, edgeGlow, tr, tg, tb, alpha);

            glassVertex(buf, cIn, nIn, 0f, edgeGlow, tr, tg, tb, alpha);
            glassVertex(buf, in[k2], nIn, 1f, edgeGlow, tr, tg, tb, alpha);
            glassVertex(buf, in[k], nIn, 1f, edgeGlow, tr, tg, tb, alpha);

            // Side wall: the visible thickness of the pane, always fully lit at the rim.
            V3 side = out[k2].sub(out[k]).cross(in[k].sub(out[k])).normalize();
            glassVertex(buf, out[k], side, 1f, edgeGlow, tr, tg, tb, alpha);
            glassVertex(buf, out[k2], side, 1f, edgeGlow, tr, tg, tb, alpha);
            glassVertex(buf, in[k2], side, 1f, edgeGlow, tr, tg, tb, alpha);
            glassVertex(buf, out[k], side, 1f, edgeGlow, tr, tg, tb, alpha);
            glassVertex(buf, in[k2], side, 1f, edgeGlow, tr, tg, tb, alpha);
            glassVertex(buf, in[k], side, 1f, edgeGlow, tr, tg, tb, alpha);
        }
    }

    private static void glassVertex(BufferBuilder buf, V3 p, V3 n, float edge, float edgeGlow,
                                    float r, float g, float b, float a) {
        buf.addVertex((float) p.x(), (float) p.y(), (float) p.z()).setUv(edge, edgeGlow)
                .setColor(r, g, b, a).setNormal((float) n.x(), (float) n.y(), (float) n.z());
    }

    // ------------------------------------------------------------------ cracks

    private static void drawCracks(ClientSkyEvent sky, V3 base, double t, double r, double pulse) {
        double front = sky.scene.crackFront(t, r);
        if (front <= 0) {
            return;
        }
        double fade = sky.scene.shellGlow(t, r) > 0 ? 1.0 : 0.0;
        ShaderInstance shader = ModShaders.crack;
        shader.safeGetUniform("CrackParams").set((float) (fade * (1.0 + pulse * 0.8)), 0f, 0f, 0f);
        RenderSystem.setShader(() -> shader);
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL);
        var shards = sky.scene.fracture.shards;
        for (CrackSegment c : sky.scene.fracture.cracks) {
            if (!sky.scene.crackOnShell(c, sky.free)) {
                continue;
            }
            double[] range = c.visibleRange(front);
            ShardData owner = c.hairline() ? shards.get(c.cellA()) : null;
            SkyScene.ShardPose pose = owner == null ? null : sky.poses[owner.index];
            if (pose != null && pose.alpha <= 0.01) {
                continue;
            }
            float intensity = c.hairline() ? 0.55f : 1.0f;
            for (int k = 0; k + 1 < range.length; k += 2) {
                if (range[k + 1] - range[k] > 1e-4) {
                    emitCrack(buf, c, range[k], range[k + 1], owner, pose, base, intensity);
                }
            }
        }
        draw(buf);
    }

    private static void emitCrack(BufferBuilder buf, CrackSegment c, double f0, double f1, ShardData owner,
                                  SkyScene.ShardPose pose, V3 base, float intensity) {
        V2 a = c.a().lerp(c.b(), f0);
        V2 b = c.a().lerp(c.b(), f1);
        double len = a.dist(b) * DomeShape.blocksPerDiscUnit();
        int pieces = Math.max(1, (int) Math.ceil(len / CRACK_PIECE));
        double width = c.width() * 2.2;
        V3 prev = crackPoint(a, owner, pose, base);
        for (int i = 1; i <= pieces; i++) {
            V3 next = crackPoint(a.lerp(b, i / (double) pieces), owner, pose, base);
            V3 dir = next.sub(prev);
            V3 mid = prev.add(next).mul(0.5);
            V3 across = dir.cross(mid).normalize().mul(width);
            V3 n = mid.mul(-1).normalize();
            crackVertex(buf, prev.sub(across), n, -1, intensity);
            crackVertex(buf, prev.add(across), n, 1, intensity);
            crackVertex(buf, next.add(across), n, 1, intensity);
            crackVertex(buf, prev.sub(across), n, -1, intensity);
            crackVertex(buf, next.add(across), n, 1, intensity);
            crackVertex(buf, next.sub(across), n, -1, intensity);
            prev = next;
        }
    }

    private static V3 crackPoint(V2 p, ShardData owner, SkyScene.ShardPose pose, V3 base) {
        V3 onDome = DomeShape.pointAt(p, CRACK_LIFT);
        if (owner == null || pose == null || pose.free <= 0) {
            return base.add(onDome);
        }
        V3 local = onDome.sub(owner.origin);
        return base.add(owner.origin).add(pose.offset).add(pose.rotation.rotate(local));
    }

    private static void crackVertex(BufferBuilder buf, V3 p, V3 n, float across, float intensity) {
        buf.addVertex((float) p.x(), (float) p.y(), (float) p.z()).setUv(across, intensity)
                .setColor(0.82f, 0.94f, 1.0f, 1.0f).setNormal((float) n.x(), (float) n.y(), (float) n.z());
    }

    // ------------------------------------------------------------------ hand

    private static final HandRig.Pose HAND = new HandRig.Pose();

    private static void drawHand(ClientSkyEvent sky, V3 base, double t, double r) {
        if (!sky.state.locked()) {
            return;
        }
        if (!HandRig.pose(t, r, sky.lockLocal(), sky.state.lockYaw(), HAND)) {
            return;
        }
        ShaderInstance shader = ModShaders.hand;
        shader.safeGetUniform("HandParams").set((float) HAND.intensity, 0f, 0f, 0f);
        RenderSystem.setShader(() -> shader);
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL);
        HandRig.mesh(HAND, (pos, n, along, fade) -> {
            V3 p = base.add(pos);
            buf.addVertex((float) p.x(), (float) p.y(), (float) p.z()).setUv((float) along, (float) fade)
                    .setColor(0.96f, 0.98f, 1.0f, 1.0f).setNormal((float) n.x(), (float) n.y(), (float) n.z());
        });
        draw(buf);
    }

    // ------------------------------------------------------------------ time stop

    private static void renderTimeStop(float strength) {
        int tex = SceneCopy.capture();
        ShaderInstance shader = ModShaders.timeStop;
        shader.safeGetUniform("StopParams").set(strength, 0f, 0f, 0f);
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
        draw(buf);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
    }

    private static void draw(BufferBuilder buf) {
        MeshData mesh = buf.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }
    }

    static Minecraft mc() {
        return Minecraft.getInstance();
    }
}
