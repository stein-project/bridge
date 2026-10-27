/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import java.util.ArrayList;
import java.util.Set;

/**
 * A lock for the class list every ClassInheritanceMultiMap shares (the entity lists of each chunk
 * section).
 *
 * <p>The list is one static HashSet for the whole game: a new chunk walks it in its constructor,
 * and a lookup by a class not seen before adds to it. In singleplayer the client thread (a chunk
 * arriving) and the integrated server thread (a chunk loading, an entity lookup) do this at the
 * same time, and the walk threw ConcurrentModificationException: the chunk packet was lost and that
 * column stayed a hole until the player left the world (seen at view distance 32, with the server
 * busy generating). Here the additions in the class's method and the walk in its constructor take
 * the set's lock, and the walk goes over a copy; the instance's own set takes the same path,
 * harmlessly.
 */
public final class ClassMapGuard {

    private ClassMapGuard() {}

    /** Set.add in ClassInheritanceMultiMap.createLookup. */
    @SuppressWarnings("unchecked")
    public static boolean add(Object setObj, Object value) {
        Set<Object> set = (Set<Object>) setObj;
        synchronized (set) {
            return set.add(value);
        }
    }

    /**
     * Set.iterator in ClassInheritanceMultiMap's constructor: over a copy, taken under the lock.
     */
    @SuppressWarnings("unchecked")
    public static Object iterator(Object setObj) {
        Set<Object> set = (Set<Object>) setObj;
        ArrayList<Object> copy;
        synchronized (set) {
            copy = new ArrayList<>(set);
        }
        return copy.iterator();
    }
}
