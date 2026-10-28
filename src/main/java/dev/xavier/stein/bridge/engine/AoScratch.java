/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import static java.lang.invoke.MethodType.methodType;

import net.minecraft.client.renderer.BlockModelRenderer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.BitSet;

/**
 * The scratch objects of BlockModelRenderer.renderModelAmbientOcclusion, kept per thread instead of
 * allocated for every smooth-lit block.
 *
 * <p>The method starts with a float[12] for the quad bounds, a BitSet(3) for the bound flags and an
 * AmbientOcclusionFace (a float[4] and an int[4] inside): about 26% of the allocation of the chunk
 * build threads on the benchmark. The patcher hands those three allocations here
 * (redirects-opt.tsv, mode "new"), and the method itself stays the game's. They never leave the
 * method (only its own private renderModelAmbientOcclusionQuads gets them) and, for every quad, the
 * game writes them before reading them (checked in the bytecode): fillQuadBounds writes all twelve
 * bounds and both flags it uses (0 and 1, set in every facing's branch), and updateVertexBrightness
 * writes the four brightness and the four colour entries in both of its branches (the vertex
 * indices of every facing are a permutation of 0..3); the quad is then drawn from them. So a reused
 * set gives the same vertices. Even so, the bounds come back zeroed and the flags cleared, as new
 * ones would. Nothing in those calls draws another block, so one set per thread is never in use
 * twice. {@code -Dsteinbridge.opt.vanillaAoScratch=true} allocates them again, as the game does.
 */
public final class AoScratch {

    private static final boolean VANILLA = EngineOptions.VANILLA_AO_SCRATCH;

    /**
     * new AmbientOcclusionFace(renderer): a package-private inner class, reached by handle (it
     * cannot be named in the readable-name build).
     */
    private static final MethodHandle FACE_NEW;

    static {
        try {
            Class<?> face = Obf.cls("bgf$b");
            FACE_NEW =
                    MethodHandles.privateLookupIn(face, MethodHandles.lookup())
                            .findConstructor(face, methodType(void.class, BlockModelRenderer.class))
                            .asType(methodType(Object.class, Object.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private AoScratch() {}

    /** new float[length] for the quad bounds. */
    public static Object quadBounds(int length) {
        if (VANILLA) {
            return new float[length];
        }
        Scratch s = SCRATCH.get();
        if (s.bounds.length != length) {
            s.bounds = new float[length];
        } else {
            Arrays.fill(s.bounds, 0f);
        }
        return s.bounds;
    }

    /** new BitSet(bits) for the bound flags. */
    public static Object boundsFlags(int bits) {
        if (VANILLA) {
            return new BitSet(bits);
        }
        Scratch s = SCRATCH.get();
        if (s.flags == null || s.flagBits != bits) {
            s.flags = new BitSet(bits);
            s.flagBits = bits;
        } else {
            s.flags.clear();
        }
        return s.flags;
    }

    /** new AmbientOcclusionFace(renderer). */
    public static Object face(Object renderer) {
        if (VANILLA) {
            return newFace(renderer);
        }
        Scratch s = SCRATCH.get();
        if (s.face == null || s.faceOwner != renderer) {
            s.face = newFace(renderer);
            s.faceOwner = renderer;
        }
        return s.face;
    }

    private static Object newFace(Object renderer) {
        try {
            return (Object) FACE_NEW.invokeExact(renderer);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** One thread's set. */
    private static final class Scratch {
        float[] bounds = new float[0];
        BitSet flags;
        int flagBits;
        Object face;
        Object faceOwner;
    }
}
