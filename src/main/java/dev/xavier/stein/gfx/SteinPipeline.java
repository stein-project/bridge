/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Closed draw state: the shaders, the vertex format, the primitive, blend, depth, cull face and
 * color mask — all together and immutable, as in Vulkan. In place of the on-and-off of old OpenGL:
 * changing state is changing pipeline, and the backend only emits what changes. Compute pipeline:
 * just the compute shader.
 *
 * <p>{@link Desc#texture} and {@link Desc#uniforms} give the slot of each sampler and uniform block
 * by name in the shader (works on OpenGL 4.1, without layout(binding)); the commands bind resources
 * by slot.
 *
 * <p>Pipeline: Stein GFX only.
 */
public abstract class SteinPipeline {

    public enum Topology {
        TRIANGLES,
        TRIANGLE_STRIP,
        LINES,
        POINTS
    }

    public enum Blend {
        /** No blend. */
        NONE,
        /** src * a + dst * (1 - a). */
        ALPHA,
        /** src + dst (the enchantment glint uses this with color). */
        ADDITIVE,
        /** src * color + dst (blend GL_SRC_COLOR, GL_ONE: the 1.8.9 glint's). */
        ADDITIVE_COLOR,
        /** src + dst * (1 - a): premultiplied alpha. */
        PREMULTIPLIED,
        /** Factors given one by one ({@link Builder#blend(Factor, Factor, Factor, Factor)}). */
        CUSTOM
    }

    /**
     * Logical operation on the colour, in place of blending (the game's glLogicOp: the text field's
     * selection uses OR_REVERSE). The same order as OpenGL (GL_CLEAR + ordinal) and Vulkan
     * (VkLogicOp).
     */
    public enum LogicOp {
        CLEAR,
        AND,
        AND_REVERSE,
        COPY,
        AND_INVERTED,
        NO_OP,
        XOR,
        OR,
        NOR,
        EQUIVALENT,
        INVERT,
        OR_REVERSE,
        COPY_INVERTED,
        OR_INVERTED,
        NAND,
        SET
    }

    /** Blend factor (the same set as Vulkan and OpenGL). */
    public enum Factor {
        ZERO,
        ONE,
        SRC_COLOR,
        ONE_MINUS_SRC_COLOR,
        DST_COLOR,
        ONE_MINUS_DST_COLOR,
        SRC_ALPHA,
        ONE_MINUS_SRC_ALPHA,
        DST_ALPHA,
        ONE_MINUS_DST_ALPHA,
        CONSTANT_COLOR,
        ONE_MINUS_CONSTANT_COLOR,
        CONSTANT_ALPHA,
        ONE_MINUS_CONSTANT_ALPHA,
        SRC_ALPHA_SATURATE
    }

    public enum Depth {
        ALWAYS,
        LESS,
        LEQUAL,
        EQUAL,
        GREATER,
        GEQUAL,
        NOTEQUAL,
        NEVER
    }

    public enum Cull {
        NONE,
        BACK,
        FRONT
    }

    public static final class Desc {
        public final String name;
        public final SteinShader vertex;
        public final SteinShader fragment;
        public final SteinShader compute;
        public final SteinVertexLayout layout;
        public final Topology topology;
        public final Blend blend;

        /**
         * With {@link Blend#CUSTOM}: source color, destination color, source alpha, destination
         * alpha; otherwise null.
         */
        public final Factor[] factors;

        /** null = no depth test. */
        public final Depth depthTest;

        public final boolean depthWrite;
        public final Cull cull;
        public final boolean colorWrite;

        /** null = no logical operation; otherwise it replaces the blend. */
        public final LogicOp logicOp;

        /** Alpha write (with color on): the menu panorama writes only RGB. */
        public final boolean alphaWrite;

        /** Sampler name in the shader -> slot. */
        public final Map<String, Integer> textures;

        /** Uniform block name (std140) -> slot. */
        public final Map<String, Integer> uniforms;

        /** Storage block name (std430, compute only) -> slot. */
        public final Map<String, Integer> storage;

        private Desc(Builder b) {
            this.name = b.name;
            this.vertex = b.vertex;
            this.fragment = b.fragment;
            this.compute = b.compute;
            this.layout = b.layout;
            this.topology = b.topology;
            this.blend = b.blend;
            this.factors = b.blend == Blend.CUSTOM ? b.factors.clone() : null;
            this.depthTest = b.depthTest;
            this.depthWrite = b.depthWrite;
            this.cull = b.cull;
            this.colorWrite = b.colorWrite;
            this.logicOp = b.logicOp;
            this.alphaWrite = b.alphaWrite;
            this.textures = Collections.unmodifiableMap(new LinkedHashMap<>(b.textures));
            this.uniforms = Collections.unmodifiableMap(new LinkedHashMap<>(b.uniforms));
            this.storage = Collections.unmodifiableMap(new LinkedHashMap<>(b.storage));
        }

        public boolean isCompute() {
            return compute != null;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Desc d
                    && Objects.equals(d.vertex, vertex)
                    && Objects.equals(d.fragment, fragment)
                    && Objects.equals(d.compute, compute)
                    && Objects.equals(d.layout, layout)
                    && d.topology == topology
                    && d.blend == blend
                    && Arrays.equals(d.factors, factors)
                    && d.depthTest == depthTest
                    && d.depthWrite == depthWrite
                    && d.cull == cull
                    && d.colorWrite == colorWrite
                    && d.logicOp == logicOp
                    && d.alphaWrite == alphaWrite
                    && d.textures.equals(textures)
                    && d.uniforms.equals(uniforms)
                    && d.storage.equals(storage);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(factors)
                    + Objects.hash(
                            vertex,
                            fragment,
                            compute,
                            layout,
                            topology,
                            blend,
                            depthTest,
                            depthWrite,
                            cull,
                            colorWrite,
                            logicOp,
                            alphaWrite,
                            textures,
                            uniforms,
                            storage);
        }
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private SteinShader vertex;
        private SteinShader fragment;
        private SteinShader compute;
        private SteinVertexLayout layout = SteinVertexLayout.empty();
        private Topology topology = Topology.TRIANGLES;
        private Blend blend = Blend.NONE;
        private Factor[] factors;
        private Depth depthTest = Depth.LEQUAL;
        private boolean depthWrite = true;
        private Cull cull = Cull.BACK;
        private boolean colorWrite = true;
        private LogicOp logicOp;
        private boolean alphaWrite = true;
        private final Map<String, Integer> textures = new LinkedHashMap<>();
        private final Map<String, Integer> uniforms = new LinkedHashMap<>();
        private final Map<String, Integer> storage = new LinkedHashMap<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder vertex(SteinShader s) {
            this.vertex = s;
            return this;
        }

        public Builder fragment(SteinShader s) {
            this.fragment = s;
            return this;
        }

        public Builder compute(SteinShader s) {
            this.compute = s;
            return this;
        }

        public Builder layout(SteinVertexLayout l) {
            this.layout = l;
            return this;
        }

        public Builder topology(Topology t) {
            this.topology = t;
            return this;
        }

        public Builder blend(Blend b) {
            this.blend = b;
            return this;
        }

        /**
         * Blend with the given factors (the game uses several combinations; each becomes a
         * pipeline).
         */
        public Builder blend(Factor srcColor, Factor dstColor, Factor srcAlpha, Factor dstAlpha) {
            this.blend = Blend.CUSTOM;
            this.factors = new Factor[] {srcColor, dstColor, srcAlpha, dstAlpha};
            return this;
        }

        /** null turns the depth test off. */
        public Builder depth(Depth test, boolean write) {
            this.depthTest = test;
            this.depthWrite = write;
            return this;
        }

        public Builder cull(Cull c) {
            this.cull = c;
            return this;
        }

        public Builder alphaWrite(boolean w) {
            this.alphaWrite = w;
            return this;
        }

        public Builder colorWrite(boolean w) {
            this.colorWrite = w;
            return this;
        }

        /** null turns it off. */
        public Builder logicOp(LogicOp op) {
            this.logicOp = op;
            return this;
        }

        public Builder texture(String samplerName, int slot) {
            textures.put(samplerName, slot);
            return this;
        }

        public Builder uniforms(String blockName, int slot) {
            uniforms.put(blockName, slot);
            return this;
        }

        public Builder storage(String blockName, int slot) {
            storage.put(blockName, slot);
            return this;
        }

        public Desc build() {
            return new Desc(this);
        }
    }

    protected final Desc desc;

    protected SteinPipeline(Desc desc) {
        this.desc = desc;
    }

    public Desc desc() {
        return desc;
    }

    /**
     * Did it link on the backend? (false: a shader did not compile; the driver log was already
     * printed)
     */
    public abstract boolean valid();

    public abstract void destroy();
}
