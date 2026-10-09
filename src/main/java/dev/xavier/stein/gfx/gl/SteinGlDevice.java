/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.gl;

import dev.xavier.stein.gfx.SteinBuffer;
import dev.xavier.stein.gfx.SteinCaps;
import dev.xavier.stein.gfx.SteinCommands;
import dev.xavier.stein.gfx.SteinDevice;
import dev.xavier.stein.gfx.SteinFence;
import dev.xavier.stein.gfx.SteinPipeline;
import dev.xavier.stein.gfx.SteinRenderPass;
import dev.xavier.stein.gfx.SteinRenderTarget;
import dev.xavier.stein.gfx.SteinSampler;
import dev.xavier.stein.gfx.SteinTexture;
import dev.xavier.stein.gfx.SteinVertexLayout;

import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLCapabilities;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * The OpenGL backend of the Stein graphics API. Created by {@link SteinDevice#get()} on the game
 * thread, with the context already active: what the GPU can do comes from LWJGL's capabilities, and
 * each resource is an OpenGL object underneath.
 *
 * <p>The game caches part of the OpenGL state (bound textures, program, framebuffer): every
 * resource here that binds something to create or write returns what was bound before.
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinGlDevice extends SteinDevice {

    /** Uniform buffer offset alignment assumed when the driver cannot be asked (GL 3.0). */
    private static final int DEFAULT_UNIFORM_ALIGNMENT = 256;

    private final SteinCaps caps;

    public SteinGlDevice() {
        GLCapabilities gl = GL.getCapabilities();
        String version =
                GL11.glGetInteger(GL30.GL_MAJOR_VERSION)
                        + "."
                        + GL11.glGetInteger(GL30.GL_MINOR_VERSION);
        String renderer = GL11.glGetString(GL11.GL_RENDERER);
        boolean persistent = gl.OpenGL44 || gl.GL_ARB_buffer_storage;
        float aniso = 1f;
        if (gl.GL_EXT_texture_filter_anisotropic) {
            aniso =
                    Math.max(
                            1f,
                            GL11.glGetFloat(
                                    EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT));
        }
        int layers = gl.OpenGL30 ? GL11.glGetInteger(GL30.GL_MAX_ARRAY_TEXTURE_LAYERS) : 0;
        int maxTex = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
        int align =
                gl.OpenGL31
                        ? GL11.glGetInteger(GL31.GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT)
                        : DEFAULT_UNIFORM_ALIGNMENT;
        // -Dsteinbridge.gfx.tier=41: pretends to be the Mac's GPU (without the 4.2+ features) to
        // test the fallback path
        boolean modern = !"41".equals(SteinGlOptions.TIER);
        if (!modern) {
            version += " as 4.1";
        }
        this.caps =
                new SteinCaps(
                        "OpenGL " + version,
                        renderer == null ? "?" : renderer,
                        persistent && modern,
                        gl.OpenGL43 && modern,
                        gl.OpenGL42 && modern,
                        gl.OpenGL43 && modern,
                        gl.OpenGL43 && modern,
                        gl.OpenGL42 && modern,
                        gl.OpenGL43 && modern,
                        (gl.OpenGL46 || gl.GL_ARB_indirect_parameters) && modern,
                        aniso,
                        layers,
                        maxTex,
                        Math.max(1, align));
        SteinGlFrame.decide(this);
    }

    @Override
    public SteinCaps caps() {
        return caps;
    }

    // ---- resources ----

    @Override
    public SteinBuffer createBuffer(SteinBuffer.Desc desc) {
        return new SteinGlBuffer(this, desc);
    }

    @Override
    public SteinTexture createTexture(SteinTexture.Desc desc) {
        return new SteinGlTexture(this, desc);
    }

    @Override
    public SteinSampler createSampler(SteinSampler.Desc desc) {
        return new SteinGlSampler(this, desc);
    }

    @Override
    public SteinTexture importTexture(SteinTexture.Desc desc, long handle) {
        return new SteinGlTexture(desc, (int) handle);
    }

    @Override
    public void copyTexture(
            SteinTexture src, int srcLayer, SteinTexture dst, int dstLayer, int w, int h) {
        if (!caps.copyImage) {
            SteinGlFrame.sync("texture copy without GL 4.3");
            blitTexture(src, srcLayer, dst, dstLayer, w, h);
            return;
        }
        if (SteinGlFrame.pending()) {
            // in the recorded frame's order (the pass recorded earlier reads the previous texture)
            SteinGlFrame.copyTexture(
                    (int) src.handle(),
                    ((SteinGlTexture) src).target(),
                    srcLayer,
                    (int) dst.handle(),
                    ((SteinGlTexture) dst).target(),
                    dstLayer,
                    w,
                    h);
            return;
        }
        GL43.glCopyImageSubData(
                (int) src.handle(),
                ((SteinGlTexture) src).target(),
                0,
                0,
                0,
                srcLayer,
                (int) dst.handle(),
                ((SteinGlTexture) dst).target(),
                0,
                0,
                0,
                dstLayer,
                w,
                h,
                1);
    }

    @Override
    public void copyBuffer(
            SteinBuffer src, long srcOffset, SteinBuffer dst, long dstOffset, long bytes) {
        if (SteinGlFrame.pending()) {
            SteinGlFrame.copyBuffer(
                    (int) src.handle(), srcOffset, (int) dst.handle(), dstOffset, bytes);
            return;
        }
        // on the copy targets (the game does not use them), restored as they were (tracked in
        // SteinGlBindings: only Stein uses the copy targets)
        int prevRead = SteinGlBindings.copyRead();
        int prevWrite = SteinGlBindings.copyWrite();
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, (int) src.handle());
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, (int) dst.handle());
        GL31.glCopyBufferSubData(
                GL31.GL_COPY_READ_BUFFER, GL31.GL_COPY_WRITE_BUFFER, srcOffset, dstOffset, bytes);
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, prevRead);
        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, prevWrite);
    }

    /**
     * Without GL 4.3: the copy through two framebuffers and a blit (GL 3.0), restoring the bound
     * framebuffers.
     */
    private static void blitTexture(
            SteinTexture src, int srcLayer, SteinTexture dst, int dstLayer, int w, int h) {
        int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        int read = GL30.glGenFramebuffers();
        int draw = GL30.glGenFramebuffers();
        try {
            if (scissor) {
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            attachLayer(GL30.GL_READ_FRAMEBUFFER, src, srcLayer);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
            attachLayer(GL30.GL_DRAW_FRAMEBUFFER, dst, dstLayer);
            GL20.glDrawBuffers(GL30.GL_COLOR_ATTACHMENT0);
            GL30.glBlitFramebuffer(
                    0, 0, w, h, 0, 0, w, h, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        } finally {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
            GL30.glDeleteFramebuffers(read);
            GL30.glDeleteFramebuffers(draw);
            if (scissor) {
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
            }
        }
    }

    private static void attachLayer(int fb, SteinTexture t, int layer) {
        if (t.desc().kind == SteinTexture.Kind.TEXTURE_2D_ARRAY) {
            GL30.glFramebufferTextureLayer(
                    fb, GL30.GL_COLOR_ATTACHMENT0, (int) t.handle(), 0, layer);
        } else {
            GL30.glFramebufferTexture2D(
                    fb, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, (int) t.handle(), 0);
        }
    }

    @Override
    public SteinPipeline createPipeline(SteinPipeline.Desc desc) {
        return new SteinGlPipeline(this, desc);
    }

    /**
     * VAOs shared by layout ({@code bindCache}): the layout is immutable and has value-based
     * equals.
     */
    private final Map<SteinVertexLayout, SteinGlVao> vaos = new HashMap<>();

    /** The layout's VAO (created by {@code build} the first time), with one more reference. */
    SteinGlVao acquireVao(SteinVertexLayout layout, IntSupplier build) {
        SteinGlVao v = vaos.get(layout);
        if (v == null) {
            v = new SteinGlVao(build.getAsInt());
            vaos.put(layout, v);
        }
        v.refs++;
        return v;
    }

    /** Releases a reference; the last one deletes the VAO. */
    void releaseVao(SteinVertexLayout layout, SteinGlVao v) {
        if (--v.refs <= 0) {
            vaos.remove(layout, v);
            GL30.glDeleteVertexArrays(v.id);
        }
    }

    @Override
    public SteinRenderTarget createTarget(SteinTexture[] colors, SteinTexture depth) {
        return new SteinGlRenderTarget(colors, depth);
    }

    @Override
    public SteinRenderTarget gameTarget() {
        return SteinGlRenderTarget.game();
    }

    // ---- work ----

    @Override
    public SteinCommands beginPass(SteinRenderPass pass) {
        // recorded frame: the pass becomes a command list, sent at the end of the frame or at the
        // next sync
        return SteinGlFrame.recording()
                ? new SteinGlRecorder(this, pass)
                : new SteinGlCommands(this, pass);
    }

    @Override
    public SteinFence fence() {
        return new SteinGlFence();
    }

    @Override
    public void readBuffer(SteinBuffer buffer, long offset, ByteBuffer out) {
        // glGetBufferSubData waits for what was requested before (valid with the persistently
        // mapped buffer); what a compute wrote has already passed the dispatch barrier. On the read
        // copy target, restored as it was.
        SteinGlFrame.sync("API readBuffer");
        int prev = SteinGlBindings.copyRead();
        GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, (int) buffer.handle());
        try {
            GL15.glGetBufferSubData(GL31.GL_COPY_READ_BUFFER, offset, out);
        } finally {
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, prev);
        }
    }

    @Override
    public void readPixels(SteinTexture texture, int x, int y, int w, int h, ByteBuffer out) {
        SteinGlFrame.sync("API readPixels");
        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousPack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
        int previousAlign = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        int fbo = GL30.glGenFramebuffers();
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
            SteinGlRenderTarget.attach(
                    GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, texture);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            if (previousPack != 0) {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            }
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
            GL11.glReadPixels(x, y, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, out);
        } finally {
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, previousAlign);
            if (previousPack != 0) {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousPack);
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            GL30.glDeleteFramebuffers(fbo);
        }
    }
}
