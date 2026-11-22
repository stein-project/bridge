/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.WorldRenderer;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * Fix for the vertex builder (WorldRenderer) not growing when it should, and growing when it need
 * not.
 *
 * <p>WorldRenderer.growBuffer decides from the position of its int view of the buffer. Only {@code
 * addVertexData} (the block models' quads) moves that position; {@code pos}, {@code color} and the
 * other element writers put at an absolute index worked out from the vertex count, and leave it
 * where it was. So a batch built vertex by vertex (text, particles, name tags, fluids, the HUD)
 * compared the room left with a stale position: from 0 it never grew, and the next vertex past the
 * end threw IndexOutOfBoundsException, a crash when one batch drew more than the buffer holds (2 MB
 * in the shared Tessellator: a crowd of name tags, a particle storm); from a position left high by
 * an earlier batch of quads it grew without need. Here the room is taken from what is really in
 * use, the larger of the vertex count's size and that position, and the buffer grows to the next
 * multiple of 2 MB that holds it, as the game's own step does.
 *
 * <p>The patcher sends the call of growBuffer in endVertex and addVertexData here
 * (redirects-mc.tsv); setVertexState clears the position first, so its own call is right as it is.
 * Called once per vertex: a few field reads, no allocation unless the buffer grows. {@code
 * -Dsteinbridge.opt.vanillaBuilderGrowth=true} brings back 1.8.9's test.
 */
public final class BuilderGrowth {

    private BuilderGrowth() {}

    private static final boolean VANILLA = RenderFixOptions.VANILLA_BUILDER_GROWTH;

    /** The game's growth step. */
    private static final int STEP = 2 * 1024 * 1024;

    /** WorldRenderer.growBuffer(ints) in endVertex and addVertexData. */
    public static void grow(Object builder, int ints) {
        WorldRenderer b = (WorldRenderer) builder;
        if (VANILLA) {
            b.growBuffer(ints);
            return;
        }
        IntBuffer raw = b.rawIntBuffer;
        int position = raw.position();
        int need = Math.max(b.getBufferSize(), position) + ints;
        if (need <= raw.capacity()) {
            return;
        }
        ByteBuffer old = b.byteBuffer;
        long bytes = (long) need * 4L;
        int size = (int) ((bytes + STEP - 1) / STEP * STEP);
        Log.info(
                "render", "vertex builder grown from " + old.capacity() + " to " + size + " bytes");
        ByteBuffer next = GLAllocation.createDirectByteBuffer(size);
        old.position(0);
        next.put(old);
        next.rewind();
        b.byteBuffer = next;
        b.rawFloatBuffer = next.asFloatBuffer().asReadOnlyBuffer();
        b.rawIntBuffer = next.asIntBuffer();
        b.rawIntBuffer.position(position);
        b.rawShortBuffer = next.asShortBuffer();
        b.rawShortBuffer.position(position << 1);
    }
}
