/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.bridge.engine;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Removal of dead particles in linear time.
 *
 * <p>On tick, 1.8.9's EffectRenderer.updateEffectAlphaLayer gathers the particles that died into a
 * list and removes them all with {@code list.removeAll(dead)}: for each live particle, the
 * ArrayList scans the whole list of dead ones — cost quadratic in the number of particles. With
 * rain (splashes) or hits that spawn particles, this lengthens the frame that has a tick; in the
 * rain profile it was 19% of the tick.
 *
 * <p>Here the dead go into an identity set and the list is filtered in a single pass, in the same
 * order. The result is the same as the original: removeAll compares by Entity.equals, which
 * compares the entity id, and each particle has its own id.
 *
 * <p>{@code -Dsteinbridge.opt.vanillaParticleRemoval=true}: the game's removeAll.
 */
public final class Particles {

    private static final boolean VANILLA = EngineOptions.VANILLA_PARTICLE_REMOVAL;

    /** Below this, removeAll is already cheap. */
    private static final int SMALL = 8;

    private Particles() {}

    /** List.removeAll(dead) inside updateEffectAlphaLayer. */
    @SuppressWarnings("unchecked")
    public static boolean removeDead(Object list, Object dead) {
        List<Object> l = (List<Object>) list;
        Collection<Object> d = (Collection<Object>) dead;
        if (VANILLA || d.size() < SMALL) {
            return l.removeAll(d);
        }
        Set<Object> set = Collections.newSetFromMap(new IdentityHashMap<>(d.size()));
        set.addAll(d);
        return l.removeIf(set::contains);
    }
}
