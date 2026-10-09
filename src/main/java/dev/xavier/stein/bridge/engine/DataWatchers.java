/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.entity.DataWatcher;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.Map;
import java.util.concurrent.locks.Lock;

/**
 * DataWatcher reads without the ReadWriteLock, without the HashMap and, for numeric values, without
 * going through the WatchableObject and the box.
 *
 * <p>DataWatcher.getWatchedObject (pz.j) takes the read lock on every read — and each entity reads
 * several values per frame (invisible, sneaking, skin layers, health...). With the 400, lock +
 * unlock are ~4% of the render thread.
 *
 * <p>Why the lock-free read is safe: the map only changes structure in addObject, which the game
 * calls in entityInit, during the entity's construction, before it is shared with another thread.
 * After that only the VALUES change (WatchableObject.setObject), which the lock does not even
 * protect. On the client all access is from the render thread; on the integrated server, the
 * network thread that serialises the DataWatcher only receives it after construction, through a
 * concurrent queue (which already establishes the memory order).
 *
 * <p><b>Mirror of the numeric values.</b> Even through the array by id (sbWatched), reading the
 * health (id 6), the skin parts (id 10) or the flags byte (id 0) of a cold entity takes three or
 * four dependent cache misses: DataWatcher -> array -> WatchableObject -> box (the health Float is
 * allocated on every change, it never comes from a cache). In the crowd, with the flags mirror
 * ({@link EntityFlags}) on, the miss moved to getHealth (isEntityAlive) and isWearing. Here, each
 * id gets a long in the DataWatcher itself (patcher fields pz.sbM0..sbM31): the box type in the
 * upper 32 bits and the value (byte, short, int or the float's bits) in the lower ones. The typed
 * getters (pz.a/b/c/d) read the long: entity -> DataWatcher is a single miss, and the 32 longs sit
 * in a few consecutive lines of the same object.
 *
 * <p>Why it is exact: the WatchableObject's value (pz$a.c, private) is only written in the
 * constructor and in setObject (pz$a.a(Object)), whose body is {@link #setObject}. When the object
 * of an id enters the array by id (its first read), it gets the DataWatcher as its owner (patcher
 * field pz$a.sbOwner) and the current value goes into the long; from then on every setObject of
 * that object updates the long as well. The getter only uses the long when the stored type is the
 * one it would convert (Byte for getWatchableObjectByte...); a null value, of another type or an id
 * outside 0..31 goes through the game's path, with the same exception. The order of the linking and
 * of setObject is that of {@link EntityFlags} (volatile value before reading the owner; owner
 * before copying, and the copy checked until the value stops), so a first read from another thread
 * does not lose a write either.
 *
 * <p>Warming up the players' DataWatchers on the pose threads before the render thread is not
 * included: the pose threads only wake at the end of the entity loop (after the reads), and with
 * the mirror the cold read is already a single miss.
 *
 * <p>{@code -Dsteinbridge.opt.vanillaDataWatcher=true}: with the lock, as in 1.8.9 (also turns off
 * the mirrors). {@code -Dsteinbridge.opt.watchedMirror=false}: without the values mirror (getters
 * through the WatchableObject). {@code -Dsteinbridge.opt.checkWatchedMirror=true}: uses the mirror
 * and checks every read against the WatchableObject (counts divergences; logs every 5 s).
 */
public final class DataWatchers {

    private static final boolean VANILLA = EngineOptions.VANILLA_DATA_WATCHER;
    private static final boolean MIRROR = !VANILLA && EngineOptions.WATCHED_MIRROR;
    private static final boolean CHECK = MIRROR && EngineOptions.CHECK_WATCHED_MIRROR;

    /** The entity's flags mirror also depends on the owner marked here. */
    private static final boolean FLAGS = !VANILLA && EngineOptions.ENTITY_FLAGS;

    /** Marks the owner and signals in setObject (any of the mirrors on). */
    private static final boolean LINK = MIRROR || FLAGS;

    /** Types in the mirror's long (upper 32 bits). 0 = no mirror: the game's path. */
    private static final int K_BYTE = 1;

    private static final int K_SHORT = 2;
    private static final int K_INT = 3;
    private static final int K_FLOAT = 4;

    /** Field added by the patcher (redirects-opt.tsv): the objects by id. */
    private static final VarHandle ARRAY;

    /**
     * WatchableObject.watchedObject (pz$a.c) for the volatile accesses — typed Java cannot read or
     * write a non-volatile field with volatile semantics, so this one stays a VarHandle (its plain
     * reads and writes are direct field accesses) — and the patcher field pz$a.sbOwner (the
     * DataWatcher).
     */
    private static final VarHandle VALUE;

    private static final VarHandle OWNER;

    /** Patcher fields pz.sbM0..sbM31: each id's mirror. */
    private static final VarHandle M0,
            M1,
            M2,
            M3,
            M4,
            M5,
            M6,
            M7,
            M8,
            M9,
            M10,
            M11,
            M12,
            M13,
            M14,
            M15;

    private static final VarHandle M16,
            M17,
            M18,
            M19,
            M20,
            M21,
            M22,
            M23,
            M24,
            M25,
            M26,
            M27,
            M28,
            M29,
            M30,
            M31;

    static {
        try {
            Class<?> dw = DataWatcher.class;
            Class<?> wo = DataWatcher.WatchableObject.class;
            MethodHandles.Lookup l = MethodHandles.privateLookupIn(dw, MethodHandles.lookup());
            MethodHandles.Lookup wl = MethodHandles.privateLookupIn(wo, MethodHandles.lookup());
            ARRAY = l.findVarHandle(dw, "sbWatched", Object[].class);
            VALUE = wl.findVarHandle(wo, "c", Object.class);
            OWNER = wl.findVarHandle(wo, "sbOwner", Object.class);
            M0 = mirrorHandle(l, dw, 0);
            M1 = mirrorHandle(l, dw, 1);
            M2 = mirrorHandle(l, dw, 2);
            M3 = mirrorHandle(l, dw, 3);
            M4 = mirrorHandle(l, dw, 4);
            M5 = mirrorHandle(l, dw, 5);
            M6 = mirrorHandle(l, dw, 6);
            M7 = mirrorHandle(l, dw, 7);
            M8 = mirrorHandle(l, dw, 8);
            M9 = mirrorHandle(l, dw, 9);
            M10 = mirrorHandle(l, dw, 10);
            M11 = mirrorHandle(l, dw, 11);
            M12 = mirrorHandle(l, dw, 12);
            M13 = mirrorHandle(l, dw, 13);
            M14 = mirrorHandle(l, dw, 14);
            M15 = mirrorHandle(l, dw, 15);
            M16 = mirrorHandle(l, dw, 16);
            M17 = mirrorHandle(l, dw, 17);
            M18 = mirrorHandle(l, dw, 18);
            M19 = mirrorHandle(l, dw, 19);
            M20 = mirrorHandle(l, dw, 20);
            M21 = mirrorHandle(l, dw, 21);
            M22 = mirrorHandle(l, dw, 22);
            M23 = mirrorHandle(l, dw, 23);
            M24 = mirrorHandle(l, dw, 24);
            M25 = mirrorHandle(l, dw, 25);
            M26 = mirrorHandle(l, dw, 26);
            M27 = mirrorHandle(l, dw, 27);
            M28 = mirrorHandle(l, dw, 28);
            M29 = mirrorHandle(l, dw, 29);
            M30 = mirrorHandle(l, dw, 30);
            M31 = mirrorHandle(l, dw, 31);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
        if (VANILLA) {
            Log.info("datawatcher", "locked read (vanilla)");
        } else if (!MIRROR) {
            Log.info("datawatcher", "values read from the WatchableObject (no mirror)");
        } else if (CHECK) {
            Log.info(
                    "datawatcher",
                    "check: each value read from the mirror compared with the"
                            + " WatchableObject");
        }
    }

    /** Number of mirrored ids (M0..M31); must be a power of two. */
    private static final int MIRROR_SLOTS = 32;

    private static boolean isMirrored(int id) {
        return (id & ~(MIRROR_SLOTS - 1)) == 0;
    }

    private static VarHandle mirrorHandle(MethodHandles.Lookup l, Class<?> dw, int id)
            throws ReflectiveOperationException {
        return l.findVarHandle(dw, "sbM" + id, long.class);
    }

    private DataWatchers() {}

    /**
     * Body of DataWatcher.getWatchedObject(int).
     *
     * <p>The watched objects are also kept in an array by id, in a field the patcher adds
     * (pz.sbWatched): the read becomes an array access instead of a HashMap&lt;Integer, ...&gt;
     * with boxing — each of the 400 entities reads several ids per frame. It holds for the same
     * reason as the lock-free read: the map only gains entries during construction and never loses
     * any, and each id's object is the same forever (only the value inside it changes). The entry
     * in the array is also where the object gets the DataWatcher as its owner and the value goes to
     * the mirror.
     */
    public static Object getWatchedObject(Object watcher, int id) {
        if (!VANILLA && id >= 0 && id < MIRROR_SLOTS) {
            Object[] arr = (Object[]) ARRAY.get(watcher);
            if (arr != null) {
                Object cached = arr[id];
                if (cached != null) {
                    return cached;
                }
            }
            Object o = ((DataWatcher) watcher).watchedObjects.get(id);
            if (o != null) {
                if (arr == null) {
                    arr = new Object[MIRROR_SLOTS];
                    ARRAY.set(watcher, arr);
                }
                arr[id] = o;
                if (LINK) {
                    link(watcher, id, o);
                }
            }
            return o;
        }
        Map<?, ?> map = ((DataWatcher) watcher).watchedObjects;
        if (!VANILLA) {
            return map.get(id);
        }
        Lock read = ((DataWatcher) watcher).lock.readLock();
        read.lock();
        try {
            return map.get(id);
        } finally {
            read.unlock();
        }
    }

    /**
     * The object gets the DataWatcher as its owner (if it does not have one yet) and the current
     * value goes to the mirror.
     */
    private static void link(Object watcher, int id, Object wo) {
        if (!OWNER.compareAndSet(wo, (Object) null, watcher) && OWNER.getVolatile(wo) != watcher) {
            return; // from another DataWatcher (mods only): the id stays on the game's path
        }
        if (!MIRROR) {
            return;
        }
        Object v;
        do {
            v = VALUE.getVolatile(wo);
            setM(watcher, id, encode(v));
        } while (VALUE.getVolatile(wo) != v);
    }

    /** The object's owner (the DataWatcher it was first read from), or null. */
    static Object ownerOf(Object wo) {
        return OWNER.getVolatile(wo);
    }

    /** The value stored in the object (WatchableObject.getObject). */
    static Object valueOf(Object wo) {
        return VALUE.getVolatile(wo);
    }

    /**
     * The body of WatchableObject.setObject(Object) (pz$a.a): the value, and the owner's mirrors if
     * there are any.
     */
    public static void setObject(Object wo, Object value) {
        if (!LINK) {
            ((DataWatcher.WatchableObject) wo).watchedObject = value;
            return;
        }
        VALUE.setVolatile(wo, value);
        Object watcher = OWNER.getVolatile(wo);
        if (watcher != null) {
            int id = ((DataWatcher.WatchableObject) wo).dataValueId;
            if (MIRROR) {
                setM(watcher, id, encode(value));
            }
            if (FLAGS && id == 0) {
                EntityFlags.changed(watcher, value);
            }
        }
    }

    /** The value stored in the object (plain read). */
    private static Object value(Object wo) {
        return ((DataWatcher.WatchableObject) wo).watchedObject;
    }

    private static long encode(Object v) {
        int k;
        int x;
        if (v instanceof Byte b) {
            k = K_BYTE;
            x = b;
        } else if (v instanceof Short s) {
            k = K_SHORT;
            x = s;
        } else if (v instanceof Integer i) {
            k = K_INT;
            x = i;
        } else if (v instanceof Float f) {
            k = K_FLOAT;
            x = Float.floatToRawIntBits(f);
        } else {
            return 0L;
        }
        return (long) k << 32 | (x & 0xFFFFFFFFL);
    }

    // ---- typed getters (bodies of pz.a/b/c/d) ----

    /** DataWatcher.getWatchableObjectByte (pz.a(I)B). */
    public static byte getByte(Object watcher, int id) {
        if (MIRROR && isMirrored(id)) {
            long m = getM(watcher, id);
            if ((int) (m >>> 32) == K_BYTE) {
                if (CHECK) {
                    check((byte) m == ((Byte) value(getWatchedObject(watcher, id))).byteValue());
                }
                return (byte) m;
            }
        }
        return ((Byte) value(getWatchedObject(watcher, id))).byteValue();
    }

    /** DataWatcher.getWatchableObjectShort (pz.b(I)S). */
    public static short getShort(Object watcher, int id) {
        if (MIRROR && isMirrored(id)) {
            long m = getM(watcher, id);
            if ((int) (m >>> 32) == K_SHORT) {
                if (CHECK) {
                    check((short) m == ((Short) value(getWatchedObject(watcher, id))).shortValue());
                }
                return (short) m;
            }
        }
        return ((Short) value(getWatchedObject(watcher, id))).shortValue();
    }

    /** DataWatcher.getWatchableObjectInt (pz.c(I)I). */
    public static int getInt(Object watcher, int id) {
        if (MIRROR && isMirrored(id)) {
            long m = getM(watcher, id);
            if ((int) (m >>> 32) == K_INT) {
                if (CHECK) {
                    check((int) m == ((Integer) value(getWatchedObject(watcher, id))).intValue());
                }
                return (int) m;
            }
        }
        return ((Integer) value(getWatchedObject(watcher, id))).intValue();
    }

    /** DataWatcher.getWatchableObjectFloat (pz.d(I)F). */
    public static float getFloat(Object watcher, int id) {
        if (MIRROR && isMirrored(id)) {
            long m = getM(watcher, id);
            if ((int) (m >>> 32) == K_FLOAT) {
                float v = Float.intBitsToFloat((int) m);
                if (CHECK) {
                    check(
                            (int) m
                                    == Float.floatToRawIntBits(
                                            ((Float) value(getWatchedObject(watcher, id)))
                                                    .floatValue()));
                }
                return v;
            }
        }
        return ((Float) value(getWatchedObject(watcher, id))).floatValue();
    }

    // ---- the 32 longs ----
    // M0..M31 and the switches below are deliberately unrolled: with the id constant after
    // inlining, only the field load remains, and each handle is a JIT constant. Do not replace
    // them with a VarHandle[].

    private static long getM(Object w, int id) {
        return switch (id) {
            case 0 -> (long) M0.get(w);
            case 1 -> (long) M1.get(w);
            case 2 -> (long) M2.get(w);
            case 3 -> (long) M3.get(w);
            case 4 -> (long) M4.get(w);
            case 5 -> (long) M5.get(w);
            case 6 -> (long) M6.get(w);
            case 7 -> (long) M7.get(w);
            case 8 -> (long) M8.get(w);
            case 9 -> (long) M9.get(w);
            case 10 -> (long) M10.get(w);
            case 11 -> (long) M11.get(w);
            case 12 -> (long) M12.get(w);
            case 13 -> (long) M13.get(w);
            case 14 -> (long) M14.get(w);
            case 15 -> (long) M15.get(w);
            case 16 -> (long) M16.get(w);
            case 17 -> (long) M17.get(w);
            case 18 -> (long) M18.get(w);
            case 19 -> (long) M19.get(w);
            case 20 -> (long) M20.get(w);
            case 21 -> (long) M21.get(w);
            case 22 -> (long) M22.get(w);
            case 23 -> (long) M23.get(w);
            case 24 -> (long) M24.get(w);
            case 25 -> (long) M25.get(w);
            case 26 -> (long) M26.get(w);
            case 27 -> (long) M27.get(w);
            case 28 -> (long) M28.get(w);
            case 29 -> (long) M29.get(w);
            case 30 -> (long) M30.get(w);
            case 31 -> (long) M31.get(w);
            default -> 0L;
        };
    }

    /**
     * Volatile write: pair of the volatile read of the value at linking (see the threads note
     * above).
     */
    private static void setM(Object w, int id, long v) {
        switch (id) {
            case 0 -> M0.setVolatile(w, v);
            case 1 -> M1.setVolatile(w, v);
            case 2 -> M2.setVolatile(w, v);
            case 3 -> M3.setVolatile(w, v);
            case 4 -> M4.setVolatile(w, v);
            case 5 -> M5.setVolatile(w, v);
            case 6 -> M6.setVolatile(w, v);
            case 7 -> M7.setVolatile(w, v);
            case 8 -> M8.setVolatile(w, v);
            case 9 -> M9.setVolatile(w, v);
            case 10 -> M10.setVolatile(w, v);
            case 11 -> M11.setVolatile(w, v);
            case 12 -> M12.setVolatile(w, v);
            case 13 -> M13.setVolatile(w, v);
            case 14 -> M14.setVolatile(w, v);
            case 15 -> M15.setVolatile(w, v);
            case 16 -> M16.setVolatile(w, v);
            case 17 -> M17.setVolatile(w, v);
            case 18 -> M18.setVolatile(w, v);
            case 19 -> M19.setVolatile(w, v);
            case 20 -> M20.setVolatile(w, v);
            case 21 -> M21.setVolatile(w, v);
            case 22 -> M22.setVolatile(w, v);
            case 23 -> M23.setVolatile(w, v);
            case 24 -> M24.setVolatile(w, v);
            case 25 -> M25.setVolatile(w, v);
            case 26 -> M26.setVolatile(w, v);
            case 27 -> M27.setVolatile(w, v);
            case 28 -> M28.setVolatile(w, v);
            case 29 -> M29.setVolatile(w, v);
            case 30 -> M30.setVolatile(w, v);
            case 31 -> M31.setVolatile(w, v);
            default -> {}
        }
    }

    // ---- check ----

    private static final CheckReport REPORT = new CheckReport(5_000_000_000L);

    private static void check(boolean ok) {
        if (REPORT.compared(ok)) {
            Log.info("datawatcher", "DIVERGENCE in the value mirror");
        }
        REPORT.maybeReport("datawatcher");
    }
}
