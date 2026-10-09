/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx.gl;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.gfx.SteinBuffer;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL44;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * OpenGL buffer. With immutable storage ({@code glBufferStorage}) when the card has it; {@link
 * Memory#UPLOAD} is then persistently and coherently mapped, and the CPU writes straight to {@link
 * #address}. Without it, a plain buffer and every write goes through {@link #upload}.
 *
 * <p>To create and write, the buffer is bound to {@code GL_COPY_WRITE_BUFFER} (which the game does
 * not use) and whatever was bound there is restored afterwards; the game's {@code GL_ARRAY_BUFFER}
 * is never touched.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinGlBuffer extends SteinBuffer {

    private static final int TARGET = GL31.GL_COPY_WRITE_BUFFER;

    /** Up to what size the float copy uses LWJGL's stack instead of allocating. */
    private static final int STACK_BYTES = 16 * 1024;

    private int id;
    private long base;

    /** {@link #base} is a CPU-side copy (no persistent mapping), not the buffer's memory. */
    private boolean shadow;

    SteinGlBuffer(SteinGlDevice device, Desc desc) {
        super(desc);
        boolean persistent = device.caps().persistentMapping;
        id = GL15.glGenBuffers();
        int previous = bind(id);
        try {
            if (desc.memory == Memory.UPLOAD) {
                if (persistent) {
                    int flags =
                            GL30.GL_MAP_WRITE_BIT
                                    | GL44.GL_MAP_PERSISTENT_BIT
                                    | GL44.GL_MAP_COHERENT_BIT;
                    GL44.glBufferStorage(TARGET, desc.size, flags);
                    base = GL30.nglMapBufferRange(TARGET, 0L, desc.size, flags);
                    if (base == 0L) {
                        Log.info("gfx", "buffer '" + desc.name + "': persistent mapping failed");
                    }
                } else {
                    // Without a persistent buffer (4.1): the CPU writes to a copy in ordinary
                    // memory (the usual address()) and the written range is uploaded with
                    // commit() before drawing.
                    GL15.glBufferData(TARGET, desc.size, GL15.GL_STREAM_DRAW);
                    base = MemoryUtil.nmemCalloc(1, desc.size);
                    shadow = base != 0L;
                }
            } else if (persistent) {
                GL44.glBufferStorage(TARGET, desc.size, GL44.GL_DYNAMIC_STORAGE_BIT);
            } else {
                GL15.glBufferData(TARGET, desc.size, GL15.GL_STATIC_DRAW);
            }
        } finally {
            unbind(previous);
        }
    }

    /**
     * Advances on every deleted buffer: the name may come back for another buffer, and what the
     * VAOs and the uniform and storage binding points track as bound ({@link SteinGlVao}, {@link
     * SteinGlCommands}) stops being valid.
     */
    static int generation;

    /**
     * Binds {@code buffer} to the copy target and returns what was bound there (tracked in {@link
     * SteinGlBindings}: only Stein uses the target; without a persistent buffer, each ring's commit
     * used to ask the driver on every flush).
     */
    private static int bind(int buffer) {
        int previous = SteinGlBindings.copyWrite();
        GL15.glBindBuffer(TARGET, buffer);
        SteinGlBindings.noteCopyWrite(buffer);
        return previous;
    }

    private static void unbind(int previous) {
        GL15.glBindBuffer(TARGET, previous);
        SteinGlBindings.noteCopyWrite(previous);
    }

    @Override
    public long handle() {
        return id;
    }

    private void checkRange(long offset, int n) {
        if (offset < 0 || offset + n > desc.size) {
            throw new IndexOutOfBoundsException(
                    "upload outside the buffer '" + desc.name + "': " + offset + "+" + n);
        }
    }

    @Override
    public void upload(long offset, ByteBuffer data) {
        if (SteinGlFrame.pending() && !shadow) {
            // Recorded frame with a pending list: the upload goes into the list (the data copied
            // now), and the draw recorded earlier reads the previous contents, as in the
            // immediate pass.
            int n = data.remaining();
            checkRange(offset, n);
            SteinGlFrame.upload(id, offset, data, base != 0L ? base + offset : 0L);
            return;
        }
        if (base != 0L) {
            // mapped (immutable storage without DYNAMIC_STORAGE): glBufferSubData would error;
            // copy into the mapping
            int n = data.remaining();
            checkRange(offset, n);
            MemoryUtil.memCopy(MemoryUtil.memAddress(data), base + offset, n);
            if (!shadow) {
                return;
            }
        }
        int previous = bind(id);
        try {
            GL15.glBufferSubData(TARGET, offset, data);
        } finally {
            unbind(previous);
        }
    }

    @Override
    public void upload(long offset, float[] data, int count) {
        int bytes = count * 4;
        if (bytes <= STACK_BYTES) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer b = stack.malloc(bytes);
                b.asFloatBuffer().put(data, 0, count);
                upload(offset, b);
            }
            return;
        }
        ByteBuffer b = MemoryUtil.memAlloc(bytes);
        try {
            b.asFloatBuffer().put(data, 0, count);
            upload(offset, b);
        } finally {
            MemoryUtil.memFree(b);
        }
    }

    @Override
    public long address(long offset) {
        return base == 0L ? 0L : base + offset;
    }

    @Override
    public boolean mapped() {
        return base != 0L;
    }

    @Override
    public void commit(long offset, long bytes) {
        if (!shadow || bytes <= 0) {
            return;
        }
        int previous = bind(id);
        try {
            GL15.nglBufferSubData(TARGET, offset, bytes, base + offset);
        } finally {
            unbind(previous);
        }
    }

    @Override
    public void destroy() {
        if (id == 0) {
            return;
        }
        if (SteinGlFrame.pending() && !shadow) {
            // recorded frame: the name is only released after whoever reads it in the list (the
            // mapping too)
            SteinGlFrame.deleteBuffer(id, base != 0L);
            base = 0L;
            id = 0;
            return;
        }
        if (shadow) {
            MemoryUtil.nmemFree(base);
            base = 0L;
            shadow = false;
            deleteNow(id, false);
        } else {
            deleteNow(id, base != 0L);
            base = 0L;
        }
        id = 0;
    }

    /**
     * Actually deletes (immediately, or when the recorded frame is replayed): undoes the persistent
     * mapping, releases the name, and only then advances the generation (what the VAOs and the
     * binding points track stops being valid when the name can come back).
     */
    static void deleteNow(int id, boolean mapped) {
        if (mapped) {
            int previous = bind(id);
            GL15.glUnmapBuffer(TARGET);
            unbind(previous == id ? 0 : previous);
        }
        GL15.glDeleteBuffers(id);
        SteinGlBindings.noteDeleteBuffer(id);
        generation++;
    }
}
