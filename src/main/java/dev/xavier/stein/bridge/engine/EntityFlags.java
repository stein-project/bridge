/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.entity.DataWatcher;
import net.minecraft.entity.Entity;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Entity.getFlag (pk.g) without the load chain: the flags byte (DataWatcher id 0: on fire,
 * sneaking, sprinting, eating, invisible) mirrored in an int of the entity itself.
 *
 * <p>Why: isSneaking (pk.av) and isInvisible (pk.ax) are read ~10 times per player per frame
 * (RenderPlayer.doRender and setModelVisibilities, the pose, ModelPlayer's setRotationAngles,
 * LayerHeldItem/LayerCape/LayerCustomHead, the shadow, the name at the end of the pass...), in
 * phases separated by a lot of drawing. Each game read is entity -> DataWatcher -> array
 * (sbWatched) -> WatchableObject -> Byte: three dependent loads on separate heap objects (the Byte
 * comes from the Byte.valueOf cache, always hot), and on the first read of each phase all three
 * miss the cache. With 400 players this was ~14% of the render thread with
 * DataWatchers.getWatchedObject at the top (crowd profile), and a microbenchmark with a cold cache
 * gives ~80-100 ns per read through the chain against ~10 through the mirror. The other values
 * (health, skin parts...) have their mirror in the DataWatcher itself ({@link DataWatchers}): one
 * miss instead of three; the flags, the most read, live on the entity, which is already in the
 * cache of whoever draws it.
 *
 * <p>Why the mirror is exact: the WatchableObject's value (pz$a.c, private) is only written in the
 * constructor and in setObject (pz$a.a(Object)), whose body is {@link DataWatchers#setObject}. Each
 * id's object gets the DataWatcher as owner when it enters the by-id array, and from then on every
 * setObject of id 0 goes through {@link #changed}. The entity's DataWatcher (pk.ac) is only
 * assigned in the constructor, with the entity itself as its owner (pz.a); the mirror is only
 * written when both sides match. On the first flag read, the current value goes into the mirror
 * (pk.sbFlags). Whoever reads the mirror always sees the same byte the chain would give at that
 * instant.
 *
 * <p>What stays on the game's path, on every call: a flag outside 0..7 (the shift and the byte's
 * sign extension would give a different result), a value that is not a Byte (the game would throw
 * ClassCastException/NPE; the mirror stays off and the chain throws the same), an id 0 object of
 * another DataWatcher (only by mod), a null DataWatcher, and everything with {@code
 * vanillaDataWatcher} (without the by-id array there is no owner).
 *
 * <p>Threads: the client and the integrated server write each DataWatcher on their own thread (the
 * render one and the server one; the network one only reads to serialize). Even so, the linking and
 * setObject follow an order that does not lose a write if the first read comes from another thread:
 * setObject writes the (volatile) value and then reads the owner; the linking sets the (volatile)
 * owner, copies the value and checks again until the value does not change. The cost is in
 * setObject (rare: only when the value changes, or per metadata packet) and in the linking (once
 * per entity); the read is a plain int.
 *
 * <p>{@code -Dsteinbridge.opt.entityFlags=false}: the game's chain on every read. {@code
 * -Dsteinbridge.opt.checkEntityFlags=true}: uses the mirror and checks each read against the
 * WatchableObject's value (counts divergences; logged every 5 s).
 */
public final class EntityFlags {

    private static final boolean ENABLED =
            EngineOptions.ENTITY_FLAGS && !EngineOptions.VANILLA_DATA_WATCHER;
    private static final boolean CHECK = ENABLED && EngineOptions.CHECK_ENTITY_FLAGS;

    /**
     * Mirror bit meaning "on and valid"; the low 8 are the byte. 0 = no mirror (the game's path).
     */
    private static final int LINKED = 0x100;

    /** The patcher field pk.sbFlags: not in the readable-name jar, so a VarHandle. */
    private static final VarHandle FLAGS;

    static {
        try {
            FLAGS =
                    MethodHandles.privateLookupIn(Entity.class, MethodHandles.lookup())
                            .findVarHandle(Entity.class, "sbFlags", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
        if (!ENABLED) {
            Log.info("flags", "entity flags read from the DataWatcher on every call (vanilla)");
        } else if (CHECK) {
            Log.info(
                    "flags", "check: each flag read from the mirror compared with the DataWatcher");
        }
    }

    /** Flag ids that fit in the mirrored byte: 0..7. */
    private static final int FLAG_RANGE_MASK = 7;

    private static final long REPORT_INTERVAL_NS = 5_000_000_000L;

    private static final CheckReport REPORT = new CheckReport(REPORT_INTERVAL_NS);
    private static long linked;

    private EntityFlags() {}

    /**
     * The body of Entity.getFlag(int) (pk.g): {@code (dataWatcher.getWatchableObjectByte(0) & 1 <<
     * flag) != 0}.
     */
    public static boolean getFlag(Object entity, int flag) {
        if (ENABLED) {
            int mirror = (int) FLAGS.get(entity);
            boolean inRange = (flag & ~FLAG_RANGE_MASK) == 0;
            if ((mirror & LINKED) != 0 && inRange) {
                boolean v = (mirror & 1 << flag) != 0;
                if (CHECK) {
                    check(entity, flag, v);
                }
                return v;
            }
            if (inRange) {
                link(entity);
            }
        }
        return (((Entity) entity).dataWatcher.getWatchableObjectByte(0) & 1 << flag) != 0;
    }

    /**
     * First flag read of the entity (or mirror turned off by a value that is not a Byte): the
     * current value goes into the mirror. Nothing changes if the DataWatcher is not its own, or if
     * the id 0 object does not exist or belongs to another DataWatcher.
     */
    private static void link(Object entity) {
        DataWatcher watcher = ((Entity) entity).dataWatcher;
        if (watcher == null || watcher.owner != entity) {
            return; // null DataWatcher: the game throws NPE on the usual path
        }
        Object wo =
                DataWatchers.getWatchedObject(watcher, 0); // the entry in the array marks the owner
        if (wo == null || DataWatchers.ownerOf(wo) != watcher) {
            return;
        }
        linked++;
        // Copy and check: a setObject from another thread between reading the value and writing
        // the mirror would be undone by this write; the loop back picks up the new value (setObject
        // writes the value before reading the owner).
        Object v;
        do {
            v = DataWatchers.valueOf(wo);
            FLAGS.setVolatile(entity, mirror(v));
        } while (DataWatchers.valueOf(wo) != v);
    }

    /** setObject of id 0 of a DataWatcher with a marked owner (DataWatchers.setObject). */
    static void changed(Object watcher, Object value) {
        Entity entity = ((DataWatcher) watcher).owner;
        if (ENABLED && entity != null && entity.dataWatcher == watcher) {
            FLAGS.setVolatile(entity, mirror(value));
        }
    }

    /** Mirror of a value: the byte with the on bit; 0 (the game's path) if it is not a Byte. */
    private static int mirror(Object v) {
        return v instanceof Byte b ? (b & 0xFF) | LINKED : 0;
    }

    /**
     * Checks against the value of the id 0 object (directly: the game's getter would read the
     * DataWatcher's mirror).
     */
    private static void check(Object entity, int flag, boolean v) {
        Object raw =
                DataWatchers.valueOf(
                        DataWatchers.getWatchedObject(((Entity) entity).dataWatcher, 0));
        boolean ok = v == ((((Byte) raw).byteValue() & 1 << flag) != 0);
        if (REPORT.compared(ok)) {
            Log.info("flags", "DIVERGENCE in the flag mirror: flag " + flag + " of " + entity);
        }
        if (REPORT.due()) {
            Log.info(
                    "flags",
                    "check: %d reads compared, %d divergences, %d entities" + " linked",
                    REPORT.checks,
                    REPORT.divergences,
                    linked);
        }
    }
}
