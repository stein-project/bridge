/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.ui;

import dev.xavier.stein.bridge.sync.SteinSync;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;

/**
 * The hit marker: a short flash of four diagonal ticks around the crosshair when a Stein Sync
 * server confirms a hit (HitConfirm), red and wider for a kill. Off by default; the player turns it
 * on in the video settings (Stein Sync). It only ever shows on a connection with the {@code hits}
 * feature, since nothing else confirms a hit.
 *
 * <p>Drawn on every frame while a flash lasts, so nothing is allocated there: the screen's
 * interface size is measured again only when the window or the GUI scale changes.
 */
final class HitMarker {

    /** How long each flash lasts, in milliseconds: a kill stays long enough to register. */
    private static final long HIT_MS = 180L;

    private static final long KILL_MS = 400L;

    private static final int HIT_RGB = 0xFFFFFF;
    private static final int KILL_RGB = 0xFF4545;

    /** When the current flash started and ends (0 when none), and whether it is a kill's. */
    private static long start;

    private static long until;
    private static boolean kill;

    // The interface size, kept for the window size and GUI scale it was measured at.
    private static int keyWidth = -1;
    private static int keyHeight = -1;
    private static int keyScale = -1;
    private static boolean keyUnicode;
    private static int guiWidth;
    private static int guiHeight;

    private HitMarker() {}

    /** A confirmed hit, on the game thread. A kill's flash is not cut short by a plain hit. */
    static void hit(boolean killed) {
        if (!SteinSync.hitMarker()) {
            return;
        }
        long now = Ui.now();
        if (killed || !kill || now >= until) {
            kill = killed;
            start = now;
            until = now + (killed ? KILL_MS : HIT_MS);
        }
    }

    /** Over the HUD, after the listeners' overlay. No allocation while a flash lasts. */
    static void draw() {
        if (until == 0L) {
            return;
        }
        long now = Ui.now();
        if (now >= until || !SteinSync.hitMarker() || !SteinSync.hits()) {
            until = 0L;
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        // Only where the game draws its crosshair: first person, with a player.
        if (mc.thePlayer == null || mc.gameSettings.thirdPersonView != 0) {
            return;
        }
        measure(mc);
        float t = (float) (now - start) / (until - start);
        float alpha = 1.0F - t * t;
        int argb = Ui.alpha(0xFF000000 | (kill ? KILL_RGB : HIT_RGB), alpha);
        float gap = 3.0F;
        float length = kill ? 5.0F : 3.5F;
        float thick = kill ? 1.5F : 1.0F;
        GlStateManager.pushMatrix();
        // The middle of the crosshair's centre unit, the one at (w/2, h/2).
        GlStateManager.translate(guiWidth / 2 + 0.5F, guiHeight / 2 + 0.5F, 0.0F);
        GlStateManager.rotate(45.0F, 0.0F, 0.0F, 1.0F);
        float h = thick / 2;
        Draw2D.rect(gap, -h, length, thick, argb);
        Draw2D.rect(-gap - length, -h, length, thick, argb);
        Draw2D.rect(-h, gap, thick, length, argb);
        Draw2D.rect(-h, -gap - length, thick, length, argb);
        GlStateManager.popMatrix();
    }

    /** The interface size, measured again only when what it depends on changed. */
    private static void measure(Minecraft mc) {
        int scale = mc.gameSettings.guiScale;
        boolean unicode = mc.isUnicode();
        if (mc.displayWidth != keyWidth
                || mc.displayHeight != keyHeight
                || scale != keyScale
                || unicode != keyUnicode) {
            ScaledResolution sr = new ScaledResolution(mc);
            guiWidth = sr.getScaledWidth();
            guiHeight = sr.getScaledHeight();
            keyWidth = mc.displayWidth;
            keyHeight = mc.displayHeight;
            keyScale = scale;
            keyUnicode = unicode;
        }
    }
}
