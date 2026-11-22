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
 * The {@code -D} options of the fixes for what a server sends, world changes, downloads and the
 * integrated server's leaks: each one brings back the 1.8.9 behaviour of one fix, for A/B and for a
 * player who wants it. The fixes are on by default.
 */
public final class NetFixOptions {

    private NetFixOptions() {}

    /**
     * {@code -Dsteinbridge.opt.vanillaTeams=true}: a team packet for a team that already exists or
     * that does not exist, or that removes a player from a team they are not on, throws and is lost
     * whole (default: applied as later versions do).
     */
    static final boolean VANILLA_TEAMS = Opt.flag("steinbridge.opt.vanillaTeams");

    /**
     * {@code -Dsteinbridge.opt.vanillaWorldlessPackets=true}: play packets that need the world are
     * handled even while there is none, and throw (default: left out, with a log line).
     */
    static final boolean VANILLA_WORLDLESS_PACKETS =
            Opt.flag("steinbridge.opt.vanillaWorldlessPackets");

    /**
     * {@code -Dsteinbridge.opt.vanillaWorldGc=true}: every world change and every single player
     * start asks for a full garbage collection on the game thread (default: left to the collector).
     */
    static final boolean VANILLA_WORLD_GC = Opt.flag("steinbridge.opt.vanillaWorldGc");

    /**
     * {@code -Dsteinbridge.opt.vanillaJoinTitles=true}: a title still on screen when joining a
     * server (or the next server behind a proxy) stays on (default: cleared on each join).
     */
    static final boolean VANILLA_JOIN_TITLES = Opt.flag("steinbridge.opt.vanillaJoinTitles");

    /**
     * {@code -Dsteinbridge.opt.vanillaSkinDownload=true}: a skin or cape download has no time limit
     * and the decoded skin image stays on the heap after its upload (default: 10 s to connect and
     * 10 s per read, the image let go once uploaded).
     */
    static final boolean VANILLA_SKIN_DOWNLOAD = Opt.flag("steinbridge.opt.vanillaSkinDownload");

    /**
     * {@code -Dsteinbridge.opt.vanillaServerPackDir=true}: the first server resource pack fails
     * when the server-resource-packs folder does not exist yet (default: the folder is created).
     */
    static final boolean VANILLA_SERVER_PACK_DIR = Opt.flag("steinbridge.opt.vanillaServerPackDir");

    /**
     * {@code -Dsteinbridge.opt.vanillaWorldLeaks=true}: in single player the region file stream of
     * each loaded chunk is left open, and each mob's path finder keeps the last area it searched
     * (default: closed, let go).
     */
    static final boolean VANILLA_WORLD_LEAKS = Opt.flag("steinbridge.opt.vanillaWorldLeaks");

    /**
     * {@code -Dsteinbridge.opt.vanillaNbtNull=true}: a null value put in an NBT compound stays
     * there until writing the compound throws, far from the code that put it (default: left out,
     * with the stack trace of who put it in the log).
     */
    static final boolean VANILLA_NBT_NULL = Opt.flag("steinbridge.opt.vanillaNbtNull");

    /**
     * {@code -Dsteinbridge.opt.vanillaDigits=true}: on a system whose language writes numbers with
     * other digits (Arabic, Persian...), String.format writes those digits into texture paths and
     * the debug screen (default: Latin digits, the rest of the number format kept).
     */
    static final boolean VANILLA_DIGITS = Opt.flag("steinbridge.opt.vanillaDigits");
}
