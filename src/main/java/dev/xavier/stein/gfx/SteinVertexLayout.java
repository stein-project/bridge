/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * How vertices reach the shader: the bindings (each one a buffer, with its stride and whether it
 * advances per vertex or per instance) and the attributes (shader location, binding, format,
 * offset). Immutable; part of the pipeline key.
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinVertexLayout {

    public static final class Binding {
        public final int index;
        public final int stride;
        public final boolean perInstance;

        Binding(int index, int stride, boolean perInstance) {
            this.index = index;
            this.stride = stride;
            this.perInstance = perInstance;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Binding b
                    && b.index == index
                    && b.stride == stride
                    && b.perInstance == perInstance;
        }

        @Override
        public int hashCode() {
            return Objects.hash(index, stride, perInstance);
        }
    }

    public static final class Attribute {
        public final int location;
        public final int binding;
        public final SteinFormat format;
        public final int offset;

        Attribute(int location, int binding, SteinFormat format, int offset) {
            this.location = location;
            this.binding = binding;
            this.format = format;
            this.offset = offset;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Attribute a
                    && a.location == location
                    && a.binding == binding
                    && a.format == format
                    && a.offset == offset;
        }

        @Override
        public int hashCode() {
            return Objects.hash(location, binding, format, offset);
        }
    }

    public final List<Binding> bindings;
    public final List<Attribute> attributes;

    private SteinVertexLayout(List<Binding> bindings, List<Attribute> attributes) {
        this.bindings = List.copyOf(bindings);
        this.attributes = List.copyOf(attributes);
    }

    private static final SteinVertexLayout EMPTY = new SteinVertexLayout(List.of(), List.of());

    /**
     * No vertices (the shader generates everything, e.g. a full-screen triangle from gl_VertexID).
     */
    public static SteinVertexLayout empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Binding binding(int index) {
        for (Binding b : bindings) {
            if (b.index == index) {
                return b;
            }
        }
        return null;
    }

    public static final class Builder {
        private final List<Binding> bindings = new ArrayList<>();
        private final List<Attribute> attributes = new ArrayList<>();

        public Builder binding(int index, int stride, boolean perInstance) {
            bindings.add(new Binding(index, stride, perInstance));
            return this;
        }

        public Builder attribute(int location, int binding, SteinFormat format, int offset) {
            attributes.add(new Attribute(location, binding, format, offset));
            return this;
        }

        public SteinVertexLayout build() {
            return new SteinVertexLayout(bindings, attributes);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SteinVertexLayout l
                && l.bindings.equals(bindings)
                && l.attributes.equals(attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(bindings, attributes);
    }
}
