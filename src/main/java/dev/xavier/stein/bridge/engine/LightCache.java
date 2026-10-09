/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.gfx.SteinGfxOptions;

import net.minecraft.entity.Entity;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Light of entities and particles, kept between frames.
 *
 * <p>Every entity and every drawn particle asks, on each frame, for the light of the block it is in
 * (Entity.getBrightnessForRender): a new BlockPos, the chunk lookup in the client's map and the two
 * light layers, with the neighbors when the block is a slab or stair. In rain, the splashes alone
 * were 26-29% of the render thread, and 11% just in this computation. The light only changes when
 * the world changes — and the particle's position, only on the tick.
 *
 * <p>Per entity (patcher fields): the block, the world and the version of the last computation, and
 * the value. The version combines the light's — it goes up before every light write
 * (NibbleArray.set, the only path) — and that of the blocks and chunks of the {@link ChunkGraph}
 * (block write, data swap, load and unload). With the same block, the same world and no change
 * since, the game's computation would give the same number. The position (and the eye height, which
 * changes when sneaking) is read on every call.
 *
 * <p>{@code -Dsteinbridge.opt.vanillaLight=true}: the game's computation on every frame. {@code
 * -Dsteinbridge.opt.checkLight=true}: uses the stored value and checks it against the game's
 * computation.
 *
 * <p>Pipeline: both — the entity brightness shortcut runs only in Stein GFX; the rest is shared.
 */
public final class LightCache {

    private static final boolean VANILLA = EngineOptions.VANILLA_LIGHT;
    private static final boolean CHECK = !VANILLA && EngineOptions.CHECK_LIGHT;

    // The entity fields below are created by the patcher (sbLightKey, sbLightVer, sbLight,
    // sbLightWorld): they do not exist in the readable-name jar the Bridge compiles against, so
    // they are reached by VarHandle.
    private static final VarHandle LIGHT_KEY;
    private static final VarHandle LIGHT_VERSION;
    private static final VarHandle LIGHT_VALUE;
    private static final VarHandle LIGHT_WORLD;

    static {
        try {
            MethodHandles.Lookup el =
                    MethodHandles.privateLookupIn(Entity.class, MethodHandles.lookup());
            LIGHT_KEY = el.findVarHandle(Entity.class, "sbLightKey", long.class);
            LIGHT_VERSION = el.findVarHandle(Entity.class, "sbLightVer", long.class);
            LIGHT_VALUE = el.findVarHandle(Entity.class, "sbLight", int.class);
            LIGHT_WORLD = el.findVarHandle(Entity.class, "sbLightWorld", Object.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
        if (CHECK) {
            Log.info("light", "check: each stored light compared with the game's computation");
        } else if (VANILLA) {
            Log.info("light", "entity light computed every frame (vanilla)");
        }
    }

    /** Light version: goes up before every light write (NibbleArray.set). */
    private static volatile int lightEpoch;

    private static final CheckReport REPORT = new CheckReport(5_000_000_000L);

    private LightCache() {}

    /** Before every NibbleArray.set. */
    public static void bump() {
        lightEpoch++;
    }

    /** The body of Entity.getBrightnessForRender(partialTicks). */
    public static int brightness(Object entity, float partialTicks) {
        Entity e = (Entity) entity;
        double x = e.posX;
        double y = e.posY + (double) e.getEyeHeight();
        double z = e.posZ;
        World world = e.worldObj;
        if (VANILLA) {
            return compute(world, x, y, z);
        }
        long key = BlockMath.pack(BlockMath.floor(x), BlockMath.floor(y), BlockMath.floor(z));
        long ver = (long) lightEpoch << 32 | Integer.toUnsignedLong(ChunkGraph.blockEpoch());
        if (world != null
                && LIGHT_WORLD.get(entity) == world
                && (long) LIGHT_KEY.get(entity) == key
                && (long) LIGHT_VERSION.get(entity) == ver) {
            int v = (int) LIGHT_VALUE.get(entity);
            if (CHECK) {
                check(v == compute(world, x, y, z));
            }
            return v;
        }
        int v = compute(world, x, y, z);
        LIGHT_VALUE.set(entity, v);
        LIGHT_KEY.set(entity, key);
        LIGHT_VERSION.set(entity, ver);
        LIGHT_WORLD.set(entity, world);
        return v;
    }

    // ---- Entity.getBrightness(partialTicks): the float brightness that setBrightness reads on
    // each layer ----

    /** Float brightness fields (loaded on first use, only with the Stein renderer on). */
    private static final class Bright {
        // Patcher fields on Entity: not in the readable-name jar, so VarHandles.
        static final VarHandle BRIGHT_KEY;
        static final VarHandle BRIGHT_VERSION;
        static final VarHandle BRIGHT_SKY;
        static final VarHandle BRIGHT_VALUE;
        static final VarHandle BRIGHT_WORLD;

        static {
            try {
                MethodHandles.Lookup el =
                        MethodHandles.privateLookupIn(Entity.class, MethodHandles.lookup());
                BRIGHT_KEY = el.findVarHandle(Entity.class, "sbBrightKey", long.class);
                BRIGHT_VERSION = el.findVarHandle(Entity.class, "sbBrightVer", long.class);
                BRIGHT_SKY = el.findVarHandle(Entity.class, "sbBrightSky", int.class);
                BRIGHT_VALUE = el.findVarHandle(Entity.class, "sbBright", float.class);
                BRIGHT_WORLD = el.findVarHandle(Entity.class, "sbBrightWorld", Object.class);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        /**
         * The class uses Entity's getBrightness (no subclass up to Entity overrides it: the blaze
         * returns 1). Reflection on purpose: it scans the declared methods of arbitrary subclasses,
         * by the method's name in the real game ("c").
         */
        static final ClassValue<Boolean> PLAIN =
                new ClassValue<>() {
                    @Override
                    protected Boolean computeValue(Class<?> c) {
                        for (Class<?> k = c;
                                k != null && k != Entity.class;
                                k = k.getSuperclass()) {
                            try {
                                if (k.getDeclaredMethod("c", float.class).getReturnType()
                                        == float.class) {
                                    return false;
                                }
                            } catch (NoSuchMethodException e) {
                                // does not declare it: moves on to the parent
                            }
                        }
                        return true;
                    }
                };
    }

    /**
     * Entity.getBrightness inside RendererLivingEntity.setBrightness (patcher rule), called on
     * every layer of every living entity per frame: a new BlockPos, the chunk lookup, the block
     * (for the neighbors' light) and the two light layers. With the Stein renderer on, stored per
     * entity like {@link #brightness}: the same block, the same world, no light or block write, no
     * chunk load swap, and the same sky darkening (skylightSubtracted, which getLight subtracts)
     * give the same number — the brightness is the dimension's table at that light level. Classes
     * that override getBrightness, and everything with the renderer off, keep using the game's
     * computation.
     */
    public static float entityBrightness(Object entity, float partialTicks) {
        Entity e = (Entity) entity;
        if (VANILLA || !SteinGfxOptions.ENABLED || !Bright.PLAIN.get(entity.getClass())) {
            return e.getBrightness(partialTicks);
        }
        World world = e.worldObj;
        if (world == null) {
            return e.getBrightness(partialTicks);
        }
        double x = e.posX;
        double y = e.posY + (double) e.getEyeHeight();
        double z = e.posZ;
        long key = BlockMath.pack(BlockMath.floor(x), BlockMath.floor(y), BlockMath.floor(z));
        long ver = (long) lightEpoch << 32 | Integer.toUnsignedLong(ChunkGraph.blockEpoch());
        int sky = world.skylightSubtracted;
        if (Bright.BRIGHT_WORLD.get(entity) == world
                && (long) Bright.BRIGHT_KEY.get(entity) == key
                && (long) Bright.BRIGHT_VERSION.get(entity) == ver
                && (int) Bright.BRIGHT_SKY.get(entity) == sky) {
            float v = (float) Bright.BRIGHT_VALUE.get(entity);
            if (CHECK) {
                check(
                        Float.floatToIntBits(v)
                                == Float.floatToIntBits(e.getBrightness(partialTicks)));
            }
            return v;
        }
        float v = e.getBrightness(partialTicks);
        Bright.BRIGHT_VALUE.set(entity, v);
        Bright.BRIGHT_KEY.set(entity, key);
        Bright.BRIGHT_VERSION.set(entity, ver);
        Bright.BRIGHT_SKY.set(entity, sky);
        Bright.BRIGHT_WORLD.set(entity, world);
        return v;
    }

    /**
     * The game's computation: BlockPos(posX, posY + eyes, posZ); loaded ? getCombinedLight(pos, 0)
     * : 0.
     */
    private static int compute(World world, double x, double y, double z) {
        BlockPos pos = new BlockPos(x, y, z);
        return world.isBlockLoaded(pos) ? world.getCombinedLight(pos, 0) : 0;
    }

    private static void check(boolean ok) {
        if (REPORT.compared(ok)) {
            Log.info("light", "DIVERGENCE in the stored light");
        }
        REPORT.maybeReport("light");
    }
}
