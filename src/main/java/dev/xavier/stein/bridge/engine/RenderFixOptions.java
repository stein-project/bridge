/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Opt;

/**
 * Every {@code -D} option of the fixes to 1.8.9 rendering bugs, read once through {@link Opt}. Each
 * one brings one bug back, for comparison; the fixes are on by default.
 */
public final class RenderFixOptions {

    private RenderFixOptions() {}

    /**
     * {@code -Dsteinbridge.opt.vanillaTerrainSeeds=true}: with the camera above the build limit or
     * below 0, the chunk search starts only from the edge layer's chunks whose own box is in view,
     * so the world and its entities vanish when looking level (default: whole columns).
     */
    static final boolean VANILLA_TERRAIN_SEEDS = Opt.flag("steinbridge.opt.vanillaTerrainSeeds");

    /**
     * {@code -Dsteinbridge.opt.vanillaBannerSway=true}: the banner sway is computed from the full
     * world time in float, so it stutters and then stops on old worlds (default: the phase, which
     * is the same picture).
     */
    static final boolean VANILLA_BANNER_SWAY = Opt.flag("steinbridge.opt.vanillaBannerSway");

    /**
     * {@code -Dsteinbridge.opt.vanillaInvalidTiles=true}: a tile entity already removed from the
     * world keeps being drawn until its chunk is rebuilt (default: not drawn).
     */
    static final boolean VANILLA_INVALID_TILES = Opt.flag("steinbridge.opt.vanillaInvalidTiles");

    /**
     * {@code -Dsteinbridge.opt.vanillaTagPitch=true}: in the front view (F5 twice) name tags and
     * other camera-facing sprites tilt the wrong way (default: they face the camera).
     */
    static final boolean VANILLA_TAG_PITCH = Opt.flag("steinbridge.opt.vanillaTagPitch");

    /**
     * {@code -Dsteinbridge.opt.vanillaBeaconBeam=true}: the beacon beam is drawn twice while its
     * chunk is in view, brighter than intended (default: once).
     */
    static final boolean VANILLA_BEACON_BEAM = Opt.flag("steinbridge.opt.vanillaBeaconBeam");

    /**
     * {@code -Dsteinbridge.opt.vanillaSkullBlend=true}: heads are drawn without blending, so
     * semi-transparent pixels of a skin's hat come out opaque (default: blended).
     */
    static final boolean VANILLA_SKULL_BLEND = Opt.flag("steinbridge.opt.vanillaSkullBlend");

    /**
     * {@code -Dsteinbridge.opt.vanillaProjectileAlpha=true}: arrows, the fishing bobber and the
     * fire on burning entities take whatever alpha test the previous draw left, and can come out
     * with black or opaque backgrounds (default: alpha test on while they are drawn).
     */
    static final boolean VANILLA_PROJECTILE_ALPHA =
            Opt.flag("steinbridge.opt.vanillaProjectileAlpha");

    /**
     * {@code -Dsteinbridge.opt.vanillaFogLight=true}: the fog colour takes the light of the block
     * at the feet, so it darkens when that block is dark (default: the light at the eyes).
     */
    static final boolean VANILLA_FOG_LIGHT = Opt.flag("steinbridge.opt.vanillaFogLight");

    /**
     * {@code -Dsteinbridge.opt.vanillaBuilderGrowth=true}: the vertex builder decides whether to
     * grow from a position only quads move, so a batch built vertex by vertex past its size throws
     * (default: from what is in use).
     */
    static final boolean VANILLA_BUILDER_GROWTH = Opt.flag("steinbridge.opt.vanillaBuilderGrowth");

    /**
     * {@code -Dsteinbridge.opt.vanillaCloseProjectiles=true}: a thrown snowball, egg, ender pearl,
     * potion or bottle o' enchanting is drawn from its first tick, covering the camera of whoever
     * threw it (default: not drawn in its first two ticks while near the camera, as from 1.15).
     */
    static final boolean VANILLA_CLOSE_PROJECTILES =
            Opt.flag("steinbridge.opt.vanillaCloseProjectiles");

    /**
     * {@code -Dsteinbridge.opt.vanillaBannerCache=true}: once 256 banner designs were drawn in the
     * last minute, new designs are not drawn at all (default: the least recently drawn design not
     * seen in the last second makes room).
     */
    static final boolean VANILLA_BANNER_CACHE = Opt.flag("steinbridge.opt.vanillaBannerCache");

    /**
     * {@code -Dsteinbridge.opt.vanillaUnreadableImages=true}: an image the game cannot decode in a
     * resource pack (another format with a .png name, a broken file) crashes the game when it is
     * loaded as a texture (default: the missing texture, with a log line).
     */
    static final boolean VANILLA_UNREADABLE_IMAGES =
            Opt.flag("steinbridge.opt.vanillaUnreadableImages");
}
