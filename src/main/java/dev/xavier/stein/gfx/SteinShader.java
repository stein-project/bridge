/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.core.Log;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A shader stage of the Stein graphics API. The renderer's ones come from a single source
 * (src/main/shaders, Vulkan-style GLSL), compiled at build time to SPIR-V and, through SPIRV-Cross,
 * to OpenGL's GLSL (tools/ShaderBuild.java); both are kept here: {@link #source} (what the OpenGL
 * backend compiles) and {@link #spirv} (the Vulkan one). {@link #load} reads by the output name in
 * shaders.list.
 *
 * <p>{@link #program} is the OpenGL shortcut used before the complete API (the {@link
 * SteinModelInstancer}): it accepts only the vertex stage, and then, in the compatibility profile,
 * the fragment stage stays the game's.
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinShader {

    public enum Stage {
        VERTEX,
        FRAGMENT,
        COMPUTE
    }

    public final Stage stage;
    public final String name;
    public final String source;

    /** The SPIR-V of the same shader (null in the ones assembled from text). */
    public final byte[] spirv;

    /**
     * The shader's resources with each one's set and binding (the build's reflection; empty in the
     * ones assembled from text).
     */
    public final List<Resource> resources;

    /**
     * A uniform or storage block, or a sampler, by its GLSL name ({@code arrayed}: sampler2DArray).
     */
    public record Resource(Kind kind, String name, int set, int binding, boolean arrayed) {
        public enum Kind {
            UNIFORM,
            STORAGE,
            TEXTURE
        }
    }

    public SteinShader(Stage stage, String name, String source) {
        this(stage, name, source, null, List.of());
    }

    private SteinShader(
            Stage stage, String name, String source, byte[] spirv, List<Resource> resources) {
        this.stage = stage;
        this.name = name;
        this.source = source;
        this.spirv = spirv;
        this.resources = resources;
    }

    /**
     * The shader compiled at build time with that output name (e.g. "terrain.vert"), from
     * stein/shaders/ in the jar.
     */
    public static SteinShader load(Stage stage, String name) {
        String dir = "/stein/shaders/" + name;
        try (InputStream glsl = SteinShader.class.getResourceAsStream(dir + ".glsl");
                InputStream spv = SteinShader.class.getResourceAsStream(dir + ".spv");
                InputStream res = SteinShader.class.getResourceAsStream(dir + ".res")) {
            if (glsl == null) {
                throw new IllegalStateException(
                        "shader " + name + " is not in the jar (run tools/build.sh)");
            }
            String source = new String(glsl.readAllBytes(), StandardCharsets.UTF_8);
            List<Resource> resources = new ArrayList<>();
            if (res != null) {
                for (String line :
                        new String(res.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                    String[] f = line.trim().split(" ");
                    if (f.length >= 4) {
                        resources.add(
                                new Resource(
                                        Resource.Kind.valueOf(f[0].toUpperCase(Locale.ROOT)),
                                        f[1],
                                        Integer.parseInt(f[2]),
                                        Integer.parseInt(f[3]),
                                        f.length > 4 && f[4].equals("2darray")));
                    }
                }
            }
            return new SteinShader(
                    stage,
                    name,
                    source,
                    spv == null ? null : spv.readAllBytes(),
                    List.copyOf(resources));
        } catch (IOException e) {
            throw new IllegalStateException("shader " + name + ": " + e, e);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SteinShader s && s.stage == stage && s.source.equals(source);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stage, source);
    }

    // ---- OpenGL shortcut (vertex-only program) ----

    /**
     * Links the given stages (fragment may be null); 0 if it does not compile or link, with the
     * driver's log.
     */
    public static int program(String name, String vertex, String fragment) {
        int vs = stage(name, GL20.GL_VERTEX_SHADER, vertex);
        int fs = fragment == null ? 0 : stage(name, GL20.GL_FRAGMENT_SHADER, fragment);
        if (vs == 0 || fragment != null && fs == 0) {
            GL20.glDeleteShader(vs);
            GL20.glDeleteShader(fs);
            return 0;
        }
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, vs);
        if (fs != 0) {
            GL20.glAttachShader(p, fs);
        }
        GL20.glLinkProgram(p);
        GL20.glDetachShader(p, vs);
        GL20.glDeleteShader(vs);
        if (fs != 0) {
            GL20.glDetachShader(p, fs);
            GL20.glDeleteShader(fs);
        }
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            Log.info("gfx", name + " failed to link: " + GL20.glGetProgramInfoLog(p));
            GL20.glDeleteProgram(p);
            return 0;
        }
        return p;
    }

    private static int stage(String name, int type, String source) {
        int s = GL20.glCreateShader(type);
        GL20.glShaderSource(s, source);
        GL20.glCompileShader(s);
        if (GL20.glGetShaderi(s, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            Log.info("gfx", name + " failed to compile: " + GL20.glGetShaderInfoLog(s));
            GL20.glDeleteShader(s);
            return 0;
        }
        return s;
    }
}
