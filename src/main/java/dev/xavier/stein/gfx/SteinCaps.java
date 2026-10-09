/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx;

/**
 * What the device can do. The renderer picks its path from here, not from the version: a GPU at
 * level 4.1 (Mac) draws without indirect drawing and without a persistent buffer; a 4.5/4.6 uses
 * everything.
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinCaps {

    /** Backend name ("OpenGL 4.6", "Vulkan 1.3"...) and the GPU's, for the log. */
    public final String backend;

    public final String renderer;

    /**
     * Persistently and coherently mapped buffer (GL 4.4 / ARB_buffer_storage; always on Vulkan).
     */
    public final boolean persistentMapping;

    /** Multi-draw indirect (GL 4.3). */
    public final boolean multiDrawIndirect;

    /** Base instance in drawing (GL 4.2). */
    public final boolean baseInstance;

    /** Vertex format separate from the buffer (GL 4.3 vertex_attrib_binding). */
    public final boolean vertexBinding;

    /** Compute shader and storage buffer (GL 4.3). */
    public final boolean compute;

    /** Immutable texture storage (GL 4.2 texture_storage). */
    public final boolean textureStorage;

    /** GPU-side texture-to-texture copy (GL 4.3 copy_image). */
    public final boolean copyImage;

    /**
     * Number of indirect draws read from a buffer, decided by the GPU (GL 4.6 indirect_parameters).
     */
    public final boolean indirectCount;

    /** Anisotropic filtering, and its maximum. */
    public final float maxAnisotropy;

    /** Maximum layers in a texture array. */
    public final int maxArrayLayers;

    /** Maximum texture size. */
    public final int maxTextureSize;

    /** Uniform buffer offset alignment. */
    public final int uniformAlignment;

    public SteinCaps(
            String backend,
            String renderer,
            boolean persistentMapping,
            boolean multiDrawIndirect,
            boolean baseInstance,
            boolean vertexBinding,
            boolean compute,
            boolean textureStorage,
            boolean copyImage,
            boolean indirectCount,
            float maxAnisotropy,
            int maxArrayLayers,
            int maxTextureSize,
            int uniformAlignment) {
        this.backend = backend;
        this.renderer = renderer;
        this.persistentMapping = persistentMapping;
        this.multiDrawIndirect = multiDrawIndirect;
        this.baseInstance = baseInstance;
        this.vertexBinding = vertexBinding;
        this.compute = compute;
        this.textureStorage = textureStorage;
        this.copyImage = copyImage;
        this.indirectCount = indirectCount;
        this.maxAnisotropy = maxAnisotropy;
        this.maxArrayLayers = maxArrayLayers;
        this.maxTextureSize = maxTextureSize;
        this.uniformAlignment = uniformAlignment;
    }

    @Override
    public String toString() {
        return backend
                + " ("
                + renderer
                + "): persistent "
                + persistentMapping
                + ", indirect "
                + multiDrawIndirect
                + ", indirect count "
                + indirectCount
                + ", base instance "
                + baseInstance
                + ", vertex binding "
                + vertexBinding
                + ", compute "
                + compute
                + ", copy image "
                + copyImage
                + ", layers "
                + maxArrayLayers
                + ", anisotropy "
                + maxAnisotropy;
    }
}
