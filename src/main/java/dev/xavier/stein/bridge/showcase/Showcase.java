/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.showcase;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.particle.EffectRenderer;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.Entity;
import net.minecraft.util.MovingObjectPosition;

/**
 * The video settings' showcase: while it is open, the screen shows a landscape of Stein's own (
 * {@link ShowcaseTerrain}) instead of the place the player is in, so every graphics option can be
 * tried and seen at once — the post-processing, HDR and the game's own options act on it like on
 * the real world.
 *
 * <p>Only the drawing changes. In place of EntityRenderer.renderWorld (inside
 * updateCameraAndRender), the game's world, renderer, particles and camera are swapped for the
 * showcase's, and given back right after: the HUD and the screen, the ticks, the packets and the
 * player's physics all keep running on the real world, which stays connected. Around the swap the
 * aim (objectMouseOver, pointedEntity) is kept, the third-person view is off and the hand is not
 * drawn (it is the real player's).
 *
 * <p>Day and night: the scene rests in the late afternoon (the sun low in the west, in front of the
 * camera) or before dawn (the moon low in the west). {@link #setNight} runs the clock forward in a
 * few seconds, eased: the sun sets in front, the stars come out, the moon comes down into view —
 * and back, through the dawn behind the camera.
 *
 * <p>Pipeline: both.
 */
public final class Showcase {

    /**
     * The resting times: the sun ~16° above the western horizon (golden hour), the moon ~19° (a
     * full moon). The game's sky bends the clock (celestial angle = f + (f2 - f) / 3), hence the
     * odd numbers.
     */
    static final long DAY_TIME = 11700L;

    static final long NIGHT_TIME = 22100L;

    /** How long the clock takes from one rest to the other. */
    private static final double TRANSITION_SECONDS = 5.0;

    private static ShowcaseWorld scene;
    private static boolean active;
    private static boolean failed;

    /** The clock: from {@link #from} to {@link #to} ticks since {@link #startNanos}. */
    private static double from = DAY_TIME;

    private static double to = DAY_TIME;
    private static long startNanos;
    private static boolean night;

    /** The settings the scene's chunks were built with; a change rebuilds them. */
    private static int builtFor;

    private Showcase() {}

    /** Whether the showcase is being drawn (mods' world hooks skip it). */
    public static boolean active() {
        return active;
    }

    /**
     * Starts drawing the showcase (the first time, builds it). Only with a world loaded: the swap
     * happens inside the world's own drawing. Render thread.
     */
    public static void open() {
        if (failed || active || Minecraft.getMinecraft().theWorld == null) {
            return;
        }
        try {
            if (scene == null) {
                scene = ShowcaseWorld.build();
                builtFor = settingsKey();
            }
            active = true;
        } catch (RuntimeException | LinkageError e) {
            failed = true;
            Log.warn("showcase", "showcase unavailable for this session: " + e);
        }
    }

    /** Back to the real world; its lightmap is recomputed at the next frame. */
    public static void close() {
        if (!active) {
            return;
        }
        active = false;
        Minecraft.getMinecraft().entityRenderer.lightmapUpdateNeeded = true;
    }

    /** Night or day, with the time-lapse between them. */
    public static void setNight(boolean on) {
        if (on == night) {
            return;
        }
        night = on;
        from = clock() % 24000.0;
        // forward only: day -> night through the sunset, night -> day through the dawn
        double target = on ? NIGHT_TIME : DAY_TIME;
        while (target <= from) {
            target += 24000.0;
        }
        to = target;
        startNanos = System.nanoTime();
    }

    public static boolean night() {
        return night;
    }

    /** The showcase's time of day now, in ticks (eased between the two rests). */
    private static double clock() {
        double t = (System.nanoTime() - startNanos) / 1e9 / TRANSITION_SECONDS;
        if (t >= 1.0) {
            return to;
        }
        double eased = t * t * (3 - 2 * t);
        return from + (to - from) * eased;
    }

    /** After each game tick: the clouds drift (the scene itself never ticks). */
    public static void tick() {
        if (active && scene != null) {
            scene.renderGlobal.updateClouds();
        }
    }

    /** In place of EntityRenderer.renderWorld in updateCameraAndRender. */
    public static void renderWorld(Object entityRenderer, float partialTicks, long finishNanos) {
        EntityRenderer er = (EntityRenderer) entityRenderer;
        if (!active) {
            er.renderWorld(partialTicks, finishNanos);
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        WorldClient world = mc.theWorld;
        RenderGlobal rg = mc.renderGlobal;
        Entity view = mc.renderViewEntity;
        EffectRenderer fx = mc.effectRenderer;
        MovingObjectPosition aim = mc.objectMouseOver;
        Entity pointed = mc.pointedEntity;
        Entity rendererPointed = er.pointedEntity;
        GameSettings gs = mc.gameSettings;
        int thirdPerson = gs.thirdPersonView;
        try {
            prepare();
            mc.theWorld = scene.world;
            mc.renderGlobal = scene.renderGlobal;
            mc.renderViewEntity = scene.camera;
            mc.effectRenderer = scene.effects;
            gs.thirdPersonView = 0;
            er.renderWorld(partialTicks, finishNanos);
        } finally {
            mc.theWorld = world;
            mc.renderGlobal = rg;
            mc.renderViewEntity = view;
            mc.effectRenderer = fx;
            mc.objectMouseOver = aim;
            mc.pointedEntity = pointed;
            er.pointedEntity = rendererPointed;
            gs.thirdPersonView = thirdPerson;
        }
    }

    /** In place of EntityRenderer.renderHand in renderWorldPass: no hand in the showcase. */
    public static void renderHand(Object entityRenderer, float partialTicks, int pass) {
        if (!active) {
            ((EntityRenderer) entityRenderer).renderHand(partialTicks, pass);
        }
    }

    /** Before the frame of the showcase: its clock, and its chunks if the settings changed. */
    private static void prepare() {
        long ticks = (long) Math.floor(clock());
        scene.world.setWorldTime(ticks % 24000L);
        if (ticks >= 24000L && clock() >= to) {
            // the transition is over: back into the first day (the moon stays full)
            from = to = ticks % 24000L;
        }
        ShowcaseWorld.hold(scene.camera);
        int key = settingsKey();
        if (key != builtFor) {
            builtFor = key;
            scene.renderGlobal.loadRenderers();
        }
    }

    /** What the chunk meshes depend on: the game reloads its own renderer when these change. */
    private static int settingsKey() {
        GameSettings gs = Minecraft.getMinecraft().gameSettings;
        int key = gs.renderDistanceChunks;
        key = key * 31 + (gs.fancyGraphics ? 1 : 0);
        key = key * 31 + gs.ambientOcclusion;
        key = key * 31 + gs.mipmapLevels;
        key = key * 31 + (gs.useVbo ? 1 : 0);
        return key;
    }
}
