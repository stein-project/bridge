/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.gfx.SteinTessCapture;

import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.VertexFormat;

import org.lwjgl.opengl.GL11;

import java.util.Arrays;

/**
 * Status bar icons (hearts, hunger, armor, air, mount health) in a single draw.
 *
 * <p>GuiIngame.renderPlayerStats draws each icon with a Gui.drawTexturedModalRect, and each one is
 * its own Tessellator.draw: begin, four vertices and a glDrawArrays with the vertices in client
 * memory — about 50 per frame in survival. In the sky profile, the glDrawArrays of these icons was
 * the biggest native cost on the render thread after the buffer swap. The method does not change GL
 * state between one icon and the next (no texture, color, blend or matrix: only arithmetic, the
 * profiler and the player), so the same vertices, in the same order, in a single glDrawArrays draw
 * the same pixels.
 *
 * <p>While renderPlayerStats runs, the begin of drawTexturedModalRect only opens the WorldRenderer
 * on the first icon and the draw does not send; on exit, a Tessellator.draw with all of them.
 *
 * <p>{@code -Dsteinbridge.opt.vanillaHudBatch=true}: one draw per icon. {@code
 * -Dsteinbridge.opt.checkHudBatch=true}: on each icon joined, checks that texture, color, blend,
 * alpha and depth are still those of the first.
 */
public final class HudBatch {

    private static final boolean VANILLA = EngineOptions.VANILLA_HUD_BATCH;

    private static final boolean CHECK = !VANILLA && EngineOptions.CHECK_HUD_BATCH;

    static {
        if (CHECK) {
            Log.info("hud", "check: GL state of each merged icon compared with the first one");
        } else if (VANILLA) {
            Log.info("hud", "one draw per status bar icon (vanilla)");
        }
    }

    /** Inside renderPlayerStats. */
    private static boolean active;

    /** The WorldRenderer has already been opened by the first icon. */
    private static boolean opened;

    /** The Tessellator with icons waiting to be drawn. */
    private static Object pending;

    private static int texture;
    private static boolean firstBlend;
    private static boolean firstAlphaTest;
    private static boolean firstDepthTest;
    private static final float[] firstColor = new float[4];
    private static final float[] currentColor = new float[4];
    private static final CheckReport REPORT = new CheckReport(5_000_000_000L);

    private HudBatch() {}

    /**
     * The renderPlayerStats call in renderGameOverlay: the icons come out in a single draw, at the
     * end.
     */
    public static void playerStats(Object gui, Object resolution) throws Throwable {
        if (VANILLA) {
            ((GuiIngame) gui).renderPlayerStats((ScaledResolution) resolution);
            return;
        }
        active = true;
        try {
            ((GuiIngame) gui).renderPlayerStats((ScaledResolution) resolution);
        } finally {
            active = false;
            opened = false;
            Object t = pending;
            pending = null;
            if (t != null) {
                // Recorded in the Stein renderer; otherwise the original.
                SteinTessCapture.draw(t);
            }
        }
    }

    /** WorldRenderer.begin in drawTexturedModalRect: with icons open, stays in the same draw. */
    public static void begin(Object renderer, int mode, Object format) {
        if (active && opened) {
            if (CHECK) {
                check();
            }
            return;
        }
        ((WorldRenderer) renderer).begin(mode, (VertexFormat) format);
        if (active) {
            opened = true;
            if (CHECK) {
                texture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                firstBlend = GL11.glIsEnabled(GL11.GL_BLEND);
                firstAlphaTest = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
                firstDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
                GL11.glGetFloatv(GL11.GL_CURRENT_COLOR, firstColor);
            }
        }
    }

    /** Tessellator.draw in drawTexturedModalRect: inside renderPlayerStats, left for the end. */
    public static void draw(Object tessellator) throws Throwable {
        if (active) {
            pending = tessellator;
            return;
        }
        SteinTessCapture.draw(tessellator);
    }

    private static void check() {
        GL11.glGetFloatv(GL11.GL_CURRENT_COLOR, currentColor);
        boolean ok =
                texture == GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
                        && firstBlend == GL11.glIsEnabled(GL11.GL_BLEND)
                        && firstAlphaTest == GL11.glIsEnabled(GL11.GL_ALPHA_TEST)
                        && firstDepthTest == GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
                        && Arrays.equals(firstColor, currentColor);
        if (REPORT.compared(ok)) {
            Log.info("hud", "DIVERGENCE: GL state changed between merged icons");
        }
        REPORT.maybeReport("hud");
    }
}
