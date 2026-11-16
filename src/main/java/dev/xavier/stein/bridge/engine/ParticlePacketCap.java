/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.network.play.server.S2APacketParticles;

/**
 * Fix for the particle packet that freezes the game: the number of particles one S2A packet spawns
 * is capped at what the particle renderer can keep.
 *
 * <p>NetHandlerPlayClient.handleParticles spawns {@code getParticleCount()} particles in a loop on
 * the game thread, and the count is any int the server writes: 2^31 kept the game thread busy for
 * minutes (the client stops responding, the server times it out). EffectRenderer keeps at most 4000
 * particles in each of its lists and drops the oldest for every new one, and the particles of one
 * packet are all of one type, so they go to one or two lists of its four layers: past 16000 (4000 x
 * 4) a packet only replaces its own particles with others at the same random spread. The patcher
 * sends both reads of the count in handleParticles (the zero test and the loop bound) here; any
 * count up to the cap, including 0 and the negatives, is unchanged.
 */
public final class ParticlePacketCap {

    /** Four layers of 4000 particles each: more than one packet's particles can ever fill. */
    static final int MAX_PARTICLES = 4 * 4000;

    private ParticlePacketCap() {}

    /** S2APacketParticles.getParticleCount in NetHandlerPlayClient.handleParticles. */
    public static int count(Object packet) {
        return Math.min(((S2APacketParticles) packet).getParticleCount(), MAX_PARTICLES);
    }
}
