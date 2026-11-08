package com.spawnadmin.client;

import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * White-out used when the hand carries the local player beyond the sky. Driven by wall-clock
 * time so it survives the dimension change (the client level is replaced mid-flash).
 */
final class ScreenFlash {
    private static long holdUntil;
    private static long fadeEnd;
    private static float level;

    private ScreenFlash() {
    }

    /** Raise the flash to at least {@code strength} (0..1). */
    static void push(float strength) {
        level = Math.max(level, strength);
    }

    /** Full white for {@code holdMs}, then fade out over {@code fadeMs}. */
    static void whiteOut(long holdMs, long fadeMs) {
        long now = System.currentTimeMillis();
        holdUntil = now + holdMs;
        fadeEnd = holdUntil + fadeMs;
    }

    static void render(RenderGuiEvent.Post event) {
        long now = System.currentTimeMillis();
        float a = level;
        if (now < holdUntil) {
            a = 1f;
        } else if (now < fadeEnd) {
            a = Math.max(a, (fadeEnd - now) / (float) (fadeEnd - holdUntil));
        }
        level = 0f;
        if (a <= 0.003f) {
            return;
        }
        GuiGraphics g = event.getGuiGraphics();
        int alpha = Math.min(255, (int) (a * 255));
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), (alpha << 24) | 0xF4F8FF);
    }

    static void clear() {
        holdUntil = 0;
        fadeEnd = 0;
        level = 0;
    }
}
