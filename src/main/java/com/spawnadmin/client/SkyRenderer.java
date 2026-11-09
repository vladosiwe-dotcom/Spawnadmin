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
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.lwjgl.opengl.GL11;

import java.util.Arrays;

/**
 * Draws the breaking sky. Everything is immediate-mode geometry built from the shared
 * {@link SkyScene}; there are no entities, no block changes and no edits to the world sky.
 * Hot loops (shards, debris, cracks) use primitive math and reused scratch arrays, so a frame
 * does not allocate per vertex.
 *
 * <p>Order per event (after weather, so it sits in front of the real sky and terrain):</p>
 * <ol>
 *   <li>windows into the other reality where shards have broken free,</li>
 *   <li>light shafts and the glow of the rift's heart, additive,</li>
 *   <li>copy of the frame, then the glass shards and debris sampling it (back to front),</li>
 *   <li>the growing crack network, additive,</li>
 *   <li>the hand of light.</li>
 * </ol>
 * Time stop is a fullscreen pass after the whole level.
 */
final class SkyRenderer {
    private static final double CRACK_LIFT = -DomeShape.THICKNESS * 0.5 - 0.04;
    private static final double RIFT_LIFT = DomeShape.THICKNESS * 0.5 + 0.35;
    /** Longest straight piece of a crack: short pieces let the line wander like a real crack. */
    private static final double CRACK_PIECE = 2.0;
    private static final ResourceLocation CLOUDS = ResourceLocation.withDefaultNamespace("textures/environment/clouds.png");

    private static Integer[] order = new Integer[0];
    private static double[] depth = new double[0];
    // scratch buffers (render thread only)
    private static final double[] A = new double[3], B = new double[3], C = new double[3];
    private static double[] outX = new double[32], outY = new double[32], outZ = new double[32];
    private static double[] inX = new double[32], inY = new double[32], inZ = new double[32];
    private static double[] roX = new double[32], roY = new double[32], roZ = new double[32];
    private static double[] riX = new double[32], riY = new double[32], riZ = new double[32];
    private static double[] lu = new double[32], lv = new double[32];
    private static final SkyScene.ShardPose DEBRIS_POSE = new SkyScene.ShardPose();
    private static final HandRig.Pose HAND = new HandRig.Pose();

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
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            for (ClientSkyEvent sky : events) {
                if (sky.scene() != null) {
                    renderEvent(sky, event.getCamera(), partial);
                }
            }
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            double stop = 0;
            Vec3 cam = event.getCamera().getPosition();
            for (ClientSkyEvent sky : events) {
                if (sky.scene() == null) continue;
                double t = sky.time(partial);
                double d = cam.distanceTo(new Vec3(sky.state.cx(), sky.state.cy(), sky.state.cz()));
                double reach = 1 - SkyScene.smoothstep(220, 320, d);
                stop = Math.max(stop, sky.scene().timeStop(t, sky.restoreAt()) * reach);
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
        double bx = sky.state.cx() - cam.x, by = sky.state.cy() - cam.y, bz = sky.state.cz() - cam.z;
        SkyScene scene = sky.scene();
        sky.evaluate(t);

        double glow = scene.shellGlow(t, r);
        double pulse = scene.skyPulse(t, r);
        double stop = scene.timeStop(t, r);

        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();

        V3 heart = sky.riftHeart();
        double hx = bx + heart.x(), hy = by + heart.y(), hz = bz + heart.z();
        drawRift(sky, bx, by, bz, hx, hy, hz, scene.riftBrightness(t, r));
        drawLight(sky, bx, by, bz, hx, hy, hz, t, scene.beams(t, r), scene.riftBrightness(t, r));
        int sceneTex = SceneCopy.capture();
        drawGlass(sky, bx, by, bz, camera, partial, t, glow, pulse, stop, sceneTex);
        drawCracks(sky, bx, by, bz, t, r);
        drawHand(sky, bx, by, bz, t, r);

        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
    }

    // ------------------------------------------------------------------ rift windows

    private static void drawRift(ClientSkyEvent sky, double bx, double by, double bz, double hx, double hy, double hz,
                                 double brightness) {
        boolean any = false;
        for (double f : sky.free) {
            if (f > 0.002) {
                any = true;
                break;
            }
        }
        if (!any) {
            return;
        }
        ShaderInstance shader = ModShaders.rift;
        shader.safeGetUniform("RiftCenter").set((float) hx, (float) hy, (float) hz, (float) brightness);
        RenderSystem.setShader(() -> shader);
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL);
        for (ShardData s : sky.scene().fracture.shards) {
            float open = (float) sky.free[s.index];
            if (open <= 0.002f) {
                continue;
            }
            DomeShape.pointAtInto(s.centroid2.x(), s.centroid2.y(), RIFT_LIFT, C);
            sky.toWorld(C);
            int n = s.outline.length;
            for (int k = 0; k < n; k++) {
                V2 p = s.outline[k], q = s.outline[(k + 1) % n];
                DomeShape.pointAtInto(p.x(), p.y(), RIFT_LIFT, A);
                DomeShape.pointAtInto(q.x(), q.y(), RIFT_LIFT, B);
                sky.toWorld(A);
                sky.toWorld(B);
                simpleVertex(buf, bx + C[0], by + C[1], bz + C[2], 0, 0, 1f, 1f, 1f, open);
                simpleVertex(buf, bx + A[0], by + A[1], bz + A[2], 0, 0, 1f, 1f, 1f, open);
                simpleVertex(buf, bx + B[0], by + B[1], bz + B[2], 0, 0, 1f, 1f, 1f, open);
            }
        }
        draw(buf);
    }

    private static void simpleVertex(BufferBuilder buf, double x, double y, double z, float u, float v,
                                     float r, float g, float b, float a) {
        buf.addVertex((float) x, (float) y, (float) z).setUv(u, v).setColor(r, g, b, a).setNormal(0f, 1f, 0f);
    }

    // ------------------------------------------------------------------ light shafts

    /**
     * Volumetric light: a soft glow around the rift's heart and shafts of light pouring out of
     * the opening towards the ground, fanned around the direction of the scene.
     */
    private static void drawLight(ClientSkyEvent sky, double bx, double by, double bz, double hx, double hy, double hz,
                                  double t, double beams, double brightness) {
        if (beams <= 0.003 && brightness <= 0.3) {
            return;
        }
        ShaderInstance shader = ModShaders.beam;
        RenderSystem.setShader(() -> shader);
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);

        // Glow sprite facing the camera.
        // Kept moderate: the white hand must stay readable in front of it.
        shader.safeGetUniform("BeamParams").set((float) Math.min(0.9, brightness * 0.7), 1f, 0f, 0f);
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL);
        double len = Math.sqrt(hx * hx + hy * hy + hz * hz);
        double vx = hx / len, vy = hy / len, vz = hz / len;
        double rx = vz, rz = -vx, rl = Math.hypot(rx, rz);
        rx /= rl;
        rz /= rl;
        double ux = vy * rz, uy = vz * rx - vx * rz, uz = -vy * rx; // up = view x right (ry = 0)
        double size = 16 + 24 * Math.min(1.3, brightness);
        quad(buf, hx, hy, hz, rx * size, 0, rz * size, ux * size, uy * size, uz * size, 1f, 0.97f, 0.88f, 1f);
        draw(buf);

        if (beams <= 0.003) {
            return;
        }
        shader.safeGetUniform("BeamParams").set((float) beams, 0f, 0f, 0f);
        buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL);
        // Down axis: from the heart towards the scene below it.
        double ax = bx - hx, ay = by - hy, az = bz - hz;
        double al = Math.sqrt(ax * ax + ay * ay + az * az);
        ax /= al;
        ay /= al;
        az /= al;
        double p1x = -az, p1z = ax, p1l = Math.hypot(p1x, p1z);
        p1x /= p1l;
        p1z /= p1l;
        double p2x = ay * p1z, p2y = az * p1x - ax * p1z, p2z = -ay * p1x;
        int count = 14;
        for (int i = 0; i < count; i++) {
            double a = i * 2.39996 + 0.15 * Math.sin(t * 0.01 + i);
            double spread = 0.18 + 0.42 * ((i * 0.618) % 1.0);
            double dx = ax + (p1x * Math.cos(a) + p2x * Math.sin(a)) * spread;
            double dy = ay + (p2y * Math.sin(a)) * spread;
            double dz = az + (p1z * Math.cos(a) + p2z * Math.sin(a)) * spread;
            double dl = Math.sqrt(dx * dx + dy * dy + dz * dz);
            dx /= dl;
            dy /= dl;
            dz /= dl;
            double length = 150 + 40 * ((i * 0.37) % 1.0);
            double ex = hx + dx * length, ey = hy + dy * length, ez = hz + dz * length;
            double mx = (hx + ex) * 0.5, my = (hy + ey) * 0.5, mz = (hz + ez) * 0.5;
            // across = dir x mid (faces the camera)
            double cx = dy * mz - dz * my, cy = dz * mx - dx * mz, cz = dx * my - dy * mx;
            double cl = Math.sqrt(cx * cx + cy * cy + cz * cz);
            cx /= cl;
            cy /= cl;
            cz /= cl;
            double w0 = 2.5, w1 = 10 + 8 * ((i * 0.71) % 1.0);
            float flicker = (float) (0.55 + 0.45 * Math.sin(t * 0.045 + i * 1.7));
            float rC = 1f, gC = 0.95f, bC = 0.82f;
            beamVertex(buf, hx - cx * w0, hy - cy * w0, hz - cz * w0, -1, 0, rC, gC, bC, flicker);
            beamVertex(buf, hx + cx * w0, hy + cy * w0, hz + cz * w0, 1, 0, rC, gC, bC, flicker);
            beamVertex(buf, ex + cx * w1, ey + cy * w1, ez + cz * w1, 1, 1, rC, gC, bC, flicker);
            beamVertex(buf, hx - cx * w0, hy - cy * w0, hz - cz * w0, -1, 0, rC, gC, bC, flicker);
            beamVertex(buf, ex + cx * w1, ey + cy * w1, ez + cz * w1, 1, 1, rC, gC, bC, flicker);
            beamVertex(buf, ex - cx * w1, ey - cy * w1, ez - cz * w1, -1, 1, rC, gC, bC, flicker);
        }
        draw(buf);
    }

    private static void beamVertex(BufferBuilder buf, double x, double y, double z, float u, float v,
                                   float r, float g, float b, float a) {
        buf.addVertex((float) x, (float) y, (float) z).setUv(u, v).setColor(r, g, b, a).setNormal(0f, 1f, 0f);
    }

    private static void quad(BufferBuilder buf, double cx, double cy, double cz, double rx, double ry, double rz,
                             double ux, double uy, double uz, float r, float g, float b, float a) {
        beamVertex(buf, cx - rx - ux, cy - ry - uy, cz - rz - uz, -1, -1, r, g, b, a);
        beamVertex(buf, cx + rx - ux, cy + ry - uy, cz + rz - uz, 1, -1, r, g, b, a);
        beamVertex(buf, cx + rx + ux, cy + ry + uy, cz + rz + uz, 1, 1, r, g, b, a);
        beamVertex(buf, cx - rx - ux, cy - ry - uy, cz - rz - uz, -1, -1, r, g, b, a);
        beamVertex(buf, cx + rx + ux, cy + ry + uy, cz + rz + uz, 1, 1, r, g, b, a);
        beamVertex(buf, cx - rx + ux, cy - ry + uy, cz - rz + uz, -1, 1, r, g, b, a);
    }

    // ------------------------------------------------------------------ glass

    /** Per-frame view of the real Minecraft sky, so the glass carries the actual sky and clouds. */
    private static double camX, camY, camZ, cloudY, cloudRange, ticks, centerX, centerY, centerZ;
    private static boolean clouds;

    private static void prepareSky(ClientSkyEvent sky, Camera camera, float partial, ShaderInstance shader) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        Vec3 cam = camera.getPosition();
        Vec3 skyColor = level.getSkyColor(cam, partial);
        float[] fog = RenderSystem.getShaderFogColor();
        Vec3 cloudColor = level.getCloudColor(partial);
        float cloudHeight = level.effects().getCloudHeight();
        camX = cam.x;
        camY = cam.y;
        camZ = cam.z;
        clouds = !Float.isNaN(cloudHeight) && mc.options.getCloudsType() != CloudStatus.OFF;
        cloudY = cloudHeight + 0.33;
        cloudRange = mc.options.getEffectiveRenderDistance() * 16.0 * 2.0;
        ticks = mc.levelRenderer.getTicks() + partial;
        centerX = sky.state.cx();
        centerY = sky.state.cy();
        centerZ = sky.state.cz();
        shader.safeGetUniform("SkyColor").set((float) skyColor.x, (float) skyColor.y, (float) skyColor.z, 1f);
        shader.safeGetUniform("HorizonColor").set(fog[0], fog[1], fog[2], 1f);
        shader.safeGetUniform("CloudColor").set((float) cloudColor.x, (float) cloudColor.y, (float) cloudColor.z, 0.85f);
    }

    /**
     * One glass vertex. (rx, ry, rz) is the point's rest position (world axes, relative to the
     * event center) when the shard sat in the dome: sky colour and cloud texel are looked up
     * there, so the piece of sky travels with the shard.
     */
    private static int localU, localV;

    /** Sets the shard-surface coordinate (blocks) for the next glass vertices. */
    private static void local(double u, double v) {
        localU = (int) Math.max(-32000, Math.min(32000, Math.round(u * 100)));
        localV = (int) Math.max(-32000, Math.min(32000, Math.round(v * 100)));
    }

    private static void glassVertex(BufferBuilder buf, double px, double py, double pz, double nx, double ny, double nz,
                                    double rx, double ry, double rz, float edge, float edgeGlow, float alpha,
                                    double frozenTicks, float variation) {
        double dx = centerX + rx - camX, dy = centerY + ry - camY, dz = centerZ + rz - camZ;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        float skyT = (float) SkyScene.smoothstep(-0.02, 0.45, dy / Math.max(len, 1e-6));
        float u = 0, v = 0, vis = 0;
        if (clouds && dy > 1e-3) {
            double k = (cloudY - camY) / dy;
            if (k > 0) {
                double hxw = camX + dx * k, hzw = camZ + dz * k;
                vis = (float) (1 - SkyScene.smoothstep(cloudRange * 0.55, cloudRange, Math.hypot(dx * k, dz * k)));
                double shift = (frozenTicks > 0 ? frozenTicks : ticks) * 0.03;
                u = (float) (((hxw + shift) / 12.0) / 256.0 % 1.0);
                v = (float) ((hzw / 12.0 + 0.33) / 256.0 % 1.0);
            }
        }
        buf.addVertex((float) px, (float) py, (float) pz)
                .setColor(edge, edgeGlow, skyT, alpha)
                .setUv(u, v)
                .setUv1((int) (vis * 1000), (int) (variation * 1000))
                .setUv2(localU, localV)
                .setNormal((float) nx, (float) ny, (float) nz);
    }

    private static void drawGlass(ClientSkyEvent sky, double bx, double by, double bz, Camera camera, float partial,
                                  double t, double glow, double pulse, double stop, int sceneTex) {
        var shards = sky.scene().fracture.shards;
        int count = shards.size();
        if (order.length < count) {
            order = new Integer[count];
            depth = new double[count];
        }
        for (int i = 0; i < count; i++) {
            order[i] = i;
            ShardData s = shards.get(i);
            V3 off = sky.poses[i].offset;
            A[0] = s.origin.x() + off.x();
            A[1] = s.origin.y() + off.y();
            A[2] = s.origin.z() + off.z();
            sky.toWorld(A);
            double x = bx + A[0], y = by + A[1], z = bz + A[2];
            depth[i] = x * x + y * y + z * z;
        }
        Arrays.sort(order, 0, count, (a, b) -> Double.compare(depth[b], depth[a]));

        ShaderInstance shader = ModShaders.glass;
        prepareSky(sky, camera, partial, shader);
        // While the sky is pressed in, the bent glass visibly warps the sky behind it.
        double pressNow = sky.scene().pressure(t, sky.restoreAt());
        shader.safeGetUniform("GlassParams").set((float) glow, (float) (0.03 + 0.14 * pressNow), (float) pulse, (float) stop);
        RenderSystem.setShaderTexture(0, sceneTex);
        RenderSystem.setShaderTexture(1, CLOUDS);
        RenderSystem.setShader(() -> shader);
        RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
        double restoreAt = sky.restoreAt();
        double pressure = sky.scene().pressure(t, restoreAt);
        for (int o = 0; o < count; o++) {
            int i = order[o];
            ShardData s = shards.get(i);
            SkyScene.ShardPose pose = sky.poses[i];
            double free = pose.free;
            // Sealed glass is almost invisible against the sky it shows; free shards are solid sky.
            double alpha = pose.alpha * Math.max(Math.max(glow * 0.6 * rimFade(s.centroid2), free * 0.97),
                    pressVisible(s, pressure) * (1 - free));
            if (alpha <= 0.003) {
                continue;
            }
            double edgeGlow = Math.min(1.0, free * 0.85 + pose.flash * 1.5);
            double broke = SkyTimeline.FALL + s.detachDelay * 20;
            double frozen = free > 0 && t > broke ? ticks - (t - broke) : 0;
            emitShard(buf, sky, s, pose, bx, by, bz, (float) alpha, (float) edgeGlow, frozen, pressure);
        }
        drawDebris(sky, bx, by, bz, t, restoreAt, buf);
        draw(buf);
    }

    private static void ensureScratch(int n) {
        if (outX.length < n) {
            int m = n * 2;
            outX = new double[m]; outY = new double[m]; outZ = new double[m];
            inX = new double[m]; inY = new double[m]; inZ = new double[m];
            roX = new double[m]; roY = new double[m]; roZ = new double[m];
            riX = new double[m]; riY = new double[m]; riZ = new double[m];
            lu = new double[m]; lv = new double[m];
        }
    }

    /** q · v into out (primitive quaternion rotation). */
    private static void rotate(Quat q, double vx, double vy, double vz, double[] out) {
        double tx = 2 * (q.y() * vz - q.z() * vy);
        double ty = 2 * (q.z() * vx - q.x() * vz);
        double tz = 2 * (q.x() * vy - q.y() * vx);
        out[0] = vx + q.w() * tx + (q.y() * tz - q.z() * ty);
        out[1] = vy + q.w() * ty + (q.z() * tx - q.x() * tz);
        out[2] = vz + q.w() * tz + (q.x() * ty - q.y() * tx);
    }

    /** The pressed-in glass shows around the impact before anything breaks. */
    private static double pressVisible(ShardData s, double pressure) {
        double d = s.impactDist / 0.32;
        return 0.95 * Math.min(1, pressure * 1.6) * Math.exp(-d * d);
    }

    private static void emitShard(BufferBuilder buf, ClientSkyEvent sky, ShardData s, SkyScene.ShardPose pose,
                                  double bx, double by, double bz, float alpha, float edgeGlow, double frozen,
                                  double pressure) {
        Quat q = pose.rotation;
        int n = s.outline.length;
        ensureScratch(n);
        double cx = s.origin.x() + pose.offset.x(), cy = s.origin.y() + pose.offset.y(), cz = s.origin.z() + pose.offset.z();
        double h = s.thickness * 0.5;
        double nlx = s.normal.x(), nly = s.normal.y(), nlz = s.normal.z();
        SkyScene scene = sky.scene();
        // The dent: every vertex is pushed inward by the bowl depth at its own disc position (the
        // shape rides along when the shard breaks free, and springs back during the restore).
        double dc = pressure > 0 ? scene.dent(s.centroid2.x(), s.centroid2.y(), pressure) : 0;
        if (pressure > 0) {
            // tilt the face towards the impact, as the bowl's slope does
            double d = s.impactDist, sigma = 0.2;
            double slope = -2 * d / (sigma * sigma) * dc / DomeShape.blocksPerDiscUnit();
            double tx = nlx + s.outward.x() * slope, ty = nly + s.outward.y() * slope, tz = nlz + s.outward.z() * slope;
            double tl = Math.sqrt(tx * tx + ty * ty + tz * tz);
            nlx = tx / tl;
            nly = ty / tl;
            nlz = tz / tl;
        }
        // the shard's own surface frame, for its unique surface detail
        V3 su = s.outward, sv = s.normal.cross(s.outward);
        for (int k = 0; k < n; k++) {
            V3 o0 = s.outer[k], in0 = s.inner[k];
            lu[k] = o0.dot(su);
            lv[k] = o0.dot(sv);
            double dk = pressure > 0 ? scene.dent(s.outline[k].x(), s.outline[k].y(), pressure) : 0;
            V3 o = dk > 0 ? new V3(o0.x() - s.normal.x() * dk, o0.y() - s.normal.y() * dk, o0.z() - s.normal.z() * dk) : o0;
            V3 in = dk > 0 ? new V3(in0.x() - s.normal.x() * dk, in0.y() - s.normal.y() * dk, in0.z() - s.normal.z() * dk) : in0;
            rotate(q, o.x(), o.y(), o.z(), A);
            A[0] += cx; A[1] += cy; A[2] += cz;
            sky.toWorld(A);
            outX[k] = bx + A[0]; outY[k] = by + A[1]; outZ[k] = bz + A[2];
            rotate(q, in.x(), in.y(), in.z(), A);
            A[0] += cx; A[1] += cy; A[2] += cz;
            sky.toWorld(A);
            inX[k] = bx + A[0]; inY[k] = by + A[1]; inZ[k] = bz + A[2];
            A[0] = s.origin.x() + o0.x(); A[1] = s.origin.y() + o0.y(); A[2] = s.origin.z() + o0.z();
            sky.toWorld(A);
            roX[k] = A[0]; roY[k] = A[1]; roZ[k] = A[2];
            A[0] = s.origin.x() + in0.x(); A[1] = s.origin.y() + in0.y(); A[2] = s.origin.z() + in0.z();
            sky.toWorld(A);
            riX[k] = A[0]; riY[k] = A[1]; riZ[k] = A[2];
        }
        // face normals and centres
        rotate(q, nlx, nly, nlz, B);
        sky.toWorld(B);
        double fnx = B[0], fny = B[1], fnz = B[2];
        double sx0 = s.normal.x(), sy0 = s.normal.y(), sz0 = s.normal.z();
        rotate(q, sx0 * (h - dc), sy0 * (h - dc), sz0 * (h - dc), A);
        double cOx = A[0] + cx, cOy = A[1] + cy, cOz = A[2] + cz;
        rotate(q, sx0 * (-h - dc), sy0 * (-h - dc), sz0 * (-h - dc), A);
        double cIx = A[0] + cx, cIy = A[1] + cy, cIz = A[2] + cz;
        C[0] = cOx; C[1] = cOy; C[2] = cOz;
        sky.toWorld(C);
        cOx = bx + C[0]; cOy = by + C[1]; cOz = bz + C[2];
        C[0] = cIx; C[1] = cIy; C[2] = cIz;
        sky.toWorld(C);
        cIx = bx + C[0]; cIy = by + C[1]; cIz = bz + C[2];
        C[0] = s.origin.x() + sx0 * h; C[1] = s.origin.y() + sy0 * h; C[2] = s.origin.z() + sz0 * h;
        sky.toWorld(C);
        double rcOx = C[0], rcOy = C[1], rcOz = C[2];
        C[0] = s.origin.x() - sx0 * h; C[1] = s.origin.y() - sy0 * h; C[2] = s.origin.z() - sz0 * h;
        sky.toWorld(C);
        double rcIx = C[0], rcIy = C[1], rcIz = C[2];
        float var = (float) s.tint;

        for (int k = 0; k < n; k++) {
            int k2 = (k + 1) % n;
            local(0, 0);
            glassVertex(buf, cOx, cOy, cOz, fnx, fny, fnz, rcOx, rcOy, rcOz, 0f, edgeGlow, alpha, frozen, var);
            local(lu[k], lv[k]);
            glassVertex(buf, outX[k], outY[k], outZ[k], fnx, fny, fnz, roX[k], roY[k], roZ[k], 1f, edgeGlow, alpha, frozen, var);
            local(lu[k2], lv[k2]);
            glassVertex(buf, outX[k2], outY[k2], outZ[k2], fnx, fny, fnz, roX[k2], roY[k2], roZ[k2], 1f, edgeGlow, alpha, frozen, var);

            local(0, 0);
            glassVertex(buf, cIx, cIy, cIz, -fnx, -fny, -fnz, rcIx, rcIy, rcIz, 0f, edgeGlow, alpha, frozen, var);
            local(lu[k2], lv[k2]);
            glassVertex(buf, inX[k2], inY[k2], inZ[k2], -fnx, -fny, -fnz, riX[k2], riY[k2], riZ[k2], 1f, edgeGlow, alpha, frozen, var);
            local(lu[k], lv[k]);
            glassVertex(buf, inX[k], inY[k], inZ[k], -fnx, -fny, -fnz, riX[k], riY[k], riZ[k], 1f, edgeGlow, alpha, frozen, var);
            local(lu[k], lv[k]);

            double ex = outX[k2] - outX[k], ey = outY[k2] - outY[k], ez = outZ[k2] - outZ[k];
            if (ex * ex + ey * ey + ez * ez < 1e-8) {
                continue; // collinear vertex from a neighbour's cut: no wall needed
            }
            // Side wall: the broken cut face of the pane.
            double fx = inX[k] - outX[k], fy = inY[k] - outY[k], fz = inZ[k] - outZ[k];
            double sx = ey * fz - ez * fy, sy = ez * fx - ex * fz, sz = ex * fy - ey * fx;
            double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (sl < 1e-9) continue;
            sx /= sl; sy /= sl; sz /= sl;
            glassVertex(buf, outX[k], outY[k], outZ[k], sx, sy, sz, roX[k], roY[k], roZ[k], 1f, edgeGlow, alpha, frozen, var);
            glassVertex(buf, outX[k2], outY[k2], outZ[k2], sx, sy, sz, roX[k2], roY[k2], roZ[k2], 1f, edgeGlow, alpha, frozen, var);
            glassVertex(buf, inX[k2], inY[k2], inZ[k2], sx, sy, sz, riX[k2], riY[k2], riZ[k2], 1f, edgeGlow, alpha, frozen, var);
            glassVertex(buf, outX[k], outY[k], outZ[k], sx, sy, sz, roX[k], roY[k], roZ[k], 1f, edgeGlow, alpha, frozen, var);
            glassVertex(buf, inX[k2], inY[k2], inZ[k2], sx, sy, sz, riX[k2], riY[k2], riZ[k2], 1f, edgeGlow, alpha, frozen, var);
            glassVertex(buf, inX[k], inY[k], inZ[k], sx, sy, sz, riX[k], riY[k], riZ[k], 1f, edgeGlow, alpha, frozen, var);
        }
    }

    /** Debris and chips: double-sided sharp triangles that glint as they tumble. */
    private static void drawDebris(ClientSkyEvent sky, double bx, double by, double bz, double t, double restoreAt,
                                   BufferBuilder buf) {
        var debris = sky.scene().fracture.debris;
        SkyScene.ShardPose pose = DEBRIS_POSE;
        double[] px = new double[3], py = new double[3], pz = new double[3], rx = new double[3], ry = new double[3], rz = new double[3];
        for (int i = 0; i < debris.size(); i++) {
            DebrisData d = debris.get(i);
            sky.scene().debrisPose(d, t, restoreAt, pose);
            if (pose.alpha <= 0.01) {
                continue;
            }
            Quat q = pose.rotation;
            double cx = d.origin.x() + pose.offset.x(), cy = d.origin.y() + pose.offset.y(), cz = d.origin.z() + pose.offset.z();
            for (int c = 0; c < 3; c++) {
                V3 v = d.tri[c];
                rotate(q, v.x(), v.y(), v.z(), A);
                A[0] += cx; A[1] += cy; A[2] += cz;
                sky.toWorld(A);
                px[c] = bx + A[0]; py[c] = by + A[1]; pz[c] = bz + A[2];
                A[0] = d.origin.x() + v.x(); A[1] = d.origin.y() + v.y(); A[2] = d.origin.z() + v.z();
                sky.toWorld(A);
                rx[c] = A[0]; ry[c] = A[1]; rz[c] = A[2];
            }
            rotate(q, d.normal.x(), d.normal.y(), d.normal.z(), B);
            sky.toWorld(B);
            double broke = d.isChip() ? d.chipTime * 20 : SkyTimeline.FALL + d.detachDelay * 20;
            double frozen = ticks - (t - broke);
            float a = (float) (pose.alpha * 0.95);
            local(i % 7, i % 5);
            glassVertex(buf, px[0], py[0], pz[0], B[0], B[1], B[2], rx[0], ry[0], rz[0], 1f, 0.9f, a, frozen, 0.5f);
            glassVertex(buf, px[1], py[1], pz[1], B[0], B[1], B[2], rx[1], ry[1], rz[1], 0.6f, 0.9f, a, frozen, 0.5f);
            glassVertex(buf, px[2], py[2], pz[2], B[0], B[1], B[2], rx[2], ry[2], rz[2], 0.6f, 0.9f, a, frozen, 0.5f);
        }
    }

    /** The sealed shell dissolves towards its rim, so the dome melts into the real horizon. */
    private static double rimFade(V2 disc) {
        return 1.0 - SkyScene.smoothstep(0.72, 0.98, disc.length());
    }

    // ------------------------------------------------------------------ cracks

    private static void drawCracks(ClientSkyEvent sky, double bx, double by, double bz, double t, double r) {
        SkyScene scene = sky.scene();
        double front = scene.crackFront(t, r);
        if (front <= 0) {
            return;
        }
        double intensityAll = scene.crackIntensity(t, r) * (1.0 + scene.skyPulse(t, r) * 0.6);
        double pressure = scene.pressure(t, r);
        // Light leaks through the great split as the two halves of the sky part.
        double splitLight = 1.6 + 2.4 * scene.splitGap(t, r);
        ShaderInstance shader = ModShaders.crack;
        shader.safeGetUniform("CrackParams").set((float) intensityAll, 0f, 0f, 0f);
        RenderSystem.setShader(() -> shader);
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL);
        var shards = scene.fracture.shards;
        var cracks = scene.fracture.cracks;
        for (int i = 0; i < cracks.size(); i++) {
            CrackSegment c = cracks.get(i);
            if (!scene.crackOnShell(c, sky.free)) {
                continue;
            }
            double[] range = c.visibleRange(front);
            ShardData owner = c.hairline() ? shards.get(c.cellA()) : null;
            SkyScene.ShardPose pose = owner == null ? null : sky.poses[owner.index];
            if (pose != null && pose.alpha <= 0.01) {
                continue;
            }
            V2 mid = c.a().lerp(c.b(), 0.5);
            double fade = pose != null && pose.free > 0 ? 1.0 : rimFade(mid);
            float intensity = (float) ((c.hairline() ? 0.55 : c.main() ? splitLight : 1.0) * fade);
            if (intensity <= 0.01f) {
                continue;
            }
            double width = c.width() * 2.2 * scene.crackWidth(c, front, t) * (c.main() ? 1.5 : 1.0);
            for (int k = 0; k + 1 < range.length; k += 2) {
                if (range[k + 1] - range[k] > 1e-4) {
                    emitCrack(buf, sky, c, i, range[k], range[k + 1], owner, pose, bx, by, bz, intensity, width, pressure);
                }
            }
        }
        shockwave(buf, sky, bx, by, bz, t, pressure);
        draw(buf);
    }

    /**
     * The snap sends a ring of light racing across the dome from the impact: the shock running
     * through the glass.
     */
    private static void shockwave(BufferBuilder buf, ClientSkyEvent sky, double bx, double by, double bz, double t,
                                  double pressure) {
        double p = SkyTimeline.progress(t, SkyTimeline.FIRST_CRACK, SkyTimeline.FIRST_CRACK + 22);
        if (p <= 0 || p >= 1) {
            return;
        }
        V2 c = sky.scene().fracture.impact;
        double radius = 0.02 + 1.0 * (1 - Math.pow(1 - p, 3));
        float intensity = (float) (3.0 * (1 - p) * (1 - p));
        double width = 1.2 + 4.0 * p;
        int segs = 128;
        double prevX = 0, prevY = 0, prevZ = 0;
        boolean prevOk = false;
        for (int i = 0; i <= segs; i++) {
            double a = Math.PI * 2 * i / segs;
            double u = c.x() + radius * Math.cos(a), v = c.y() + radius * Math.sin(a);
            boolean ok = u * u + v * v < 0.97;
            if (ok) {
                double dent = pressure > 0 ? sky.scene().dent(u, v, pressure) : 0;
                DomeShape.pointAtInto(u, v, CRACK_LIFT - 0.1 - dent, A);
                sky.toWorld(A);
                double x = bx + A[0], y = by + A[1], z = bz + A[2];
                if (prevOk) {
                    double dx = x - prevX, dy = y - prevY, dz = z - prevZ;
                    double mx = (x + prevX) * 0.5, my = (y + prevY) * 0.5, mz = (z + prevZ) * 0.5;
                    double cx = dy * mz - dz * my, cy = dz * mx - dx * mz, cz = dx * my - dy * mx;
                    double cl = Math.sqrt(cx * cx + cy * cy + cz * cz);
                    if (cl > 1e-9) {
                        cx = cx / cl * width; cy = cy / cl * width; cz = cz / cl * width;
                        crackVertex(buf, prevX - cx, prevY - cy, prevZ - cz, -1, intensity);
                        crackVertex(buf, prevX + cx, prevY + cy, prevZ + cz, 1, intensity);
                        crackVertex(buf, x + cx, y + cy, z + cz, 1, intensity);
                        crackVertex(buf, prevX - cx, prevY - cy, prevZ - cz, -1, intensity);
                        crackVertex(buf, x + cx, y + cy, z + cz, 1, intensity);
                        crackVertex(buf, x - cx, y - cy, z - cz, -1, intensity);
                    }
                }
                prevX = x; prevY = y; prevZ = z;
            }
            prevOk = ok;
        }
    }

    private static double hash(int a, int b) {
        long h = a * 0x9E3779B97F4A7C15L + b * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (h & 0xffffff) / (double) 0xffffff;
    }

    private static void emitCrack(BufferBuilder buf, ClientSkyEvent sky, CrackSegment c, int index, double f0, double f1,
                                  ShardData owner, SkyScene.ShardPose pose, double bx, double by, double bz,
                                  float intensity, double width, double pressure) {
        double ax = c.a().x(), ay = c.a().y(), ex = c.b().x() - ax, ey = c.b().y() - ay;
        double segLen = Math.sqrt(ex * ex + ey * ey);
        double nx = -ey / Math.max(segLen, 1e-12), ny = ex / Math.max(segLen, 1e-12);
        double blocks = segLen * (f1 - f0) * DomeShape.blocksPerDiscUnit();
        int pieces = Math.max(1, (int) Math.ceil(blocks / CRACK_PIECE));
        // A real crack wanders: interior points are pushed sideways a little (deterministic).
        double wander = 0; // the crack geometry itself is already jagged and shared with the shards
        double prevX = 0, prevY = 0, prevZ = 0;
        for (int p = 0; p <= pieces; p++) {
            double f = f0 + (f1 - f0) * p / pieces;
            double fullT = f; // position along the whole segment
            double off = (p == 0 || p == pieces) ? 0 : (hash(index, (int) Math.round(fullT * 64)) - 0.5) * 2 * wander;
            double u = ax + ex * fullT + nx * off, v = ay + ey * fullT + ny * off;
            crackPoint(sky, u, v, owner, pose, pressure, A);
            double x = bx + A[0], y = by + A[1], z = bz + A[2];
            if (p > 0) {
                double dx = x - prevX, dy = y - prevY, dz = z - prevZ;
                double mx = (x + prevX) * 0.5, my = (y + prevY) * 0.5, mz = (z + prevZ) * 0.5;
                double cx = dy * mz - dz * my, cy = dz * mx - dx * mz, cz = dx * my - dy * mx;
                double cl = Math.sqrt(cx * cx + cy * cy + cz * cz);
                if (cl > 1e-9) {
                    cx = cx / cl * width; cy = cy / cl * width; cz = cz / cl * width;
                    crackVertex(buf, prevX - cx, prevY - cy, prevZ - cz, -1, intensity);
                    crackVertex(buf, prevX + cx, prevY + cy, prevZ + cz, 1, intensity);
                    crackVertex(buf, x + cx, y + cy, z + cz, 1, intensity);
                    crackVertex(buf, prevX - cx, prevY - cy, prevZ - cz, -1, intensity);
                    crackVertex(buf, x + cx, y + cy, z + cz, 1, intensity);
                    crackVertex(buf, x - cx, y - cy, z - cz, -1, intensity);
                }
            }
            prevX = x; prevY = y; prevZ = z;
        }
    }

    /** Crack point in world axes relative to the event center; hairlines ride on their shard. */
    private static void crackPoint(ClientSkyEvent sky, double u, double v, ShardData owner, SkyScene.ShardPose pose,
                                   double pressure, double[] out) {
        double dent = pressure > 0 ? sky.scene().dent(u, v, pressure) : 0;
        DomeShape.pointAtInto(u, v, CRACK_LIFT - dent, out);
        if (owner != null && pose != null) {
            double lx = out[0] - owner.origin.x(), ly = out[1] - owner.origin.y(), lz = out[2] - owner.origin.z();
            rotate(pose.rotation, lx, ly, lz, out);
            out[0] += owner.origin.x() + pose.offset.x();
            out[1] += owner.origin.y() + pose.offset.y();
            out[2] += owner.origin.z() + pose.offset.z();
        }
        sky.toWorld(out);
    }

    private static void crackVertex(BufferBuilder buf, double x, double y, double z, float across, float intensity) {
        buf.addVertex((float) x, (float) y, (float) z).setUv(across, intensity)
                .setColor(0.82f, 0.94f, 1.0f, 1.0f).setNormal(0f, -1f, 0f);
    }

    // ------------------------------------------------------------------ hand

    private static void drawHand(ClientSkyEvent sky, double bx, double by, double bz, double t, double r) {
        // Before the target is caught the hand chases them; afterwards it follows the server's lock.
        V3 target = sky.state.locked() ? sky.lockLocal() : sky.aim((float) (t - Math.floor(t)));
        if (!HandRig.pose(t, r, target, sky.state.lockYaw(), sky.riftHeart(), HAND)) {
            return;
        }
        ShaderInstance shader = ModShaders.hand;
        RenderSystem.setShader(() -> shader);
        // Pass 1: the solid pale body, fading in as the silhouette fills with light.
        if (HAND.fill > 0.01) {
            shader.safeGetUniform("HandParams").set((float) HAND.intensity, 1f, (float) HAND.fill, 0f);
            RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
            emitHand(bx, by, bz);
        }
        // Pass 2: additive energy: the outline first, flowing light through the whole hand later.
        shader.safeGetUniform("HandParams").set((float) HAND.intensity, 0f, (float) HAND.fill, 0f);
        RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
        emitHand(bx, by, bz);
    }

    private static void emitHand(double bx, double by, double bz) {
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_TEX_COLOR_NORMAL);
        HandRig.mesh(HAND, (pos, n, along, fade) -> buf.addVertex((float) (bx + pos.x()), (float) (by + pos.y()), (float) (bz + pos.z()))
                .setUv((float) along, (float) fade).setColor(0.96f, 0.98f, 1.0f, 1.0f)
                .setNormal((float) n.x(), (float) n.y(), (float) n.z()));
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
