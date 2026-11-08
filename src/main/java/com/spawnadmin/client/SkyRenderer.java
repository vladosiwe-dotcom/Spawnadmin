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
import com.spawnadmin.sky.geom.DebrisData;
import com.spawnadmin.sky.geom.DomeShape;
import com.spawnadmin.sky.geom.Quat;
import com.spawnadmin.sky.geom.ShardData;
import com.spawnadmin.sky.geom.V2;
import com.spawnadmin.sky.geom.V3;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
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
        drawGlass(sky, base, camera, partial, t, glow, pulse, stop, sceneTex);
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

    /** Per-frame view of the real Minecraft sky, so the glass carries the actual sky and clouds. */
    private static final class SkyView {
        Vec3 camera;
        double cloudY;
        boolean clouds;
        double cloudRange;
        double ticks;
        V3 center;
    }

    private static final SkyView VIEW = new SkyView();

    private static void prepareSky(ClientSkyEvent sky, Camera camera, float partial, ShaderInstance shader) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Vec3 cam = camera.getPosition();
        Vec3 skyColor = level.getSkyColor(cam, partial);
        float[] fog = RenderSystem.getShaderFogColor();
        Vec3 cloudColor = level.getCloudColor(partial);
        float cloudHeight = level.effects().getCloudHeight();
        VIEW.camera = cam;
        VIEW.clouds = !Float.isNaN(cloudHeight) && mc.options.getCloudsType() != CloudStatus.OFF;
        VIEW.cloudY = cloudHeight + 0.33;
        VIEW.cloudRange = mc.options.getEffectiveRenderDistance() * 16.0 * 2.0;
        VIEW.ticks = mc.levelRenderer.getTicks() + partial;
        VIEW.center = new V3(sky.state.cx(), sky.state.cy(), sky.state.cz());
        shader.safeGetUniform("SkyColor").set((float) skyColor.x, (float) skyColor.y, (float) skyColor.z, 1f);
        shader.safeGetUniform("HorizonColor").set(fog[0], fog[1], fog[2], 1f);
        shader.safeGetUniform("CloudColor").set((float) cloudColor.x, (float) cloudColor.y, (float) cloudColor.z, 0.85f);
    }

    /**
     * Emits one glass vertex. {@code rest} is the point's position (relative to the event center)
     * when the shard sat in the dome: the sky colour and the cloud texel are looked up there, so
     * the piece of sky travels with the shard. {@code frozenTicks} > 0 freezes the clouds at the
     * moment the shard broke away.
     */
    private static void glassVertex(BufferBuilder buf, V3 p, V3 n, V3 rest, float edge, float edgeGlow, float alpha,
                                    double frozenTicks, float variation) {
        V3 world = VIEW.center.add(rest);
        double dx = world.x() - VIEW.camera.x, dy = world.y() - VIEW.camera.y, dz = world.z() - VIEW.camera.z;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double dirY = dy / Math.max(len, 1e-6);
        float skyT = (float) SkyScene.smoothstep(-0.02, 0.45, dirY);
        float u = 0, v = 0, vis = 0;
        if (VIEW.clouds && dy > 1e-3) {
            double t = (VIEW.cloudY - VIEW.camera.y) / dy;
            if (t > 0) {
                double hx = VIEW.camera.x + dx * t, hz = VIEW.camera.z + dz * t;
                double horiz = Math.hypot(hx - VIEW.camera.x, hz - VIEW.camera.z);
                vis = (float) (1 - SkyScene.smoothstep(VIEW.cloudRange * 0.55, VIEW.cloudRange, horiz));
                double shift = (frozenTicks > 0 ? frozenTicks : VIEW.ticks) * 0.03;
                u = (float) (((hx + shift) / 12.0) / 256.0 % 1.0);
                v = (float) ((hz / 12.0 + 0.33) / 256.0 % 1.0);
            }
        }
        buf.addVertex((float) p.x(), (float) p.y(), (float) p.z())
                .setColor(edge, edgeGlow, skyT, alpha)
                .setUv(u, v)
                .setUv1((int) (vis * 1000), (int) (variation * 1000))
                .setUv2(0, 0)
                .setNormal((float) n.x(), (float) n.y(), (float) n.z());
    }

    private static void drawGlass(ClientSkyEvent sky, V3 base, Camera camera, float partial, double t, double glow,
                                  double pulse, double stop, int sceneTex) {
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
        prepareSky(sky, camera, partial, shader);
        shader.safeGetUniform("GlassParams").set((float) glow, 0.03f, (float) pulse, (float) stop);
        RenderSystem.setShaderTexture(0, sceneTex);
        RenderSystem.setShaderTexture(1, CLOUDS);
        RenderSystem.setShader(() -> shader);
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
        double restoreAt = sky.restoreAt();
        for (int o = 0; o < count; o++) {
            int i = order[o];
            ShardData s = shards.get(i);
            SkyScene.ShardPose pose = sky.poses[i];
            double free = pose.free;
            // Sealed glass is almost invisible against the sky it shows; free shards are solid sky.
            double alpha = pose.alpha * Math.max(glow * 0.6 * rimFade(s.centroid2), free * 0.97);
            if (alpha <= 0.003) {
                continue;
            }
            double edgeGlow = Math.min(1.0, free * 0.85 + pose.flash * 1.5);
            // Clouds on a free shard stop where they were at the moment it broke away.
            double broke = SkyTimeline.FALL + s.detachDelay * 20;
            double frozen = free > 0 && t > broke ? VIEW.ticks - (t - broke) : 0;
            emitShard(buf, s, pose, base, (float) alpha, (float) edgeGlow, frozen);
        }
        drawDebris(sky, base, t, restoreAt, buf);
        draw(buf);
    }

    private static void emitShard(BufferBuilder buf, ShardData s, SkyScene.ShardPose pose, V3 base, float alpha,
                                  float edgeGlow, double frozen) {
        Quat q = pose.rotation;
        V3 center = base.add(s.origin).add(pose.offset);
        double h = s.thickness * 0.5;
        V3 nOut = q.rotate(s.normal);
        V3 nIn = nOut.mul(-1);
        V3 cOutRest = s.origin.add(s.normal.mul(h));
        V3 cInRest = s.origin.add(s.normal.mul(-h));
        V3 cOut = center.add(q.rotate(s.normal.mul(h)));
        V3 cIn = center.add(q.rotate(s.normal.mul(-h)));
        float var = (float) s.tint;
        int n = s.outline.length;
        V3[] out = new V3[n], in = new V3[n], outRest = new V3[n], inRest = new V3[n];
        for (int k = 0; k < n; k++) {
            out[k] = center.add(q.rotate(s.outer[k]));
            in[k] = center.add(q.rotate(s.inner[k]));
            outRest[k] = s.origin.add(s.outer[k]);
            inRest[k] = s.origin.add(s.inner[k]);
        }
        for (int k = 0; k < n; k++) {
            int k2 = (k + 1) % n;
            glassVertex(buf, cOut, nOut, cOutRest, 0f, edgeGlow, alpha, frozen, var);
            glassVertex(buf, out[k], nOut, outRest[k], 1f, edgeGlow, alpha, frozen, var);
            glassVertex(buf, out[k2], nOut, outRest[k2], 1f, edgeGlow, alpha, frozen, var);

            glassVertex(buf, cIn, nIn, cInRest, 0f, edgeGlow, alpha, frozen, var);
            glassVertex(buf, in[k2], nIn, inRest[k2], 1f, edgeGlow, alpha, frozen, var);
            glassVertex(buf, in[k], nIn, inRest[k], 1f, edgeGlow, alpha, frozen, var);

            if (out[k2].sub(out[k]).length() < 1e-4) {
                continue; // collinear vertex from a neighbour's cut: no wall needed
            }
            // Side wall: the broken cut face of the pane.
            V3 side = out[k2].sub(out[k]).cross(in[k].sub(out[k])).normalize();
            glassVertex(buf, out[k], side, outRest[k], 1f, edgeGlow, alpha, frozen, var);
            glassVertex(buf, out[k2], side, outRest[k2], 1f, edgeGlow, alpha, frozen, var);
            glassVertex(buf, in[k2], side, inRest[k2], 1f, edgeGlow, alpha, frozen, var);
            glassVertex(buf, out[k], side, outRest[k], 1f, edgeGlow, alpha, frozen, var);
            glassVertex(buf, in[k2], side, inRest[k2], 1f, edgeGlow, alpha, frozen, var);
            glassVertex(buf, in[k], side, inRest[k], 1f, edgeGlow, alpha, frozen, var);
        }
    }

    /** Tiny splinters: double-sided sharp triangles that glint as they tumble. */
    private static void drawDebris(ClientSkyEvent sky, V3 base, double t, double restoreAt, BufferBuilder buf) {
        var debris = sky.scene.fracture.debris;
        SkyScene.ShardPose pose = DEBRIS_POSE;
        for (int i = 0; i < debris.size(); i++) {
            DebrisData d = debris.get(i);
            sky.scene.debrisPose(d, t, restoreAt, pose);
            if (pose.alpha <= 0.01) {
                continue;
            }
            Quat q = pose.rotation;
            V3 c = base.add(d.origin).add(pose.offset);
            V3 nrm = q.rotate(d.normal);
            double frozen = VIEW.ticks - (t - (SkyTimeline.FALL + d.detachDelay * 20));
            float a = (float) (pose.alpha * 0.95);
            V3 p0 = c.add(q.rotate(d.tri[0])), p1 = c.add(q.rotate(d.tri[1])), p2 = c.add(q.rotate(d.tri[2]));
            V3 r0 = d.origin.add(d.tri[0]), r1 = d.origin.add(d.tri[1]), r2 = d.origin.add(d.tri[2]);
            glassVertex(buf, p0, nrm, r0, 1f, 0.9f, a, frozen, 0.5f);
            glassVertex(buf, p1, nrm, r1, 0.6f, 0.9f, a, frozen, 0.5f);
            glassVertex(buf, p2, nrm, r2, 0.6f, 0.9f, a, frozen, 0.5f);
        }
    }

    private static final SkyScene.ShardPose DEBRIS_POSE = new SkyScene.ShardPose();

    /** The sealed shell dissolves towards its rim, so the dome melts into the real horizon. */
    private static double rimFade(V2 disc) {
        return 1.0 - SkyScene.smoothstep(0.72, 0.98, disc.length());
    }
    private static final ResourceLocation CLOUDS = ResourceLocation.withDefaultNamespace("textures/environment/clouds.png");

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
            float intensity = (float) ((c.hairline() ? 0.55 : 1.0) * (pose != null && pose.free > 0 ? 1.0 : rimFade(c.a().lerp(c.b(), 0.5))));
            if (intensity <= 0.01f) {
                continue;
            }
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
        RenderSystem.setShader(() -> shader);
        // Pass 1: solid pale body with a cyan rim (visible against the bright day sky).
        shader.safeGetUniform("HandParams").set((float) HAND.intensity, 1f, 0f, 0f);
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        emitHand(base);
        // Pass 2: additive energy glow with flowing streaks.
        shader.safeGetUniform("HandParams").set((float) HAND.intensity, 0f, 0f, 0f);
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
        emitHand(base);
    }

    private static void emitHand(V3 base) {
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

}
