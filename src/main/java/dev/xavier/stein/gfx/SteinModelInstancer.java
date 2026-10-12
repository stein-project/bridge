/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinInstanceWriter.COMMAND_BYTES;
import static dev.xavier.stein.gfx.SteinInstanceWriter.REGIONS;
import static dev.xavier.stein.gfx.SteinInstanceWriter.RING_PARTS;
import static dev.xavier.stein.gfx.SteinInstanceWriter.putMatrixRows;
import static dev.xavier.stein.gfx.SteinInstanceWriter.putTexture;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.engine.GLMatrix;
import dev.xavier.stein.gfx.gl.SteinGlBindings;
import dev.xavier.stein.gfx.gl.SteinGlFrame;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryUtil;

import java.util.Arrays;

/**
 * Model parts drawn per instance (step C1 of the renderer).
 *
 * <p>In today's path ({@code ModelBatch}) each part of each model (head, arm, leg...) has its ~24
 * vertices transformed on the CPU and written to the buffer, every frame: with 400 players, ~17
 * thousand parts per frame, and that is where most of the entities' time goes. Here:
 *
 * <ul>
 *   <li>the geometry of each part (captured when the game builds its display list) is uploaded
 *       <b>once</b> to a fixed buffer on the card, already as triangles;
 *   <li>per frame only the part's matrix and the texture matrix (the enchantment glint) go, 80
 *       bytes, and a 16-byte indirect draw command, in a persistent ring;
 *   <li>consecutive parts go out in a single {@code glMultiDrawArraysIndirect}; the vertex shader
 *       transforms and does the entity lighting by reading OpenGL's own state (the two lights, the
 *       color, the material, the lightmap), and the pixel stage remains the game's — texture, fog,
 *       alpha test, blend, the glint pass — with nothing imitated.
 * </ul>
 *
 * <p>With Stein GFX (the default) only the fixed geometry on the card is used: the deferred
 * entities draw the parts from it, and the direct draw here stays off. In the fixed-function
 * pipeline the direct draw is experimental and off by default ({@code
 * -Dsteinbridge.gfx.instancedModels=true} turns it on); it requires GL 4.4 (multi indirect draw
 * with base instance and persistent buffers) and what the ModelBatch already requires.
 *
 * <p>Pipeline: both — Stein GFX uses the fixed geometry on the GPU; the fixed pipeline uses the
 * direct draw only with -Dsteinbridge.gfx.instancedModels=true (experimental, GL 4.4).
 */
public final class SteinModelInstancer {

    public static final boolean REQUESTED =
            SteinGfxOptions.ENABLED || SteinGfxOptions.INSTANCED_MODELS;

    private static final int VERTEX_FLOATS = 8; // position 3, texture 2, normal 3
    static final int VERTEX_BYTES = VERTEX_FLOATS * 4;

    /** One instance of the direct draw: 3 matrix rows + 2 of the texture matrix, vec4 each. */
    private static final int DIRECT_INSTANCE_BYTES = 80;

    private static final int FIRST_INSTANCE_ATTRIBUTE = 10; // keep in sync with the GLSL below
    private static final int INSTANCE_ATTRIBUTES = 5;
    private static final int[] QUAD_TO_TRIANGLES = {0, 1, 2, 0, 2, 3};
    private static final long GEOMETRY_BYTES = 64L << 20;

    private static boolean initDone;
    private static boolean active;

    /** The fixed geometry on the card exists (even without the direct draw from here). */
    private static boolean geometryReady;

    private static int program;
    private static int uLighting;
    private static int uColorMaterial;
    private static int uTexOverride;
    private static int uTex0;
    private static int uTex1;
    private static int vao;
    private static SteinBuffer geometry;

    /** Bytes already written to the fixed geometry buffer (the next chunk goes from here). */
    private static long geometryUsed;

    private static SteinBuffer instances;
    private static SteinBuffer commands;

    /**
     * Per display list: the first vertex and how many (in triangles), in the fixed buffer; count 0
     * = not registered.
     */
    private static int[] firstVertexOf = new int[8192];

    private static int[] vertexCountOf = new int[8192];

    /** The pending one: first command (byte offset in the command ring) and how many. */
    private static long pendingCommands;

    private static int pendingCount;
    private static boolean flushing;

    private static long frameParts;
    private static long frameDraws;

    private SteinModelInstancer() {}

    // ---- shader ----

    private static final String VERTEX =
            """
            #version 330 compatibility
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec2 aUv;
            layout(location = 2) in vec3 aNormal;
            // Instance: in locations that do not mix with the fixed attributes the game uses (color
            // = 3, lightmap = 9).
            layout(location = 10) in vec4 iRow0;
            layout(location = 11) in vec4 iRow1;
            layout(location = 12) in vec4 iRow2;
            layout(location = 13) in vec4 iTex0;
            layout(location = 14) in vec4 iTex1;
            uniform int lighting;
            uniform int colorMaterial;
            uniform int texOverride;
            uniform vec4 oTex0;
            uniform vec4 oTex1;
            void main() {
                vec4 p = vec4(aPos, 1.0);
                vec4 eye = vec4(dot(iRow0, p), dot(iRow1, p), dot(iRow2, p), 1.0);
                gl_Position = gl_ProjectionMatrix * eye;
                vec3 st = vec3(aUv, 1.0);
                gl_TexCoord[0] = texOverride == 1
                        ? vec4(dot(oTex0.xyz, st), dot(oTex1.xyz, st), 0.0, 1.0)
                        : vec4(dot(iTex0.xyz, st), dot(iTex1.xyz, st), 0.0, 1.0);
                gl_TexCoord[1] = gl_TextureMatrix[1] * gl_MultiTexCoord1;
                vec4 c = gl_Color;
                if (lighting == 1) {
                    // OpenGL's fixed light: scene ambient + ambient and diffuse of the two lights
                    // (no specular).
                    vec3 n = normalize(vec3(
                            dot(iRow0.xyz, aNormal),
                            dot(iRow1.xyz, aNormal),
                            dot(iRow2.xyz, aNormal)));
                    vec4 amb = colorMaterial == 1 ? gl_Color : gl_FrontMaterial.ambient;
                    vec4 dif = colorMaterial == 1 ? gl_Color : gl_FrontMaterial.diffuse;
                    vec3 l = gl_FrontMaterial.emission.rgb + gl_LightModel.ambient.rgb * amb.rgb;
                    for (int i = 0; i < 2; i++) {
                        vec3 dir = normalize(gl_LightSource[i].position.xyz);
                        l += gl_LightSource[i].ambient.rgb * amb.rgb
                                + max(dot(n, dir), 0.0) * gl_LightSource[i].diffuse.rgb * dif.rgb;
                    }
                    c = vec4(clamp(l, 0.0, 1.0), dif.a);
                }
                gl_FrontColor = c;
                gl_BackColor = c;
                gl_FogFragCoord = abs(eye.z);
            }
            """;

    // ---- startup ----

    private static boolean ready() {
        if (initDone) {
            return active;
        }
        initDone = true;
        if (!REQUESTED) {
            return false;
        }
        GLCapabilities caps = GL.getCapabilities();
        if (!caps.OpenGL33 || !GLMatrix.emulating()) {
            Log.info("gfx", "instanced parts off: need GL 3.3 and the matrix stack in Java");
            return false;
        }
        SteinDevice device = SteinDevice.get();
        // The fixed geometry on the card works at any level (the deferred renderer uses it on
        // 4.1); the direct draw from here requires indirect draw and the persistent buffer.
        geometry =
                device.createBuffer(
                        new SteinBuffer.Desc(
                                "models",
                                GEOMETRY_BYTES,
                                SteinBuffer.Memory.GPU,
                                SteinBuffer.Usage.VERTEX));
        geometryReady = true;
        if (SteinGfxOptions.ENABLED) {
            Log.info("gfx", "model parts: fixed geometry on the GPU");
            return false;
        }
        if (!device.caps().multiDrawIndirect || !device.caps().persistentMapping) {
            Log.info(
                    "gfx",
                    "direct instanced parts off: need GL 4.4 (indirect draw and persistent"
                            + " buffer); the geometry stays on the GPU for the deferred renderer");
            return false;
        }
        program = SteinShader.program("instanced parts", VERTEX, null);
        if (program == 0) {
            return false;
        }
        uLighting = GL20.glGetUniformLocation(program, "lighting");
        uColorMaterial = GL20.glGetUniformLocation(program, "colorMaterial");
        uTexOverride = GL20.glGetUniformLocation(program, "texOverride");
        uTex0 = GL20.glGetUniformLocation(program, "oTex0");
        uTex1 = GL20.glGetUniformLocation(program, "oTex1");
        instances =
                device.createBuffer(
                                new SteinBuffer.Desc(
                                        "model instances",
                                        (long) RING_PARTS * DIRECT_INSTANCE_BYTES * REGIONS,
                                        SteinBuffer.Memory.UPLOAD,
                                        SteinBuffer.Usage.VERTEX))
                        .ring(REGIONS);
        commands =
                device.createBuffer(
                                new SteinBuffer.Desc(
                                        "model commands",
                                        (long) RING_PARTS * COMMAND_BYTES * REGIONS,
                                        SteinBuffer.Memory.UPLOAD,
                                        SteinBuffer.Usage.INDIRECT))
                        .ring(REGIONS);
        if (!instances.mapped() || !commands.mapped()) {
            Log.info("gfx", "instanced parts off: persistent mapping failed");
            return false;
        }
        vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, (int) geometry.handle());
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, VERTEX_BYTES, 0L);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, VERTEX_BYTES, 12L);
        GL20.glEnableVertexAttribArray(2);
        GL20.glVertexAttribPointer(2, 3, GL11.GL_FLOAT, false, VERTEX_BYTES, 20L);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, (int) instances.handle());
        for (int i = 0; i < INSTANCE_ATTRIBUTES; i++) {
            int location = FIRST_INSTANCE_ATTRIBUTE + i;
            GL20.glEnableVertexAttribArray(location);
            GL20.glVertexAttribPointer(
                    location, 4, GL11.GL_FLOAT, false, DIRECT_INSTANCE_BYTES, i * 16L);
            GL33.glVertexAttribDivisor(location, 1);
        }
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        GL30.glBindVertexArray(0);
        // Stein's pass reads the VAO without asking
        SteinGlBindings.noteVao(0);
        active = true;
        Log.info("gfx", "instanced model parts: fixed geometry on the GPU, indirect draw");
        return true;
    }

    // ---- capture (when the game builds the part's display list) ----

    /** The geometry of a part, in quads (8 floats per vertex): uploaded once, as triangles. */
    public static void register(int list, float[] quads) {
        if (!REQUESTED || list <= 0) {
            return;
        }
        ready();
        if (!geometryReady) {
            return;
        }
        int verts = quads.length / VERTEX_FLOATS;
        if (verts % 4 != 0) {
            return;
        }
        int quadsCount = verts / 4;
        float[] tris = new float[quadsCount * 6 * VERTEX_FLOATS];
        int k = 0;
        for (int q = 0; q < quadsCount; q++) {
            for (int corner : QUAD_TO_TRIANGLES) {
                System.arraycopy(quads, (q * 4 + corner) * VERTEX_FLOATS, tris, k, VERTEX_FLOATS);
                k += VERTEX_FLOATS;
            }
        }
        long bytes = (long) tris.length * 4;
        if (geometryUsed + bytes > geometry.size()) {
            return; // fixed buffer full: the part goes through today's path
        }
        long at = geometryUsed;
        geometry.upload(at, tris, tris.length);
        geometryUsed += bytes;
        if (list >= firstVertexOf.length) {
            int n = Math.max(list + 1, firstVertexOf.length * 2);
            firstVertexOf = Arrays.copyOf(firstVertexOf, n);
            vertexCountOf = Arrays.copyOf(vertexCountOf, n);
        }
        firstVertexOf[list] = (int) (at / VERTEX_BYTES);
        vertexCountOf[list] = quadsCount * 6;
    }

    // ---- draw ----

    /** Does the display list already have its geometry on the card? */
    public static boolean has(int list) {
        return geometryReady && list > 0 && list < vertexCountOf.length && vertexCountOf[list] != 0;
    }

    /**
     * Draws the part per instance, with the current matrix; false if it is not from here (today's
     * path draws it).
     */
    public static boolean draw(int list) {
        if (!active || !has(list) || !GLMatrix.batchable()) {
            return false;
        }
        return drawWith(list, GLMatrix.current(), 0);
    }

    /** A ring region filled up: draws what is pending and moves both rings to the next region. */
    private static void flushAndRotate() {
        flush();
        instances.nextRegion();
        commands.nextRegion();
    }

    /**
     * Draws part {@code list} with the matrix in {@code m[o..o+15]} (SteinEntityInstancer computes
     * it).
     */
    public static boolean drawWith(int list, float[] m, int o) {
        long inst = instances.reserve(DIRECT_INSTANCE_BYTES);
        long cmd = commands.reserve(COMMAND_BYTES);
        if (inst == -2 || cmd == -2) {
            flushAndRotate();
            inst = instances.reserve(DIRECT_INSTANCE_BYTES);
            cmd = commands.reserve(COMMAND_BYTES);
        }
        if (inst < 0 || cmd < 0) {
            return false;
        }
        float[] t = GLMatrix.textureOrNull();
        long p = instances.address(inst);
        putMatrixRows(p, m, o);
        // Affine texture matrix (the glint): s' = t0 . (s, t, 1), t' = t1 . (s, t, 1).
        putTexture(p + 48, t, 0f, 0f);
        long c = commands.address(cmd);
        MemoryUtil.memPutInt(c, vertexCountOf[list]);
        MemoryUtil.memPutInt(c + 4, 1);
        MemoryUtil.memPutInt(c + 8, firstVertexOf[list]);
        MemoryUtil.memPutInt(c + 12, (int) (inst / DIRECT_INSTANCE_BYTES));
        if (pendingCount == 0) {
            pendingCommands = cmd;
        }
        pendingCount++;
        frameParts++;
        if (recording) {
            if (recLen + 3 > rec.length) {
                rec = Arrays.copyOf(rec, rec.length * 2);
            }
            rec[recLen++] = vertexCountOf[list];
            rec[recLen++] = firstVertexOf[list];
            rec[recLen++] = (int) (inst / DIRECT_INSTANCE_BYTES);
        }
        return true;
    }

    // ---- recording and re-emission (the enchantment glint) ----

    private static boolean recording;
    private static int[] rec = new int[3 * 256];
    private static int recLen;

    /**
     * Texture matrix that applies to the current draws in place of each instance's (null: the
     * instance's).
     */
    private static float[] texOverride;

    private static boolean lastOverride;

    /**
     * Starts recording the commands of the parts drawn from here on (the base of an armor piece).
     */
    public static void startRecording() {
        if (SteinEntityDeferred.capturing()) {
            SteinEntityDeferred.startRecording();
            return;
        }
        recording = active;
        recLen = 0;
    }

    /**
     * Stops recording and returns the recorded commands (triples: vertices, first vertex,
     * instance), or null.
     */
    public static int[] stopRecording() {
        if (SteinEntityDeferred.capturing()) {
            return SteinEntityDeferred.stopRecording();
        }
        boolean was = recording;
        recording = false;
        return was && recLen > 0 ? Arrays.copyOf(rec, recLen) : null;
    }

    /**
     * Draws again parts already drawn in this frame — the same matrices, which are still on the
     * card, so the same depth — with the texture matrix {@code tex} (affine, the glint's) in place
     * of theirs. Without recomputing the pose or walking the model: only the commands, copied.
     */
    public static void replay(int[][] recs, int n, float[] tex) {
        if (SteinEntityDeferred.capturing()) {
            SteinEntityDeferred.replay(recs, n, tex);
            return;
        }
        if (!active) {
            return;
        }
        flush();
        texOverride = tex;
        try {
            for (int i = 0; i < n; i++) {
                int[] r = recs[i];
                for (int k = 0; k + 2 < r.length; k += 3) {
                    long cmd = commands.reserve(COMMAND_BYTES);
                    if (cmd == -2) {
                        flushAndRotate();
                        cmd = commands.reserve(COMMAND_BYTES);
                    }
                    long c = commands.address(cmd);
                    MemoryUtil.memPutInt(c, r[k]);
                    MemoryUtil.memPutInt(c + 4, 1);
                    MemoryUtil.memPutInt(c + 8, r[k + 1]);
                    MemoryUtil.memPutInt(c + 12, r[k + 2]);
                    if (pendingCount == 0) {
                        pendingCommands = cmd;
                    }
                    pendingCount++;
                }
            }
            flush();
        } finally {
            texOverride = null;
        }
    }

    private static boolean lastLight;
    private static boolean lastColorMaterial;

    /** Draws the pending one: before any OpenGL call of the game (the ModelBatch calls it). */
    public static void flush() {
        if (pendingCount == 0 || flushing) {
            return;
        }
        SteinLazyGl.sync();
        // the recorded frame goes to the driver BEFORE binding program, VAO and indirect buffer:
        // replay changes and zeroes those bindings (after binding, the draw came out without an
        // indirect buffer: GL_INVALID_OPERATION in "Post render")
        SteinGlFrame.sync("direct draw of instanced parts");
        flushing = true;
        int n = pendingCount;
        long at = pendingCommands;
        pendingCount = 0;
        try {
            // The state comes from GlStateManager's cache (asking the driver on every batch cost
            // ~5% of the frame); the game does not leave a program bound when drawing models, so
            // the way back is to 0.
            GL20.glUseProgram(program);
            boolean light = SteinGameState.lighting();
            boolean colorMat = SteinGameState.colorMaterial();
            if (light != lastLight) {
                GL20.glUniform1i(uLighting, light ? 1 : 0);
                lastLight = light;
            }
            if (colorMat != lastColorMaterial) {
                GL20.glUniform1i(uColorMaterial, colorMat ? 1 : 0);
                lastColorMaterial = colorMat;
            }
            boolean override = texOverride != null;
            if (override) {
                float[] t = texOverride;
                GL20.glUniform1i(uTexOverride, 1);
                GL20.glUniform4f(uTex0, t[0], t[4], t[12], 0f);
                GL20.glUniform4f(uTex1, t[1], t[5], t[13], 0f);
                lastOverride = true;
            } else if (lastOverride) {
                GL20.glUniform1i(uTexOverride, 0);
                lastOverride = false;
            }
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, (int) commands.handle());
            GL43.glMultiDrawArraysIndirect(GL11.GL_TRIANGLES, at, n, 0);
            GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, 0);
            GL30.glBindVertexArray(0);
            GL20.glUseProgram(0);
            // Stein's pass reads these without asking
            SteinGlBindings.resetAfterOwnDraw();
            frameDraws++;
        } finally {
            flushing = false;
        }
    }

    /** Parts and draws per frame since the last call (for the ModelBatch log). */
    public static String frameStats(int frames) {
        String s =
                frames > 0
                        ? (frameParts / frames)
                                + " instanced parts in "
                                + (frameDraws / frames)
                                + " draws"
                        : "";
        frameParts = 0;
        frameDraws = 0;
        return s;
    }

    public static boolean active() {
        if (!initDone && REQUESTED) {
            ready();
        }
        return active;
    }

    /**
     * Is the parts' geometry on the card (the deferred renderer can use it, even without the direct
     * draw from here)?
     */
    public static boolean geometryReady() {
        if (!initDone && REQUESTED) {
            ready();
        }
        return geometryReady;
    }

    public static boolean hasPending() {
        return pendingCount != 0;
    }

    /** The fixed buffer with the parts' geometry (for {@link SteinEntityDeferred}). */
    static SteinBuffer geometry() {
        return geometry;
    }

    static int firstVertex(int list) {
        return firstVertexOf[list];
    }

    static int vertexCount(int list) {
        return vertexCountOf[list];
    }
}
