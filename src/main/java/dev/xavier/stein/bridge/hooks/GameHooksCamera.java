/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.hooks;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.settings.GameSettings;

import org.lwjgl.input.Mouse;

/**
 * The camera side of {@link GameHooks} for a zoom (the Lens zoom): how far the mouse turns the
 * view, the hand in first person, a forced smooth camera, the wheel while playing, and the visible
 * chunks when the field of view widens. Client view only: what the mouse does is what a lower
 * sensitivity option would do, and nothing here reaches the server or the game's logic.
 */
final class GameHooksCamera {

    /** Multiplies each frame's mouse turn (1 = the player's sensitivity). */
    private static float lookScale = 1.0F;

    /** The held item and the arm in first person are not drawn. */
    private static boolean handHidden;

    /** The smooth camera is forced on, and what the player's choice was before. */
    private static boolean smoothForced;

    private static boolean smoothBefore;

    /** The world's field of view of the last request, to see it widen. */
    private static float lastWorldFov;

    private GameHooksCamera() {}

    static void setLookScale(float scale) {
        lookScale = scale > 0.0F && scale <= 1.0e3F ? scale : 1.0F;
    }

    static void setHandHidden(boolean hidden) {
        handHidden = hidden;
    }

    /**
     * Forces the game's smooth camera (F8) on, or gives the player's choice back. When it was off
     * before, the filters are zeroed on the way back, so the next time starts with no leftover
     * movement; a player's own smooth camera keeps its motion.
     */
    static void setSmoothCamera(boolean on) {
        if (on == smoothForced) {
            return;
        }
        try {
            GameSettings s = Minecraft.getMinecraft().gameSettings;
            if (on) {
                smoothBefore = s.smoothCamera;
                s.smoothCamera = true;
            } else {
                s.smoothCamera = smoothBefore;
                if (!smoothBefore) {
                    GameHooksRender.resetCameraSmoothing();
                }
            }
            smoothForced = on;
        } catch (Throwable e) {
            GameHooks.fail("setSmoothCamera", e);
        }
    }

    /**
     * In place of EntityPlayerSP.setAngles inside EntityRenderer.updateCameraAndRender (both calls,
     * with and without the smooth camera): the frame's mouse turn, scaled.
     */
    static void look(Object player, float yaw, float pitch) {
        float s = lookScale;
        ((EntityPlayerSP) player).setAngles(yaw * s, pitch * s);
    }

    /**
     * In place of ItemRenderer.renderItemInFirstPerson inside EntityRenderer.renderHand: the held
     * item and the arm. The overlays drawn after it (fire, water, inside a block) stay.
     */
    static void firstPersonHand(Object itemRenderer, float partialTicks) {
        if (!handHidden) {
            ((ItemRenderer) itemRenderer).renderItemInFirstPerson(partialTicks);
        }
    }

    /**
     * In place of Mouse.getEventDWheel in Minecraft.runTick: a wheel a listener takes (a zoom
     * level) reaches the game as no wheel — the hotbar slot and the spectator's flying speed stay.
     */
    static int eventWheel() {
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0 && GameHooks.SINK != null) {
            try {
                if (GameHooks.SINK.mouseWheel(wheel)) {
                    return 0;
                }
            } catch (Throwable e) {
                GameHooks.fail("mouseWheel", e);
            }
        }
        return wheel;
    }

    /**
     * After each world field of view: when it widens (a zoom going out), the terrain redoes the
     * visible-chunk search. Standing still, the game keeps the chunks found with the narrow view,
     * and the screen edges would lack the ones outside it until the camera moved.
     */
    static void worldFov(float fov) {
        if (fov > lastWorldFov + 1.0e-3F) {
            RenderGlobal rg = Minecraft.getMinecraft().renderGlobal;
            if (rg != null) {
                rg.setDisplayListEntitiesDirty();
            }
        }
        lastWorldFov = fov;
    }
}
