/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.gl;

import dev.xavier.stein.bridge.engine.GLMatrix;
import dev.xavier.stein.bridge.engine.Mat4;
import dev.xavier.stein.gfx.SteinGfxOptions;
import dev.xavier.stein.gfx.SteinTessCapture;
import dev.xavier.stein.gfx.game.SteinGameGl;
import dev.xavier.stein.gfx.game.SteinGameTextures;

import net.minecraft.client.renderer.texture.TextureUtil;

import org.lwjgl.opengl.ARBMultitexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * The OpenGL fixed-function pipeline between the game (and the engine) and the driver, for the core
 * profile (stage 7 of the renderer). Every function the core profile lacks goes through here: in
 * the fixed-function fallback (compatibility context, {@link SteinGfxOptions#ENABLED} false) it
 * goes to the driver as before; in the core context (4.1+, requested in Display) it does not reach
 * the driver — LWJGL does not even load those functions in the core, and calling one aborts the
 * JVM.
 *
 * <p>Almost everything becomes a no-op: the fixed-function state (light on, alpha test, texenv,
 * fog, current colour) is read by the Stein renderer from the GlStateManager cache, and the drawing
 * is entirely its own. What the renderer used to read from the driver is now tracked here: the
 * projection stack (the model and texture stacks are already GLMatrix's), lights 0 and 1 and the
 * ambient (with the position taken to eye space by the current model matrix, as OpenGL does), the
 * fog colour. Display lists become numbers with no list (the geometry of item lists is already
 * recorded when they are compiled). {@code GL_CLAMP} becomes {@code GL_CLAMP_TO_EDGE}; {@code
 * GL_EXTENSIONS} comes from glGetStringi.
 *
 * <p>Pipeline: both — the fixed pipeline passes each call to the driver; Stein GFX tracks it in
 * Java or drops it.
 */
public final class SteinCoreGl {

    /** Core context: the renderer's normal mode ({@link SteinGfxOptions#CORE}). */
    public static final boolean CORE = SteinGfxOptions.CORE;

    /** No OpenGL context (the Vulkan backend): nothing here reaches a driver. */
    private static final boolean VULKAN = SteinGfxOptions.VULKAN;

    private static final int GL_MODELVIEW = GL11.GL_MODELVIEW;
    private static final int GL_PROJECTION = GL11.GL_PROJECTION;
    private static final int GL_CLAMP = GL11.GL_CLAMP;
    private static final int GL_FOG_COLOR = GL11.GL_FOG_COLOR;
    private static final int GL_PROJECTION_MATRIX = GL11.GL_PROJECTION_MATRIX;
    private static final int GL_LIGHT_MODEL_AMBIENT = GL11.GL_LIGHT_MODEL_AMBIENT;
    private static final int GL_POSITION = GL11.GL_POSITION;
    private static final int GL_AMBIENT = GL11.GL_AMBIENT;
    private static final int GL_DIFFUSE = GL11.GL_DIFFUSE;
    private static final int GL_SPECULAR = GL11.GL_SPECULAR;
    private static final int GL_LIGHT0 = GL11.GL_LIGHT0;
    private static final int GL_EXTENSIONS = GL11.GL_EXTENSIONS;

    private SteinCoreGl() {}

    // ---- tracked state (core only) ----

    private static int mode = GL_MODELVIEW;
    private static final float[] PROJ = new float[16];
    private static final float[][] PROJ_STACK = new float[16][16];
    private static int projDepth;
    private static final float[] SCRATCH = new float[16];
    private static final float[] FOG_RGBA = new float[4];

    /** Per light (0..7): eye-space position, ambient, diffuse, specular — 4 floats each. */
    private static final float[][] LIGHTS = new float[8][16];

    private static final float[] MODEL_AMBIENT = {0.2f, 0.2f, 0.2f, 1f};
    private static int nextList = 1;

    static {
        Mat4.identity(PROJ);
        // OpenGL defaults: position (0, 0, 1, 0); ambient (0, 0, 0, 1); diffuse and specular
        // (1, 1, 1, 1) on light 0 and (0, 0, 0, 1) on the others
        for (int i = 0; i < 8; i++) {
            float on = i == 0 ? 1f : 0f;
            float[] l = LIGHTS[i];
            l[2] = 1f;
            l[7] = 1f;
            l[8] = on;
            l[9] = on;
            l[10] = on;
            l[11] = 1f;
            l[12] = on;
            l[13] = on;
            l[14] = on;
            l[15] = 1f;
        }
    }

    /** The current projection (column-major), tracked in the core. */
    public static float[] projection() {
        return PROJ;
    }

    private static boolean proj() {
        return mode == GL_PROJECTION;
    }

    // ---- matrices ----

    public static void glMatrixMode(int m) {
        if (CORE) {
            mode = m;
            return;
        }
        GL11.glMatrixMode(m);
    }

    public static void glLoadIdentity() {
        if (CORE) {
            if (proj()) {
                Mat4.identity(PROJ);
            }
            return;
        }
        GL11.glLoadIdentity();
    }

    public static void glLoadMatrixf(FloatBuffer b) {
        if (CORE) {
            if (proj()) {
                b.get(b.position(), PROJ);
            }
            return;
        }
        GL11.glLoadMatrixf(b);
    }

    public static void glMultMatrixf(FloatBuffer b) {
        if (CORE) {
            if (proj()) {
                b.get(b.position(), SCRATCH);
                Mat4.mul(PROJ, SCRATCH);
            }
            return;
        }
        GL11.glMultMatrixf(b);
    }

    public static void glOrtho(double l, double r, double b, double t, double n, double f) {
        if (CORE) {
            if (proj()) {
                Mat4.ortho(PROJ, (float) l, (float) r, (float) b, (float) t, (float) n, (float) f);
            }
            return;
        }
        GL11.glOrtho(l, r, b, t, n, f);
    }

    public static void glPushMatrix() {
        if (CORE) {
            if (proj() && projDepth < PROJ_STACK.length) {
                System.arraycopy(PROJ, 0, PROJ_STACK[projDepth++], 0, 16);
            }
            return;
        }
        GL11.glPushMatrix();
    }

    public static void glPopMatrix() {
        if (CORE) {
            if (proj() && projDepth > 0) {
                System.arraycopy(PROJ_STACK[--projDepth], 0, PROJ, 0, 16);
            }
            return;
        }
        GL11.glPopMatrix();
    }

    public static void glTranslatef(float x, float y, float z) {
        if (CORE) {
            if (proj()) {
                Mat4.translate(PROJ, x, y, z);
            }
            return;
        }
        GL11.glTranslatef(x, y, z);
    }

    public static void glTranslated(double x, double y, double z) {
        if (CORE) {
            glTranslatef((float) x, (float) y, (float) z);
            return;
        }
        GL11.glTranslated(x, y, z);
    }

    public static void glScalef(float x, float y, float z) {
        if (CORE) {
            if (proj()) {
                Mat4.scale(PROJ, x, y, z);
            }
            return;
        }
        GL11.glScalef(x, y, z);
    }

    public static void glScaled(double x, double y, double z) {
        if (CORE) {
            glScalef((float) x, (float) y, (float) z);
            return;
        }
        GL11.glScaled(x, y, z);
    }

    public static void glRotatef(float a, float x, float y, float z) {
        if (CORE) {
            if (proj()) {
                Mat4.rotate(PROJ, a, x, y, z);
            }
            return;
        }
        GL11.glRotatef(a, x, y, z);
    }

    // ---- light and fog ----

    public static void glLightfv(int light, int pname, FloatBuffer b) {
        if (!CORE) {
            GL11.glLightfv(light, pname, b);
            return;
        }
        int i = light - GL_LIGHT0;
        if (i < 0 || i >= 8) {
            return;
        }
        int p = b.position();
        switch (pname) {
            case GL_POSITION -> {
                // as OpenGL does: stored in eye space, by the current model matrix
                float x = b.get(p);
                float y = b.get(p + 1);
                float z = b.get(p + 2);
                float w = b.get(p + 3);
                float[] m = GLMatrix.current();
                LIGHTS[i][0] = m[0] * x + m[4] * y + m[8] * z + m[12] * w;
                LIGHTS[i][1] = m[1] * x + m[5] * y + m[9] * z + m[13] * w;
                LIGHTS[i][2] = m[2] * x + m[6] * y + m[10] * z + m[14] * w;
                LIGHTS[i][3] = m[3] * x + m[7] * y + m[11] * z + m[15] * w;
            }
            case GL_AMBIENT -> b.get(p, LIGHTS[i], 4, 4);
            case GL_DIFFUSE -> b.get(p, LIGHTS[i], 8, 4);
            case GL_SPECULAR -> b.get(p, LIGHTS[i], 12, 4);
            default -> {}
        }
    }

    public static void glGetLightfv(int light, int pname, FloatBuffer out) {
        if (!CORE) {
            GL11.glGetLightfv(light, pname, out);
            return;
        }
        int i = light - GL_LIGHT0;
        if (i < 0 || i >= 8) {
            return;
        }
        int at =
                switch (pname) {
                    case GL_POSITION -> 0;
                    case GL_AMBIENT -> 4;
                    case GL_DIFFUSE -> 8;
                    case GL_SPECULAR -> 12;
                    default -> -1;
                };
        if (at >= 0) {
            out.put(out.position(), LIGHTS[i], at, 4);
        }
    }

    public static void glLightModelfv(int pname, FloatBuffer b) {
        if (!CORE) {
            GL11.glLightModelfv(pname, b);
            return;
        }
        if (pname == GL_LIGHT_MODEL_AMBIENT) {
            b.get(b.position(), MODEL_AMBIENT);
        }
    }

    public static void glFogfv(int pname, FloatBuffer b) {
        if (!CORE) {
            GL11.glFogfv(pname, b);
            return;
        }
        if (pname == GL_FOG_COLOR) {
            b.get(b.position(), FOG_RGBA);
        }
    }

    public static void glFogf(int pname, float v) {
        if (!CORE) {
            GL11.glFogf(pname, v);
        }
    }

    public static void glFogi(int pname, int v) {
        if (!CORE) {
            GL11.glFogi(pname, v);
        }
    }

    /**
     * glGetFloatv: in the core, what the fixed-function pipeline would keep (projection, fog,
     * ambient) comes from here.
     */
    public static void glGetFloatv(int pname, FloatBuffer out) {
        if (CORE) {
            switch (pname) {
                case GL_PROJECTION_MATRIX -> {
                    out.put(out.position(), PROJ);
                    return;
                }
                case GL_FOG_COLOR -> {
                    out.put(out.position(), FOG_RGBA);
                    return;
                }
                case GL_LIGHT_MODEL_AMBIENT -> {
                    out.put(out.position(), MODEL_AMBIENT);
                    return;
                }
                default -> {}
            }
        }
        if (VULKAN) {
            return; // nothing else the game asks for lives in a driver
        }
        GL11.glGetFloatv(pname, out);
    }

    // ---- fixed-function state that the renderer reads from the GlStateManager cache ----

    public static void glAlphaFunc(int func, float ref) {
        if (!CORE) {
            GL11.glAlphaFunc(func, ref);
        }
    }

    public static void glShadeModel(int m) {
        if (!CORE) {
            GL11.glShadeModel(m);
        }
    }

    public static void glColor4f(float r, float g, float b, float a) {
        if (SteinGfxOptions.ENABLED) {
            SteinTessCapture.noteColor(r, g, b, a);
        }
        if (!CORE) {
            GL11.glColor4f(r, g, b, a);
        }
    }

    public static void glColorMaterial(int face, int m) {
        if (!CORE) {
            GL11.glColorMaterial(face, m);
        }
    }

    public static void glNormal3f(float x, float y, float z) {
        if (!CORE) {
            GL11.glNormal3f(x, y, z);
        }
    }

    public static void glTexEnvi(int target, int pname, int v) {
        if (!CORE) {
            GL11.glTexEnvi(target, pname, v);
        }
    }

    public static void glTexEnvfv(int target, int pname, FloatBuffer b) {
        if (!CORE) {
            GL11.glTexEnvfv(target, pname, b);
        }
    }

    public static void glTexGeni(int coord, int pname, int v) {
        if (!CORE) {
            GL11.glTexGeni(coord, pname, v);
        }
    }

    public static void glTexGenfv(int coord, int pname, FloatBuffer b) {
        if (!CORE) {
            GL11.glTexGenfv(coord, pname, b);
        }
    }

    public static void glPushAttrib(int mask) {
        if (!CORE) {
            GL11.glPushAttrib(mask);
        }
    }

    public static void glPopAttrib() {
        if (!CORE) {
            GL11.glPopAttrib();
        }
    }

    public static void glMultiTexCoord2f(int unit, float s, float t) {
        if (!CORE) {
            GL13.glMultiTexCoord2f(unit, s, t);
        }
    }

    public static void glMultiTexCoord2fARB(int unit, float s, float t) {
        if (!CORE) {
            ARBMultitexture.glMultiTexCoord2fARB(unit, s, t);
        }
    }

    public static void glClientActiveTexture(int unit) {
        if (!CORE) {
            GL13.glClientActiveTexture(unit);
        }
    }

    public static void glClientActiveTextureARB(int unit) {
        if (!CORE) {
            ARBMultitexture.glClientActiveTextureARB(unit);
        }
    }

    // ---- client arrays and immediate mode (draws that, in the core, only the Stein renderer
    // makes) ----

    public static void glEnableClientState(int cap) {
        if (!CORE) {
            GL11.glEnableClientState(cap);
        }
    }

    public static void glDisableClientState(int cap) {
        if (!CORE) {
            GL11.glDisableClientState(cap);
        }
    }

    public static void glVertexPointer(int size, int type, int stride, long pointer) {
        if (!CORE) {
            GL11.glVertexPointer(size, type, stride, pointer);
        }
    }

    public static void glVertexPointer(int size, int type, int stride, ByteBuffer pointer) {
        if (!CORE) {
            GL11.glVertexPointer(size, type, stride, pointer);
        }
    }

    public static void glColorPointer(int size, int type, int stride, long pointer) {
        if (!CORE) {
            GL11.glColorPointer(size, type, stride, pointer);
        }
    }

    public static void glColorPointer(int size, int type, int stride, ByteBuffer pointer) {
        if (!CORE) {
            GL11.glColorPointer(size, type, stride, pointer);
        }
    }

    public static void glTexCoordPointer(int size, int type, int stride, long pointer) {
        if (!CORE) {
            GL11.glTexCoordPointer(size, type, stride, pointer);
        }
    }

    public static void glTexCoordPointer(int size, int type, int stride, ByteBuffer pointer) {
        if (!CORE) {
            GL11.glTexCoordPointer(size, type, stride, pointer);
        }
    }

    public static void glNormalPointer(int type, int stride, long pointer) {
        if (!CORE) {
            GL11.glNormalPointer(type, stride, pointer);
        }
    }

    public static void glNormalPointer(int type, int stride, ByteBuffer pointer) {
        if (!CORE) {
            GL11.glNormalPointer(type, stride, pointer);
        }
    }

    public static void glBegin(int m) {
        if (SteinGlAudit.ON) {
            SteinGlAudit.draw("glBegin");
        }
        if (!CORE) {
            GL11.glBegin(m);
        }
    }

    public static void glEnd() {
        if (!CORE) {
            GL11.glEnd();
        }
    }

    public static void glVertex3f(float x, float y, float z) {
        if (!CORE) {
            GL11.glVertex3f(x, y, z);
        }
    }

    public static void glTexCoord2f(float s, float t) {
        if (!CORE) {
            GL11.glTexCoord2f(s, t);
        }
    }

    public static void glVertex2f(float x, float y) {
        if (!CORE) {
            GL11.glVertex2f(x, y);
        }
    }

    public static void glColor3f(float r, float g, float b) {
        if (SteinGfxOptions.ENABLED) {
            SteinTessCapture.noteColor(r, g, b, 1f);
        }
        if (!CORE) {
            GL11.glColor3f(r, g, b);
        }
    }

    // ---- display lists ----

    public static int glGenLists(int range) {
        if (CORE) {
            int first = nextList;
            nextList += Math.max(1, range);
            return first;
        }
        return GL11.glGenLists(range);
    }

    public static void glNewList(int list, int m) {
        if (SteinGlAudit.ON) {
            SteinGlAudit.compiling = m == GL11.GL_COMPILE;
        }
        if (!CORE) {
            GL11.glNewList(list, m);
        }
    }

    public static void glEndList() {
        if (SteinGlAudit.ON) {
            SteinGlAudit.compiling = false;
        }
        if (!CORE) {
            GL11.glEndList();
        }
    }

    public static void glCallList(int list) {
        if (SteinGlAudit.ON) {
            SteinGlAudit.draw("callList");
        }
        if (!CORE) {
            GL11.glCallList(list);
        }
    }

    /** Lists in a block (mods only: the game uses glCallList). */
    public static void glCallLists(IntBuffer lists) {
        if (SteinGlAudit.ON) {
            SteinGlAudit.draw("callLists");
        }
        if (!CORE) {
            GL11.glCallLists(lists);
        }
    }

    public static void glDeleteLists(int list, int range) {
        if (!CORE) {
            GL11.glDeleteLists(list, range);
        }
    }

    // ---- core functions with values the core does not accept ----

    /**
     * Fixed-function switches (light, material, alpha test, bound texture, fog...): they do not
     * exist in the core.
     */
    private static boolean fixedCap(int cap) {
        return switch (cap) {
            case GL11.GL_LIGHTING,
                    GL11.GL_COLOR_MATERIAL,
                    GL11.GL_ALPHA_TEST,
                    GL11.GL_TEXTURE_2D,
                    GL11.GL_FOG,
                    GL12.GL_RESCALE_NORMAL,
                    GL11.GL_NORMALIZE,
                    GL11.GL_TEXTURE_GEN_S,
                    GL11.GL_TEXTURE_GEN_T,
                    GL11.GL_TEXTURE_GEN_R,
                    GL11.GL_TEXTURE_GEN_Q,
                    GL11.GL_LINE_STIPPLE,
                    GL11.GL_POLYGON_STIPPLE,
                    GL11.GL_POINT_SMOOTH ->
                    true;
            default -> cap >= GL_LIGHT0 && cap <= GL_LIGHT0 + 7;
        };
    }

    public static void glEnable(int cap) {
        if (!VULKAN && (!CORE || !fixedCap(cap))) {
            GL11.glEnable(cap);
        }
    }

    public static void glDisable(int cap) {
        if (!VULKAN && (!CORE || !fixedCap(cap))) {
            GL11.glDisable(cap);
        }
    }

    /**
     * TextureUtil.allocateTextureImpl deletes the name and binds again before defining the levels:
     * in the fixed-function context the bind recreates the name; in the core only a name from
     * glGenTextures is valid, and the texture ended up empty. In the core the name is kept (the
     * glTexImage2D calls right after redefine every level); outside it, the game's deleteTexture,
     * as always.
     */
    public static void reallocTexture(int id) {
        if (CORE) {
            return;
        }
        TextureUtil.deleteTexture(id);
    }

    public static void glTexParameteri(int target, int pname, int v) {
        int value = CORE && v == GL_CLAMP ? GL12.GL_CLAMP_TO_EDGE : v;
        if (VULKAN) {
            SteinGameTextures.texParameter(target, pname, value);
            return;
        }
        int tex = recordedTexture(target);
        if (tex > 0) {
            SteinGlFrame.textureParameteri(tex, pname, value);
            return;
        }
        GL11.glTexParameteri(target, pname, value);
    }

    /** The game's Framebuffer passes GL_CLAMP as a float. */
    public static void glTexParameterf(int target, int pname, float v) {
        float value = CORE && v == GL_CLAMP ? GL12.GL_CLAMP_TO_EDGE : v;
        if (VULKAN) {
            SteinGameTextures.texParameter(target, pname, (int) value);
            return;
        }
        int tex = recordedTexture(target);
        if (tex > 0) {
            SteinGlFrame.textureParameterf(tex, pname, value);
            return;
        }
        GL11.glTexParameterf(target, pname, value);
    }

    /**
     * Recorded frame with a pending list: the bound 2D texture (what the game believes: the
     * GlStateManager cache, equal to the driver after the flush the patcher puts before the call),
     * so the parameter enters the list by name (the atlas filter switches between the terrain
     * layers). Another target, or the texture is unknown: the list goes first and the call follows
     * immediately (0).
     */
    static int recordedTexture(int target) {
        if (!SteinGlFrame.pending()) {
            return 0;
        }
        int tex =
                target == GL11.GL_TEXTURE_2D
                        ? SteinGlGame.textureName(SteinGlGame.activeUnit())
                        : -1;
        if (tex <= 0) {
            SteinGlFrame.sync("texture parameter with no known name");
            return 0;
        }
        return tex;
    }

    public static String glGetString(int name) {
        if (VULKAN) {
            return SteinGameGl.glGetString(name);
        }
        if (CORE && name == GL_EXTENSIONS) {
            int n = GL11.glGetInteger(GL30.GL_NUM_EXTENSIONS);
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    b.append(' ');
                }
                b.append(GL30.glGetStringi(GL_EXTENSIONS, i));
            }
            return b.toString();
        }
        return GL11.glGetString(name);
    }
}
