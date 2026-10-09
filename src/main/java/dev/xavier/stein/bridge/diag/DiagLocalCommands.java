/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.diag;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.engine.Obf;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.resources.ResourcePackRepository;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.stats.StatFileWriter;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.ResourceLocation;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * The local commands of the chat scene ({@code #station}, {@code !shot}, {@code !screen}, {@code
 * !opt}...): they act on the client and never go to the server.
 */
final class DiagLocalCommands {
    private DiagLocalCommands() {}

    /** The current station of the coverage scene (label of the {@code !shot} captures). */
    private static String station = "inicio";

    /**
     * Chat entries that do not go to the server (the renderer's coverage scene):
     *
     * <ul>
     *   <li>{@code #name}: a new station (label for the renderer audit and the captures);
     *   <li>{@code !shot}: captures &lt;screenshotName&gt;-&lt;station&gt;.png;
     *   <li>{@code !screen class}: opens the screen of the class (obfuscated name; the constructor
     *       receives the current screen, the options, the player, the statistics, whatever fits);
     *       {@code !screen close} closes it;
     *   <li>{@code !f3 0|1|2}: F3 screen closed, open, open with the profiler chart;
     *   <li>{@code !persp 0|1|2}: first person, third person from behind, third person from the
     *       front;
     *   <li>{@code !shader next|off}: the game's post-processing shader (the "super secret
     *       settings");
     *   <li>{@code !key code 1|0}: presses or releases the key, like {@code steinbridge.diag.keys};
     *   <li>{@code !reload}: reloads the resources (the game's loading screen).
     * </ul>
     */
    static void run(String text) throws ReflectiveOperationException {
        String[] a = text.split(" ");
        if (text.startsWith("#")) {
            station = text.substring(1).trim();
            dev.xavier.stein.gfx.gl.SteinGlAudit.station(station);
            Log.info("diag", "station " + station);
            return;
        }
        switch (a[0]) {
            case "!shot" -> shot(a);
            case "!screen" -> screen(a);
            case "!f3" -> f3(a);
            case "!persp" -> persp(a);
            case "!shader" -> shader(a);
            case "!key" -> key(a);
            case "!state" -> state(a);
            case "!opt" -> option(a);
            case "!packs" -> packs(a, text);
            case "!tex" -> texture(a);
            case "!reload" -> reload(a);
            default -> Log.info("diag", "unknown local command: " + text);
        }
    }

    private static void shot(String[] a) {
        IChatComponent msg = DiagCapture.save(DiagCapture.NAME + "-" + station + ".png");
        Log.info("diag", "capture " + station + ": " + msg);
    }

    private static void screen(String[] a) throws ReflectiveOperationException {
        GuiScreen screen = a[1].equals("close") ? null : newScreen(a[1]);
        DiagGame.open(screen);
        Log.info("diag", "screen " + a[1]);
    }

    private static void f3(String[] a) {
        GameSettings settings = DiagGame.settings();
        int v = Integer.parseInt(a[1]);
        settings.showDebugInfo = v > 0;
        settings.showDebugProfilerChart = v > 1;
        Log.info("diag", "F3 " + v);
    }

    private static void persp(String[] a) {
        DiagGame.settings().thirdPersonView = Integer.parseInt(a[1]);
        Log.info("diag", "perspective " + a[1]);
    }

    private static void shader(String[] a) {
        EntityRenderer er = DiagGame.mc().entityRenderer;
        if (a[1].equals("off")) {
            er.stopUseShader();
        } else {
            er.activateNextShader();
        }
        Log.info("diag", "shader " + a[1] + ": " + er.getShaderGroup());
        Log.info("diag", "game shaders supported: " + OpenGlHelper.shadersSupported);
    }

    private static void key(String[] a) {
        DiagGame.setKey(Integer.parseInt(a[1]), !a[2].equals("0"));
    }

    private static void state(String[] a) {
        EntityPlayerSP player = DiagGame.player();
        Log.info(
                "diag",
                "state: hunger "
                        + player.getFoodStats().getFoodLevel()
                        + ", using item "
                        + player.isUsingItem()
                        + " ("
                        + player.getItemInUseCount()
                        + " ticks left), in hand "
                        + player.getHeldItem());
    }

    private static void option(String[] a) throws ReflectiveOperationException {
        Minecraft mc = DiagGame.mc();
        GameSettings settings = DiagGame.settings();
        // The option is named on the command line (the obfuscated GameSettings field name), so it
        // is looked up by name. Then the terrain is rebuilt, as the video screen does.
        Field fd = GameSettings.class.getDeclaredField(a[1]);
        fd.setAccessible(true);
        if (fd.getType() == boolean.class) {
            fd.setBoolean(settings, !a[2].equals("0"));
        } else {
            fd.setInt(settings, Integer.parseInt(a[2]));
        }
        mc.renderGlobal.loadRenderers();
        Log.info("diag", "option " + a[1] + " = " + a[2] + " (terrain reloaded)");
    }

    /**
     * Swaps the resource packs with the game open, like the packs screen: the ones whose name has
     * each piece of "a|b" ("-" = none).
     */
    private static void packs(String[] a, String text) {
        Minecraft mc = DiagGame.mc();
        GameSettings settings = DiagGame.settings();
        String want = text.substring(7).trim();
        // What the GlStateManager thinks is enabled x what the driver has, before the
        // reload.
        int activeNow = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL13.GL_ACTIVE_TEXTURE);
        StringBuilder st =
                new StringBuilder(
                        "before the reload: active unit driver "
                                + (activeNow - org.lwjgl.opengl.GL13.GL_TEXTURE0)
                                + ", game "
                                + dev.xavier.stein.gfx.SteinGameState.activeUnit());
        for (int u = 0; u < 3; u++) {
            org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0 + u);
            st.append(" | u")
                    .append(u)
                    .append(" driver ")
                    .append(
                            org.lwjgl.opengl.GL11.glGetInteger(
                                    org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D))
                    .append(" game ")
                    .append(dev.xavier.stein.gfx.SteinGameState.textureName(u));
        }
        org.lwjgl.opengl.GL13.glActiveTexture(activeNow);
        Log.info("diag", st.toString());
        ResourcePackRepository repo = mc.getResourcePackRepository();
        repo.updateRepositoryEntriesAll();
        List<ResourcePackRepository.Entry> chosen = new ArrayList<>();
        List<String> names = new ArrayList<>();
        if (!want.equals("-")) {
            for (String part : want.split("\\|")) {
                for (ResourcePackRepository.Entry e : repo.getRepositoryEntriesAll()) {
                    String name = e.getResourcePackName();
                    if (name.contains(part)) {
                        chosen.add(e);
                        names.add(name);
                        break;
                    }
                }
            }
        }
        repo.setRepositories(chosen);
        settings.resourcePacks = names;
        mc.refreshResources();
        Log.info("diag", "packs " + names + " (reloaded)");
    }

    /**
     * {@code !tex path}: the number the TextureManager has for the texture and the average of its
     * pixels on the GPU.
     */
    private static void texture(String[] a) {
        ITextureObject obj =
                DiagGame.mc().getTextureManager().getTexture(new ResourceLocation(a[1]));
        if (obj == null) {
            Log.info("diag", "texture " + a[1] + ": not loaded");
            return;
        }
        int id = obj.getGlTextureId();
        int w =
                org.lwjgl.opengl.GL45.glGetTextureLevelParameteri(
                        id, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH);
        int h =
                org.lwjgl.opengl.GL45.glGetTextureLevelParameteri(
                        id, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_HEIGHT);
        java.nio.ByteBuffer px = org.lwjgl.BufferUtils.createByteBuffer(Math.max(1, w * h * 4));
        org.lwjgl.opengl.GL45.glGetTextureImage(
                id, 0, org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, px);
        long r = 0;
        long g = 0;
        long b = 0;
        for (int i = 0; i < w * h; i++) {
            r += px.get(i * 4) & 255;
            g += px.get(i * 4 + 1) & 255;
            b += px.get(i * 4 + 2) & 255;
        }
        int n = Math.max(1, w * h);
        Log.info(
                "diag",
                "textura "
                        + a[1]
                        + ": number "
                        + id
                        + ", "
                        + w
                        + "x"
                        + h
                        + ", average "
                        + r / n
                        + ","
                        + g / n
                        + ","
                        + b / n);
    }

    private static void reload(String[] a) {
        DiagGame.mc().refreshResources();
        Log.info("diag", "resources reloaded");
    }

    /**
     * The screen of class {@code name}, with the constructor with the most arguments it can fill.
     * The class comes from the command line (an obfuscated name) and the constructor is chosen at
     * run time, so this one stays reflective.
     */
    private static GuiScreen newScreen(String name) throws ReflectiveOperationException {
        Minecraft mc = DiagGame.mc();
        Class<?> c = Obf.cls(name);
        EntityPlayerSP player = DiagGame.player();
        GuiScreen current = DiagGame.currentScreen();
        StatFileWriter stats = player == null ? null : player.getStatFileWriter();
        Object[] pool = {mc, DiagGame.settings(), player, stats};
        Constructor<?>[] cs = c.getConstructors();
        java.util.Arrays.sort(cs, (x, y) -> y.getParameterCount() - x.getParameterCount());
        Class<?> guiScreen = GuiScreen.class;
        next:
        for (Constructor<?> k : cs) {
            Class<?>[] ps = k.getParameterTypes();
            Object[] args = new Object[ps.length];
            for (int i = 0; i < ps.length; i++) {
                Class<?> p = ps[i];
                if (guiScreen.isAssignableFrom(p)) {
                    args[i] =
                            current != null && p.isInstance(current)
                                    ? current
                                    : p == guiScreen ? new GuiIngameMenu() : null;
                } else if (p == String.class) {
                    args[i] = "";
                } else if (p == boolean.class) {
                    args[i] = false;
                } else if (p == int.class) {
                    args[i] = 0;
                } else {
                    Object found = null;
                    for (Object o : pool) {
                        if (o != null && p.isInstance(o)) {
                            found = o;
                            break;
                        }
                    }
                    for (Field fd :
                            found == null ? Minecraft.class.getDeclaredFields() : new Field[0]) {
                        if (!java.lang.reflect.Modifier.isStatic(fd.getModifiers())
                                && p.isAssignableFrom(fd.getType())) {
                            fd.setAccessible(true);
                            found = fd.get(mc);
                            if (found != null) {
                                break;
                            }
                        }
                    }
                    if (found == null) {
                        continue next;
                    }
                    args[i] = found;
                }
            }
            return (GuiScreen) k.newInstance(args);
        }
        throw new NoSuchMethodException("no constructor for " + name);
    }
}
