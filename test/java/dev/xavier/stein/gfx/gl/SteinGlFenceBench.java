/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

// How to run (standalone, outside the game; not part of the Bridge jar). After tools/build.sh:
//   java -cp "build/classes;build/test;libs/*" dev.xavier.stein.gfx.gl.SteinGlFenceBench

package dev.xavier.stein.gfx.gl;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Out-of-game proof of the gap before the terrain's translucent pass: three "layers" (6, 2 and 1
 * full-screen quads sampling an atlas with mips, the last one blended and without writing depth)
 * with timestamps between them and, between the second and the third, depending on the mode:
 *
 * <ul>
 *   <li>{@code param}: changes the atlas filter (the game does this around the cutout layer);
 *   <li>{@code sampler}: switches by sampler;
 *   <li>{@code fence} / {@code fenceN}: one or N consecutive glFenceSync (the end of the entity
 *       pass closing the rings);
 *   <li>{@code none}.
 * </ul>
 *
 * Arguments: mode, atlas width (default 1024), {@code mut} (allocated like the game, glTexImage2D
 * per level) or {@code imm}, microseconds of CPU after the fences (default 0). Measured on the RX
 * 7600 ("switch B" is the interval): param/sampler/none 0 us; fence 19 us; fence3 127 us; fence
 * with 100 us of CPU afterwards, 75 us.
 *
 * <pre>
 * java --enable-native-access=ALL-UNNAMED -cp "&lt;classes&gt;;libs/*"
 * dev.xavier.stein.gfx.gl.SteinGlFenceBench fence3
 * </pre>
 */
public final class SteinGlFenceBench {

    private SteinGlFenceBench() {}

    private static int program(String vs, String fs) {
        int v = GL20.glCreateShader(GL20.GL_VERTEX_SHADER);
        GL20.glShaderSource(v, vs);
        GL20.glCompileShader(v);
        int f = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER);
        GL20.glShaderSource(f, fs);
        GL20.glCompileShader(f);
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, v);
        GL20.glAttachShader(p, f);
        GL20.glLinkProgram(p);
        return p;
    }

    public static void main(String[] a) {
        String mode = a.length > 0 ? a[0] : "fence3";
        int aw = a.length > 1 ? Integer.parseInt(a[1]) : 1024;
        int ah = aw / 2;
        boolean mutable = a.length <= 2 || a[2].equals("mut");
        long cpuNs = a.length > 3 ? Long.parseLong(a[3]) * 1000 : 0;
        GLFW.glfwInit();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 6);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window = GLFW.glfwCreateWindow(64, 64, "bench", 0, 0);
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
        int w = 854;
        int h = 480;
        int color = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
        GL45.glTextureStorage2D(color, 1, GL11.GL_RGBA8, w, h);
        int depth = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
        GL45.glTextureStorage2D(depth, 1, GL30.GL_DEPTH24_STENCIL8, w, h);
        int fbo = GL45.glCreateFramebuffers();
        GL45.glNamedFramebufferTexture(fbo, GL30.GL_COLOR_ATTACHMENT0, color, 0);
        GL45.glNamedFramebufferTexture(fbo, GL30.GL_DEPTH_STENCIL_ATTACHMENT, depth, 0);
        ByteBuffer px = MemoryUtil.memAlloc(aw * ah * 4);
        for (int i = 0; i < px.capacity(); i++) {
            px.put(i, (byte) (i * 31));
        }
        int atlas;
        if (mutable) {
            atlas = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlas);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 4);
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MIN_LOD, 0f);
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LOD, 4f);
            GL11.glTexParameterf(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_LOD_BIAS, 0f);
            for (int l = 0; l <= 4; l++) {
                GL11.glTexImage2D(
                        GL11.GL_TEXTURE_2D,
                        l,
                        GL11.GL_RGBA,
                        aw >> l,
                        ah >> l,
                        0,
                        GL12.GL_BGRA,
                        GL12.GL_UNSIGNED_INT_8_8_8_8_REV,
                        (ByteBuffer) null);
                GL11.glTexSubImage2D(
                        GL11.GL_TEXTURE_2D,
                        l,
                        0,
                        0,
                        aw >> l,
                        ah >> l,
                        GL12.GL_BGRA,
                        GL12.GL_UNSIGNED_INT_8_8_8_8_REV,
                        px);
            }
        } else {
            atlas = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
            GL45.glTextureStorage2D(atlas, 5, GL11.GL_RGBA8, aw, ah);
            GL45.glTextureSubImage2D(
                    atlas, 0, 0, 0, aw, ah, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
            GL45.glGenerateTextureMipmap(atlas);
        }
        MemoryUtil.memFree(px);
        GL45.glTextureParameteri(atlas, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST_MIPMAP_LINEAR);
        GL45.glTextureParameteri(atlas, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        int nearest = GL33.glGenSamplers();
        GL33.glSamplerParameteri(nearest, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL33.glSamplerParameteri(nearest, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL30.glBindVertexArray(GL30.glGenVertexArrays());
        int p =
                program(
                        "#version 460\n"
                                + "out vec2 uv; uniform float z; void main(){ vec2 q ="
                                + " vec2((gl_VertexID << 1) & 2, gl_VertexID & 2); uv = q * 8.0;"
                                + " gl_Position = vec4(q * 2.0 - 1.0, z, 1.0); }",
                        "#version 460\n"
                                + "in vec2 uv; uniform sampler2D t; out vec4 c; void main(){ c ="
                                + " texture(t, uv) * 0.5; }");
        GL20.glUseProgram(p);
        int zLoc = GL20.glGetUniformLocation(p, "z");
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL11.glViewport(0, 0, w, h);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, atlas);
        int[] q = new int[7];
        for (int i = 0; i < q.length; i++) {
            q[i] =
                    GL45.glCreateQueries(
                            GL33.GL_TIMESTAMP); // one at a time (a bulk glGenQueries repeats names)
        }
        long[] acc = new long[q.length];
        List<Long> fences = new ArrayList<>();
        int frames = 3000;
        for (int f = 0; f < frames + 100; f++) {
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(true);
            GL11.glDisable(GL11.GL_BLEND);
            GL33.glQueryCounter(q[0], GL33.GL_TIMESTAMP);
            for (int i = 0; i < 6; i++) {
                GL20.glUniform1f(zLoc, 0.5f - i * 0.01f);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            }
            GL33.glQueryCounter(q[1], GL33.GL_TIMESTAMP);
            if (mode.equals("param")) {
                GL45.glTextureParameteri(atlas, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            } else if (mode.equals("sampler")) {
                GL33.glBindSampler(0, nearest);
            }
            GL33.glQueryCounter(q[2], GL33.GL_TIMESTAMP);
            for (int i = 0; i < 2; i++) {
                GL20.glUniform1f(zLoc, 0.3f - i * 0.01f);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            }
            GL33.glQueryCounter(q[3], GL33.GL_TIMESTAMP);
            if (mode.equals("param")) {
                GL45.glTextureParameteri(
                        atlas, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST_MIPMAP_LINEAR);
            } else if (mode.equals("sampler")) {
                GL33.glBindSampler(0, 0);
            } else if (mode.startsWith("fence")) {
                int n = mode.length() > 5 ? Integer.parseInt(mode.substring(5)) : 1;
                for (int k = 0; k < n; k++) {
                    fences.add(GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0));
                }
                long until =
                        System.nanoTime()
                                + cpuNs; // the CPU keeps going (the game goes through the entities)
                while (System.nanoTime() < until) {
                    Thread.onSpinWait();
                }
            }
            GL33.glQueryCounter(q[4], GL33.GL_TIMESTAMP);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glDepthMask(false);
            GL33.glQueryCounter(q[5], GL33.GL_TIMESTAMP);
            GL20.glUniform1f(zLoc, 0.1f);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            GL33.glQueryCounter(q[6], GL33.GL_TIMESTAMP);
            GL11.glFinish();
            for (long s : fences) {
                GL32.glDeleteSync(s);
            }
            fences.clear();
            if (f >= 100) {
                long prev = GL33.glGetQueryObjecti64(q[0], GL15.GL_QUERY_RESULT);
                for (int i = 1; i < q.length; i++) {
                    long t = GL33.glGetQueryObjecti64(q[i], GL15.GL_QUERY_RESULT);
                    acc[i] += t - prev;
                    prev = t;
                }
            }
        }
        String[] names = {"", "solid", "swap A", "cutout", "swap B", "blend state", "translucent"};
        StringBuilder b = new StringBuilder("[gfx] fence bench, mode " + mode + ", us:");
        for (int i = 1; i < q.length; i++) {
            b.append(String.format(" %s %.1f |", names[i], acc[i] / 1e3 / frames));
        }
        System.out.println(b);
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
    }
}
