/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * ItemStack.isItemEnchanted with the result stored on the ItemStack itself.
 *
 * <p>Every piece of armour and every held item asks, every frame, whether it has the glint:
 * NBTTagCompound.hasKey("ench", 9) — ItemStack -> NBT -> map -> table -> node -> key -> value, each
 * in a different object scattered in memory, for every player. With 400 of them, ~6% of the render
 * thread goes to memory latency.
 *
 * <p>The result lives in three fields the patcher creates on ItemStack (sbEnchTag, sbEnchVer,
 * sbEnch) and holds as long as (1) the NBT is the same object and (2) a global counter has not
 * changed. The counter goes up, AFTER the change, whenever the "ench" key is written or removed in
 * any NBT (the NBTTagCompound map becomes a {@link TagMap}) — rare: only when an enchanted item is
 * read from the network or disk, or enchanted. The check reads only the ItemStack's own line and
 * the counter.
 *
 * <p>{@code -Dsteinbridge.opt.vanillaEnchCache=true}: always the NBT lookup. {@code
 * -Dsteinbridge.opt.checkEnchCache=true}: uses the cache and checks every answer against the
 * original lookup.
 */
public final class EnchCache {

    private static final boolean VANILLA = EngineOptions.VANILLA_ENCH_CACHE;
    private static final boolean CHECK = !VANILLA && EngineOptions.CHECK_ENCH_CACHE;

    private static final String ENCH_KEY = "ench";

    /** NBT tag type of a list. */
    private static final int NBT_LIST = 9;

    private static final AtomicInteger VERSION = new AtomicInteger();

    // The ItemStack fields below are created by the patcher (sbEnchTag, sbEnchVer, sbEnch): they do
    // not exist in the readable-name jar the Bridge compiles against, so they are VarHandles.
    private static final VarHandle C_TAG;
    private static final VarHandle C_VER;
    private static final VarHandle C_ENCH;

    private static final CheckReport REPORT = new CheckReport(10_000_000_000L);

    static {
        try {
            MethodHandles.Lookup l =
                    MethodHandles.privateLookupIn(ItemStack.class, MethodHandles.lookup());
            C_TAG = l.findVarHandle(ItemStack.class, "sbEnchTag", Object.class);
            C_VER = l.findVarHandle(ItemStack.class, "sbEnchVer", int.class);
            C_ENCH = l.findVarHandle(ItemStack.class, "sbEnch", boolean.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
        if (CHECK) {
            Log.info("enchcache", "check: each answer compared with the NBT lookup");
        } else if (VANILLA) {
            Log.info("enchcache", "NBT lookup on every call (vanilla)");
        }
    }

    private EnchCache() {}

    /**
     * The global counter: goes up after every write or removal of the "ench" key in any NBT.
     * Whoever stores something derived from an item's enchantments checks against it (and the NBT).
     */
    public static int version() {
        return VERSION.get();
    }

    /** Map of each new NBTTagCompound (diverted from Maps.newHashMap in dn's constructor). */
    public static HashMap<Object, Object> newTagMap() {
        return new TagMap();
    }

    /** Body of ItemStack.isItemEnchanted: stackTagCompound != null && hasKey("ench", 9). */
    public static boolean isItemEnchanted(Object stackObject) {
        ItemStack stack = (ItemStack) stackObject;
        NBTTagCompound tag = stack.stackTagCompound;
        if (tag == null) {
            return false;
        }
        if (VANILLA) {
            return tag.hasKey(ENCH_KEY, NBT_LIST);
        }
        int v = VERSION.get();
        if (C_TAG.get(stack) == tag && (int) C_VER.get(stack) == v) {
            boolean cached = (boolean) C_ENCH.get(stack);
            if (CHECK) {
                verify(tag, cached);
            }
            return cached;
        }
        boolean r = tag.hasKey(ENCH_KEY, NBT_LIST);
        C_ENCH.set(stack, r);
        C_VER.set(stack, v);
        C_TAG.set(stack, tag);
        return r;
    }

    private static void verify(NBTTagCompound tag, boolean cached) {
        boolean real = tag.hasKey(ENCH_KEY, NBT_LIST);
        if (REPORT.compared(real == cached)) {
            Log.info("enchcache", "DIVERGENCE: cache " + cached + ", NBT " + real);
        }
        REPORT.maybeReport("enchcache");
    }

    /**
     * The NBTTagCompound's HashMap, which signals when the "ench" key changes. The counter goes up
     * after the change: a reader that sees the old counter with the new map just recomputes again;
     * the opposite would leave a stale result in the cache.
     */
    static final class TagMap extends HashMap<Object, Object> {

        private static final long serialVersionUID = 1L;

        private static void bumpIfEnch(Object key) {
            if (ENCH_KEY.equals(key)) {
                VERSION.incrementAndGet();
            }
        }

        private static void bump() {
            VERSION.incrementAndGet();
        }

        @Override
        public Object put(Object key, Object value) {
            Object r = super.put(key, value);
            bumpIfEnch(key);
            return r;
        }

        @Override
        public Object remove(Object key) {
            Object r = super.remove(key);
            bumpIfEnch(key);
            return r;
        }

        @Override
        public boolean remove(Object key, Object value) {
            boolean r = super.remove(key, value);
            bumpIfEnch(key);
            return r;
        }

        @Override
        public void clear() {
            super.clear();
            bump();
        }

        @Override
        public void putAll(Map<?, ?> m) {
            super.putAll(m);
            bump();
        }

        @Override
        public Object putIfAbsent(Object key, Object value) {
            Object r = super.putIfAbsent(key, value);
            bumpIfEnch(key);
            return r;
        }

        @Override
        public Object replace(Object key, Object value) {
            Object r = super.replace(key, value);
            bumpIfEnch(key);
            return r;
        }

        @Override
        public boolean replace(Object key, Object oldValue, Object newValue) {
            boolean r = super.replace(key, oldValue, newValue);
            bumpIfEnch(key);
            return r;
        }

        @Override
        public Object computeIfAbsent(Object key, Function<? super Object, ?> f) {
            Object r = super.computeIfAbsent(key, f);
            bumpIfEnch(key);
            return r;
        }

        @Override
        public Object computeIfPresent(
                Object key, BiFunction<? super Object, ? super Object, ?> f) {
            Object r = super.computeIfPresent(key, f);
            bumpIfEnch(key);
            return r;
        }

        @Override
        public Object compute(Object key, BiFunction<? super Object, ? super Object, ?> f) {
            Object r = super.compute(key, f);
            bumpIfEnch(key);
            return r;
        }

        @Override
        public Object merge(
                Object key, Object value, BiFunction<? super Object, ? super Object, ?> f) {
            Object r = super.merge(key, value, f);
            bumpIfEnch(key);
            return r;
        }

        @Override
        public void replaceAll(BiFunction<? super Object, ? super Object, ?> f) {
            super.replaceAll(f);
            bump();
        }
    }
}
