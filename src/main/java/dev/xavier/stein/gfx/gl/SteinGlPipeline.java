/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx.gl;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.gfx.SteinPipeline;
import dev.xavier.stein.gfx.SteinShader;
import dev.xavier.stein.gfx.SteinVertexLayout;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL41;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Map;

/**
 * The pipeline on OpenGL: the bound program, the layout's VAO and the fixed state already
 * translated to GL values ({@link SteinGlCommands} only compares and emits what changes).
 *
 * <p>The VAO has two paths. With {@code vertexBinding} (GL 4.3) each attribute's format is recorded
 * in the VAO at creation, and the buffer is attached later through {@code glBindVertexBuffer}. On
 * 4.1 (Mac) the VAO only keeps the divisor: each attribute's pointer is assembled by the commands
 * when the buffer is bound, and the attribute is only enabled then (enabling it without a buffer in
 * the compatibility profile would make the driver read process memory).
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinGlPipeline extends SteinPipeline {

    private final SteinGlDevice device;
    private int program;
    private int vao;

    /**
     * The VAO shared with the pipelines of the same layout (with {@code bindCache}); null = VAO for
     * this one only.
     */
    private SteinGlVao shared;

    /**
     * The layout's bindings and attributes as arrays (the commands' hot path does not walk a list).
     */
    final SteinVertexLayout.Binding[] bindings;

    final SteinVertexLayout.Attribute[] attributes;
    private final boolean compute;

    /** The separate vertex format path (GL 4.3). */
    final boolean vertexBinding;

    // ---- translated fixed state ----
    final int mode;
    final boolean blend;
    final int blendSrc;
    final int blendDst;
    final int blendSrcAlpha;
    final int blendDstAlpha;
    final boolean depthTest;
    final int depthFunc;
    final boolean depthWrite;
    final boolean cull;
    final int cullFace;
    final boolean colorWrite;
    final boolean alphaWrite;

    public SteinGlPipeline(SteinGlDevice device, SteinPipeline.Desc desc) {
        super(desc);
        this.device = device;
        this.compute = desc.isCompute();
        this.vertexBinding = device.caps().vertexBinding;

        this.mode =
                switch (desc.topology) {
                    case TRIANGLES -> GL11.GL_TRIANGLES;
                    case TRIANGLE_STRIP -> GL11.GL_TRIANGLE_STRIP;
                    case LINES -> GL11.GL_LINES;
                    case POINTS -> GL11.GL_POINTS;
                };
        this.blend = desc.blend != Blend.NONE;
        switch (desc.blend) {
            case ALPHA -> {
                blendSrc = GL11.GL_SRC_ALPHA;
                blendDst = GL11.GL_ONE_MINUS_SRC_ALPHA;
            }
            case ADDITIVE -> {
                blendSrc = GL11.GL_ONE;
                blendDst = GL11.GL_ONE;
            }
            case ADDITIVE_COLOR -> {
                blendSrc = GL11.GL_SRC_COLOR;
                blendDst = GL11.GL_ONE;
            }
            case PREMULTIPLIED -> {
                blendSrc = GL11.GL_ONE;
                blendDst = GL11.GL_ONE_MINUS_SRC_ALPHA;
            }
            case CUSTOM -> {
                blendSrc = factor(desc.factors[0]);
                blendDst = factor(desc.factors[1]);
            }
            default -> {
                blendSrc = GL11.GL_ONE;
                blendDst = GL11.GL_ZERO;
            }
        }
        this.blendSrcAlpha = desc.blend == Blend.CUSTOM ? factor(desc.factors[2]) : blendSrc;
        this.blendDstAlpha = desc.blend == Blend.CUSTOM ? factor(desc.factors[3]) : blendDst;
        this.depthTest = desc.depthTest != null;
        this.depthFunc =
                desc.depthTest == null
                        ? GL11.GL_ALWAYS
                        : switch (desc.depthTest) {
                            case ALWAYS -> GL11.GL_ALWAYS;
                            case LESS -> GL11.GL_LESS;
                            case LEQUAL -> GL11.GL_LEQUAL;
                            case EQUAL -> GL11.GL_EQUAL;
                            case GREATER -> GL11.GL_GREATER;
                            case GEQUAL -> GL11.GL_GEQUAL;
                            case NOTEQUAL -> GL11.GL_NOTEQUAL;
                            case NEVER -> GL11.GL_NEVER;
                        };
        this.depthWrite = desc.depthWrite;
        this.cull = desc.cull != Cull.NONE;
        this.cullFace = desc.cull == Cull.FRONT ? GL11.GL_FRONT : GL11.GL_BACK;
        this.colorWrite = desc.colorWrite;
        this.alphaWrite = desc.alphaWrite;

        this.bindings = desc.layout.bindings.toArray(new SteinVertexLayout.Binding[0]);
        this.attributes = desc.layout.attributes.toArray(new SteinVertexLayout.Attribute[0]);

        this.program = link(desc);
        if (program != 0) {
            bindSlots(desc);
            if (!compute) {
                if (SteinGlCommands.BIND_CACHE) {
                    shared = device.acquireVao(desc.layout, () -> buildVao(desc.layout));
                    this.vao = shared.id;
                } else {
                    this.vao = buildVao(desc.layout);
                }
            }
        }
    }

    // ---- program ----

    /**
     * glShaderSource with the text encoded in our own memory: LWJGL's glShaderSource(int,
     * CharSequence) encodes on its stack (64 KB), and the generated GLSL of the pose compute is
     * over 40 KB.
     */
    private static void source(int id, String text) {
        ByteBuffer utf8 = MemoryUtil.memUTF8(text, false);
        PointerBuffer strings = MemoryUtil.memAllocPointer(1);
        IntBuffer lengths = MemoryUtil.memAllocInt(1);
        try {
            strings.put(0, utf8);
            lengths.put(0, utf8.remaining());
            GL20.glShaderSource(id, strings, lengths);
        } finally {
            MemoryUtil.memFree(lengths);
            MemoryUtil.memFree(strings);
            MemoryUtil.memFree(utf8);
        }
    }

    private static int link(Desc desc) {
        SteinShader[] stages =
                desc.isCompute()
                        ? new SteinShader[] {desc.compute}
                        : new SteinShader[] {desc.vertex, desc.fragment};
        int[] shaders = new int[stages.length];
        boolean ok = true;
        for (int i = 0; i < stages.length && ok; i++) {
            SteinShader s = stages[i];
            if (s == null) {
                continue;
            }
            int type =
                    switch (s.stage) {
                        case VERTEX -> GL20.GL_VERTEX_SHADER;
                        case FRAGMENT -> GL20.GL_FRAGMENT_SHADER;
                        case COMPUTE -> GL43.GL_COMPUTE_SHADER;
                    };
            int id = GL20.glCreateShader(type);
            source(id, s.source);
            GL20.glCompileShader(id);
            if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
                Log.info(
                        "gfx",
                        "pipeline "
                                + desc.name
                                + ": "
                                + s.name
                                + ": "
                                + GL20.glGetShaderInfoLog(id));
                GL20.glDeleteShader(id);
                ok = false;
            } else {
                shaders[i] = id;
            }
        }
        if (ok && !desc.isCompute() && desc.vertex == null) {
            Log.info("gfx", "pipeline " + desc.name + ": no vertex shader");
            ok = false;
        }
        int p = 0;
        if (ok) {
            p = GL20.glCreateProgram();
            for (int s : shaders) {
                if (s != 0) {
                    GL20.glAttachShader(p, s);
                }
            }
            GL20.glLinkProgram(p);
            for (int s : shaders) {
                if (s != 0) {
                    GL20.glDetachShader(p, s);
                }
            }
            if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                Log.info("gfx", "pipeline " + desc.name + ": " + GL20.glGetProgramInfoLog(p));
                GL20.glDeleteProgram(p);
                p = 0;
            }
        }
        for (int s : shaders) {
            if (s != 0) {
                GL20.glDeleteShader(s);
            }
        }
        return p;
    }

    /**
     * Slots by name: samplers, uniform blocks and storage blocks (without layout(binding), works on
     * 4.1).
     */
    private void bindSlots(Desc desc) {
        if (!desc.textures.isEmpty()) {
            if (GL.getCapabilities().OpenGL41) {
                for (Map.Entry<String, Integer> e : desc.textures.entrySet()) {
                    int loc = GL20.glGetUniformLocation(program, e.getKey());
                    if (loc >= 0) {
                        GL41.glProgramUniform1i(program, loc, e.getValue());
                    }
                }
            } else {
                int prev = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
                GL20.glUseProgram(program);
                for (Map.Entry<String, Integer> e : desc.textures.entrySet()) {
                    int loc = GL20.glGetUniformLocation(program, e.getKey());
                    if (loc >= 0) {
                        GL20.glUniform1i(loc, e.getValue());
                    }
                }
                GL20.glUseProgram(prev);
            }
        }
        for (Map.Entry<String, Integer> e : desc.uniforms.entrySet()) {
            int index = GL31.glGetUniformBlockIndex(program, e.getKey());
            if (index != GL31.GL_INVALID_INDEX) {
                GL31.glUniformBlockBinding(program, index, e.getValue());
            }
        }
        if (!desc.storage.isEmpty()) {
            if (!device.caps().compute) {
                Log.info(
                        "gfx",
                        "pipeline "
                                + desc.name
                                + ": storage not supported (GL 4.3) on this device");
            } else {
                for (Map.Entry<String, Integer> e : desc.storage.entrySet()) {
                    int index =
                            GL43.glGetProgramResourceIndex(
                                    program, GL43.GL_SHADER_STORAGE_BLOCK, e.getKey());
                    if (index != GL31.GL_INVALID_INDEX) {
                        GL43.glShaderStorageBlockBinding(program, index, e.getValue());
                    }
                }
            }
        }
    }

    // ---- VAO ----

    private int buildVao(SteinVertexLayout layout) {
        int prev = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int id = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(id);
        for (SteinVertexLayout.Attribute a : layout.attributes) {
            SteinVertexLayout.Binding b = layout.binding(a.binding);
            boolean perInstance = b != null && b.perInstance;
            if (vertexBinding) {
                int size = SteinGlFormats.attribSize(a.format);
                int type = SteinGlFormats.attribType(a.format);
                if (SteinGlFormats.attribInteger(a.format)) {
                    GL43.glVertexAttribIFormat(a.location, size, type, a.offset);
                } else {
                    GL43.glVertexAttribFormat(
                            a.location,
                            size,
                            type,
                            SteinGlFormats.attribNormalized(a.format),
                            a.offset);
                }
                GL43.glVertexAttribBinding(a.location, a.binding);
                GL20.glEnableVertexAttribArray(a.location);
            } else {
                GL33.glVertexAttribDivisor(a.location, perInstance ? 1 : 0);
            }
        }
        if (vertexBinding) {
            for (SteinVertexLayout.Binding b : layout.bindings) {
                GL43.glVertexBindingDivisor(b.index, b.perInstance ? 1 : 0);
            }
        }
        GL30.glBindVertexArray(prev);
        return id;
    }

    // ---- for the commands ----

    static int factor(SteinPipeline.Factor f) {
        return switch (f) {
            case ZERO -> GL11.GL_ZERO;
            case ONE -> GL11.GL_ONE;
            case SRC_COLOR -> GL11.GL_SRC_COLOR;
            case ONE_MINUS_SRC_COLOR -> GL11.GL_ONE_MINUS_SRC_COLOR;
            case DST_COLOR -> GL11.GL_DST_COLOR;
            case ONE_MINUS_DST_COLOR -> GL11.GL_ONE_MINUS_DST_COLOR;
            case SRC_ALPHA -> GL11.GL_SRC_ALPHA;
            case ONE_MINUS_SRC_ALPHA -> GL11.GL_ONE_MINUS_SRC_ALPHA;
            case DST_ALPHA -> GL11.GL_DST_ALPHA;
            case ONE_MINUS_DST_ALPHA -> GL11.GL_ONE_MINUS_DST_ALPHA;
            case CONSTANT_COLOR -> GL14.GL_CONSTANT_COLOR;
            case ONE_MINUS_CONSTANT_COLOR -> GL14.GL_ONE_MINUS_CONSTANT_COLOR;
            case CONSTANT_ALPHA -> GL14.GL_CONSTANT_ALPHA;
            case ONE_MINUS_CONSTANT_ALPHA -> GL14.GL_ONE_MINUS_CONSTANT_ALPHA;
            case SRC_ALPHA_SATURATE -> GL11.GL_SRC_ALPHA_SATURATE;
        };
    }

    int program() {
        return program;
    }

    int vao() {
        return vao;
    }

    /** The shared VAO (what is bound to it is tracked), or null without {@code bindCache}. */
    SteinGlVao shared() {
        return shared;
    }

    boolean isCompute() {
        return compute;
    }

    @Override
    public boolean valid() {
        return program != 0;
    }

    @Override
    public void destroy() {
        SteinGlFrame.sync("pipeline deleted");
        if (shared != null) {
            device.releaseVao(desc.layout, shared);
            shared = null;
            vao = 0;
        } else if (vao != 0) {
            GL30.glDeleteVertexArrays(vao);
            vao = 0;
        }
        if (program != 0) {
            GL20.glDeleteProgram(program);
            program = 0;
        }
    }
}
