/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx.gl;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.gfx.SteinRenderTarget;
import dev.xavier.stein.gfx.SteinTexture;

import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL45;

import java.util.Arrays;

/**
 * OpenGL render target: a framebuffer with our textures, or the framebuffer the game is drawing to
 * ({@link #game()}, which is not ours and is never deleted from here).
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinGlRenderTarget extends SteinRenderTarget {

    private final SteinTexture[] colors;
    private final SteinTexture depth;
    private final boolean owned;
    private final int width;
    private final int height;
    private int fbo;

    private SteinGlRenderTarget(
            int fbo,
            boolean owned,
            SteinTexture[] colors,
            SteinTexture depth,
            int width,
            int height) {
        this.fbo = fbo;
        this.owned = owned;
        this.colors = colors;
        this.depth = depth;
        this.width = width;
        this.height = height;
    }

    /**
     * New framebuffer with these textures (colors in {@code GL_COLOR_ATTACHMENTi}, depth separate).
     */
    SteinGlRenderTarget(SteinTexture[] colors, SteinTexture depth) {
        this(
                GL30.glGenFramebuffers(),
                true,
                colors == null ? new SteinTexture[0] : colors.clone(),
                depth,
                sizeOf(colors, depth, true),
                sizeOf(colors, depth, false));
        int previous = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        try {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);
            int n = 0;
            int[] buffers = new int[this.colors.length];
            for (int i = 0; i < this.colors.length; i++) {
                if (this.colors[i] != null) {
                    attach(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0 + i, this.colors[i]);
                    buffers[i] = GL30.GL_COLOR_ATTACHMENT0 + i;
                    n = i + 1;
                } else {
                    buffers[i] = GL11.GL_NONE;
                }
            }
            if (depth != null) {
                attach(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, depth);
            }
            if (n == 0) {
                GL11.glDrawBuffer(GL11.GL_NONE);
                GL11.glReadBuffer(GL11.GL_NONE);
            } else {
                GL20.glDrawBuffers(Arrays.copyOf(buffers, n));
            }
            int status = GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                Log.info("gfx", "incomplete target: 0x" + Integer.toHexString(status));
            }
        } finally {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previous);
        }
    }

    private static int sizeOf(SteinTexture[] colors, SteinTexture depth, boolean width) {
        if (colors != null) {
            for (SteinTexture t : colors) {
                if (t != null) {
                    return width ? t.width() : t.height();
                }
            }
        }
        if (depth != null) {
            return width ? depth.width() : depth.height();
        }
        return 0;
    }

    /**
     * Attaches the texture (layer 0, if it is an array) to the {@code attachment} point of the
     * framebuffer bound at {@code fb}.
     */
    static void attach(int fb, int attachment, SteinTexture texture) {
        int id = (int) texture.handle();
        if (texture.desc().kind == SteinTexture.Kind.TEXTURE_2D_ARRAY) {
            GL30.glFramebufferTextureLayer(fb, attachment, id, 0, 0);
        } else {
            GL30.glFramebufferTexture2D(fb, attachment, GL11.GL_TEXTURE_2D, id, 0);
        }
    }

    /**
     * The game's framebuffer: the tracked draw framebuffer ({@link SteinGlBindings}) and the window
     * size the game uses (Display.getWidth/getHeight, the window framebuffer that the LWJGL 2 shim
     * keeps), with no query or GLFW call. The game only rebuilds its framebuffer after seeing the
     * window change (at the end of the frame, before the next), so the key (framebuffer, window
     * size) changes together; the attachment size is only read when it changes.
     */
    static SteinGlRenderTarget game() {
        int fb = SteinGlBindings.drawFbo();
        int w = Display.getWidth();
        int h = Display.getHeight();
        SteinGlRenderTarget c = cached;
        if (c != null && c.fbo == fb && cachedW == w && cachedH == h) {
            return c;
        }
        int[] size = fb == 0 ? null : attachmentSize();
        c =
                new SteinGlRenderTarget(
                        fb,
                        false,
                        new SteinTexture[0],
                        null,
                        size != null ? size[0] : w,
                        size != null ? size[1] : h);
        cached = c;
        cachedW = w;
        cachedH = h;
        return c;
    }

    private static SteinGlRenderTarget cached;
    private static int cachedW;
    private static int cachedH;

    /**
     * Size of color 0 (or of the depth) of the bound draw framebuffer; null if it cannot be known.
     */
    private static int[] attachmentSize() {
        int[] s = attachmentSize(GL30.GL_COLOR_ATTACHMENT0);
        return s != null ? s : attachmentSize(GL30.GL_DEPTH_ATTACHMENT);
    }

    private static int[] attachmentSize(int attachment) {
        int fb = GL30.GL_DRAW_FRAMEBUFFER;
        int type =
                GL30.glGetFramebufferAttachmentParameteri(
                        fb, attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        if (type == GL11.GL_NONE) {
            return null;
        }
        int name =
                GL30.glGetFramebufferAttachmentParameteri(
                        fb, attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        boolean dsa = GL.getCapabilities().OpenGL45;
        if (type == GL30.GL_RENDERBUFFER) {
            if (dsa) {
                return new int[] {
                    GL45.glGetNamedRenderbufferParameteri(name, GL30.GL_RENDERBUFFER_WIDTH),
                    GL45.glGetNamedRenderbufferParameteri(name, GL30.GL_RENDERBUFFER_HEIGHT)
                };
            }
            int previous = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, name);
            int[] s = {
                GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_WIDTH),
                GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_HEIGHT)
            };
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, previous);
            return s;
        }
        if (type == GL11.GL_TEXTURE) {
            int level =
                    GL30.glGetFramebufferAttachmentParameteri(
                            fb, attachment, GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL);
            if (dsa) {
                return new int[] {
                    GL45.glGetTextureLevelParameteri(name, level, GL11.GL_TEXTURE_WIDTH),
                    GL45.glGetTextureLevelParameteri(name, level, GL11.GL_TEXTURE_HEIGHT)
                };
            }
            // The game's framebuffer uses a 2D texture; binds it and returns the one bound on the
            // active unit.
            int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, name);
            int[] s = {
                GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_WIDTH),
                GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_HEIGHT)
            };
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previous);
            return s;
        }
        return null;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public SteinTexture color(int i) {
        return i >= 0 && i < colors.length ? colors[i] : null;
    }

    @Override
    public SteinTexture depth() {
        return depth;
    }

    @Override
    public long handle() {
        return fbo;
    }

    @Override
    public void destroy() {
        if (owned && fbo != 0) {
            SteinGlFrame.sync("API target deleted");
            GL30.glDeleteFramebuffers(fbo);
            SteinGlBindings.noteDeleteFramebuffer(fbo);
            fbo = 0;
        }
    }
}
