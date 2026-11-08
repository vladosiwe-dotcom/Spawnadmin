package com.spawnadmin.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * A colour copy of the main framebuffer, used by the glass (refraction) and time-stop shaders.
 * One blit per frame, only while a sky event is visible. Released when idle, on logout and on
 * every resize so no GPU memory outlives the effect.
 */
final class SceneCopy {
    private static TextureTarget target;

    private SceneCopy() {
    }

    /** Copies the current main colour buffer and returns the copy's texture id. */
    static int capture() {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (target == null || target.width != main.width || target.height != main.height) {
            release();
            target = new TextureTarget(main.width, main.height, false, Minecraft.ON_OSX);
            target.setFilterMode(GL11.GL_LINEAR);
        }
        int prevRead = GlStateManager._getInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevDraw = GlStateManager._getInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, main.width, main.height, 0, 0, main.width, main.height,
                GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
        return target.getColorTextureId();
    }

    static void release() {
        if (target != null) {
            target.destroyBuffers();
            target = null;
        }
    }

    static boolean allocated() {
        return target != null;
    }
}
