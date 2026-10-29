/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.gfx.SteinGfxOptions;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.ScreenShotHelper;

import java.io.File;

/**
 * Direct rendering: world and GUI go straight to the window, without the game's intermediate
 * framebuffer and without its copy to the window every frame — the path 1.8.9 itself uses with
 * {@code fboEnable} off (no button in the game's options). It is an option (Visuals, through the
 * bridge's visual options): whatever depends on the intermediate framebuffer is left out while it
 * is on.
 *
 * <ul>
 *   <li>post-processing shaders (creeper, spider and enderman spectator view; the F4 ones) do not
 *       load, and whichever is active stops when this is turned on;
 *   <li>the spectator's player outline is not drawn;
 *   <li>the screenshot (F2) reads the window, so it is deferred to right before the buffer swap of
 *       the next frame, with the whole frame in it — after the swap, the back buffer's contents are
 *       undefined.
 * </ul>
 *
 * <p>Turning it on deletes the game's framebuffer (texture and depth on the card); turning it off
 * creates it again at the window size. {@code -Dsteinbridge.visuals.directRender=true} turns it on
 * from the start (the framebuffer is not even created).
 *
 * <p>On OpenGL, while the Lens's light needs its scene in 16 bits ({@link #suspend}), the game's
 * framebuffer comes back and the drawing is not direct; the option itself stays on and returns when
 * the Lens's light is turned off. The window can only hold 8 bits.
 *
 * <p>Pipeline: both — the direct window draw is shared; Stein GFX also turns off the game's
 * post-processing shaders and entity outlines.
 */
public final class DirectRender {

    private static boolean direct = EngineOptions.DIRECT_RENDER;

    /** The game's framebuffer is needed anyway (the Lens's 16-bit scene on OpenGL). */
    private static boolean suspended;

    /** Deferred F2: folder and framebuffer of the request, and the chat that shows the message. */
    private static boolean pending;

    private static Object pendingDir;
    private static Object pendingFramebuffer;
    private static Object pendingChat;

    static {
        if (direct) {
            Log.info("direct", "direct drawing from the start (no intermediate framebuffer)");
        }
    }

    private DirectRender() {}

    /** The marker, created on first use (Display.update calls before the game has started). */
    private static final class Marker {
        /**
         * Empty message returned to the deferred F2: only the printChatMessage detour recognizes
         * it.
         */
        static final Object MARKER = new ChatComponentText("");
    }

    /** Turns it on or off (game thread). */
    public static void setDirect(boolean on) {
        if (on == direct) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc == null) {
                return;
            }
            Framebuffer fb = suspended ? null : mc.getFramebuffer();
            if (on) {
                // The framebuffer goes away while the game still sees it as on: the game's delete
                // checks that.
                if (fb != null) {
                    fb.deleteFramebuffer();
                }
                EntityRenderer er = mc.entityRenderer;
                if (er != null) {
                    er.stopUseShader();
                }
                direct = true;
            } else {
                direct = false;
                if (fb != null) {
                    fb.createBindFramebuffer(mc.displayWidth, mc.displayHeight);
                }
            }
            Log.info(
                    "direct",
                    "direct drawing "
                            + (on
                                    ? "on: game framebuffer deleted"
                                    : "off: game framebuffer recreated"));
        } catch (Throwable t) {
            throw new IllegalStateException("direct drawing", t);
        }
    }

    // ---- hooks (patcher/redirects-mods.tsv) ----

    /**
     * OpenGlHelper.isFramebufferEnabled in Framebuffer, in ScreenShotHelper and in
     * LoadingScreenRenderer.
     */
    public static boolean framebufferEnabled() {
        return !drawingDirect() && OpenGlHelper.isFramebufferEnabled();
    }

    /** Whether the frame goes straight to the window now: the option on and not suspended. */
    private static boolean drawingDirect() {
        return direct && !suspended;
    }

    /**
     * While {@code on}, the game's framebuffer is used even with the option on (the Lens's light on
     * OpenGL draws the world in 16 bits, which the window cannot hold): with the option on, the
     * framebuffer is created now, and deleted again when the suspension ends. Render thread.
     */
    public static void suspend(boolean on) {
        if (on == suspended) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        Framebuffer fb = direct && mc != null ? mc.getFramebuffer() : null;
        if (on) {
            // (the game's create and delete act only while the framebuffer counts as on)
            suspended = true;
            if (fb != null) {
                fb.createBindFramebuffer(mc.displayWidth, mc.displayHeight);
            }
        } else {
            if (fb != null) {
                fb.deleteFramebuffer();
            }
            suspended = false;
        }
        if (fb == null) {
            return;
        }
        Log.info(
                "direct",
                on
                        ? "direct drawing suspended: the Lens's light needs the game framebuffer"
                        : "direct drawing back");
    }

    /**
     * EntityRenderer.loadShader: no post-processing shader in direct rendering, nor with Stein's
     * renderer (the effects are GLSL programs of the old pipeline, outside the API; they stay
     * disabled as in direct rendering).
     */
    public static void loadShader(Object renderer, Object location) {
        if (!direct && !SteinGfxOptions.ENABLED) {
            ((EntityRenderer) renderer).loadShader((ResourceLocation) location);
        }
    }

    /**
     * RenderGlobal.isRenderEntityOutlines: no spectator outline in direct rendering nor with
     * Stein's renderer.
     */
    public static boolean entityOutlines(Object renderGlobal) {
        return !direct
                && !SteinGfxOptions.ENABLED
                && ((RenderGlobal) renderGlobal).isRenderEntityOutlines();
    }

    /**
     * The F2 ScreenShotHelper.saveScreenshot (Minecraft.dispatchKeypresses): in direct rendering,
     * deferred.
     */
    public static Object screenshot(Object dir, int width, int height, Object framebuffer) {
        if (!drawingDirect()) {
            return ScreenShotHelper.saveScreenshot(
                    (File) dir, width, height, (Framebuffer) framebuffer);
        }
        pending = true;
        pendingDir = dir;
        pendingFramebuffer = framebuffer;
        return Marker.MARKER;
    }

    /**
     * printChatMessage of dispatchKeypresses: the deferred F2's message goes out when the capture
     * goes out.
     */
    public static void printScreenshot(Object chat, Object message) {
        if (message == Marker.MARKER) {
            pendingChat = chat;
            return;
        }
        ((GuiNewChat) chat).printChatMessage((IChatComponent) message);
    }

    /** Display.update, before the buffer swap: the deferred F2 reads the window's whole frame. */
    public static void beforeSwap() {
        if (!pending) {
            return;
        }
        pending = false;
        try {
            Minecraft mc = Minecraft.getMinecraft();
            IChatComponent message =
                    ScreenShotHelper.saveScreenshot(
                            (File) pendingDir,
                            mc.displayWidth,
                            mc.displayHeight,
                            (Framebuffer) pendingFramebuffer);
            if (pendingChat != null) {
                ((GuiNewChat) pendingChat).printChatMessage(message);
            }
        } catch (Throwable t) {
            Log.info("direct", "screenshot failed: " + t);
        }
        pendingDir = null;
        pendingFramebuffer = null;
        pendingChat = null;
    }
}
