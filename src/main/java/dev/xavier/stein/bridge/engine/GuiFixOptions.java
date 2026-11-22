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
 * The {@code -D} options of the screen, sound and input fixes: each one brings back the 1.8.9 (or,
 * for the keyboard and the links, the LWJGL 2 shim's) behaviour of one fix, for A/B and for a
 * player who wants it.
 */
public final class GuiFixOptions {

    private GuiFixOptions() {}

    /**
     * {@code -Dsteinbridge.opt.vanillaPhysicalKeys=true}: letter and symbol keys take the code of
     * their position on a US keyboard, whatever the layout (default: the code of the key's label,
     * as LWJGL 2 did on Windows and Linux).
     */
    public static final boolean VANILLA_PHYSICAL_KEYS =
            Opt.flag("steinbridge.opt.vanillaPhysicalKeys");

    /**
     * {@code -Dsteinbridge.opt.vanillaOpenFolder=true}: links and the resource pack folder open
     * only through Java's Desktop, the pack folder on the game thread (default: xdg-open first on
     * Linux, off the game thread).
     */
    public static final boolean VANILLA_OPEN_FOLDER = Opt.flag("steinbridge.opt.vanillaOpenFolder");

    /**
     * {@code -Dsteinbridge.opt.vanillaPackFiles=true}: resource packs taken off the selected list
     * keep their zip open (default: closed, so the file can be moved or deleted).
     */
    static final boolean VANILLA_PACK_FILES = Opt.flag("steinbridge.opt.vanillaPackFiles");

    /**
     * {@code -Dsteinbridge.opt.vanillaOptionsSave=true}: options screens save only on their Done
     * button, and the chat keeps its old line breaks after a language change (default: saved
     * whenever an options screen closes, chat re-wrapped).
     */
    static final boolean VANILLA_OPTIONS_SAVE = Opt.flag("steinbridge.opt.vanillaOptionsSave");

    /**
     * {@code -Dsteinbridge.opt.vanillaKeyResync=true}: keys held while a screen closes are only
     * seen once pressed again (default: re-read on closing, as from 1.12).
     */
    static final boolean VANILLA_KEY_RESYNC = Opt.flag("steinbridge.opt.vanillaKeyResync");

    /**
     * {@code -Dsteinbridge.opt.vanillaArmorBreakSound=true}: in multiplayer the player does not
     * hear or see their own armor break (default: they do, as from 1.15).
     */
    static final boolean VANILLA_ARMOR_BREAK_SOUND =
            Opt.flag("steinbridge.opt.vanillaArmorBreakSound");

    /**
     * {@code -Dsteinbridge.opt.vanillaPauseSounds=true}: resuming from the pause menu plays again
     * the sounds that had already ended (default: only the ones the pause stopped, as from 1.14).
     */
    static final boolean VANILLA_PAUSE_SOUNDS = Opt.flag("steinbridge.opt.vanillaPauseSounds");

    /**
     * {@code -Dsteinbridge.opt.vanillaSoundWarnings=true}: an unknown sound writes its warning to
     * the log every time it plays (default: once per sound).
     */
    static final boolean VANILLA_SOUND_WARNINGS = Opt.flag("steinbridge.opt.vanillaSoundWarnings");

    /**
     * {@code -Dsteinbridge.opt.vanillaGameOverButtons=true}: the death screen's buttons stay off
     * for good when the window is resized during its first second (default: they come back).
     */
    static final boolean VANILLA_GAME_OVER_BUTTONS =
            Opt.flag("steinbridge.opt.vanillaGameOverButtons");

    /**
     * {@code -Dsteinbridge.opt.vanillaPotionLevels=true}: effects above level IV show no level in
     * the inventory and a raw key in the potion tooltip (default: the level, as from 1.13).
     */
    static final boolean VANILLA_POTION_LEVELS = Opt.flag("steinbridge.opt.vanillaPotionLevels");

    /**
     * {@code -Dsteinbridge.opt.vanillaChatClick=true}: a click just above the open chat's lines can
     * hit a hidden line's link (default: only the visible lines).
     */
    static final boolean VANILLA_CHAT_CLICK = Opt.flag("steinbridge.opt.vanillaChatClick");

    /**
     * {@code -Dsteinbridge.opt.vanillaScreenInput=true}: keys pressed in the same tick as a click
     * that changed the screen go to the old screen (default: to the new one, as from 1.13).
     */
    static final boolean VANILLA_SCREEN_INPUT = Opt.flag("steinbridge.opt.vanillaScreenInput");

    /**
     * {@code -Dsteinbridge.opt.vanillaCharKeys=true}: a character typed without a key (IME) also
     * counts as the key with its number, so '&lt;' takes a screenshot (default: it does not, as
     * from 1.13).
     */
    static final boolean VANILLA_CHAR_KEYS = Opt.flag("steinbridge.opt.vanillaCharKeys");

    /**
     * {@code -Dsteinbridge.opt.vanillaMipmapSlider=true}: the vanilla video screen's mipmap slider
     * reloads every texture at each step of the drag (default: once, on release, as from 1.14).
     */
    static final boolean VANILLA_MIPMAP_SLIDER = Opt.flag("steinbridge.opt.vanillaMipmapSlider");

    /**
     * {@code -Dsteinbridge.opt.vanillaSpectatorPumpkin=true}: a spectator wearing a pumpkin sees
     * its overlay (default: not drawn in spectator mode).
     */
    static final boolean VANILLA_SPECTATOR_PUMPKIN =
            Opt.flag("steinbridge.opt.vanillaSpectatorPumpkin");

    /**
     * {@code -Dsteinbridge.opt.vanillaTextStyles=true}: the bold, italic, underline, strikethrough
     * and obfuscated styles a text's shadow ends with carry over to the start of the text itself
     * (default: each pass starts plain).
     */
    static final boolean VANILLA_TEXT_STYLES = Opt.flag("steinbridge.opt.vanillaTextStyles");

    /**
     * {@code -Dsteinbridge.opt.vanillaGlyphStart=true}: unicode glyphs that start in the right half
     * of their cell (some Korean letters, half blocks) are drawn from a wrong place and push the
     * rest of the text off the screen (default: read as the unsigned number they are).
     */
    static final boolean VANILLA_GLYPH_START = Opt.flag("steinbridge.opt.vanillaGlyphStart");

    /**
     * {@code -Dsteinbridge.opt.vanillaLanguageReload=true}: choosing a language reloads every
     * resource (textures, models, sounds), several seconds (default: only the translations).
     */
    static final boolean VANILLA_LANGUAGE_RELOAD =
            Opt.flag("steinbridge.opt.vanillaLanguageReload");

    /**
     * {@code -Dsteinbridge.opt.vanillaMouseKeys=true}: the perspective, smooth camera, fullscreen
     * and screenshot keys, and the inventory, drop and pick block keys in inventories, do nothing
     * when bound to a mouse button (default: they work as on the keyboard).
     */
    static final boolean VANILLA_MOUSE_KEYS = Opt.flag("steinbridge.opt.vanillaMouseKeys");

    /**
     * {@code -Dsteinbridge.opt.vanillaChatClose=true}: a server closing a window also closes the
     * chat being typed, and the message is lost (default: the chat stays open).
     */
    static final boolean VANILLA_CHAT_CLOSE = Opt.flag("steinbridge.opt.vanillaChatClose");

    /**
     * {@code -Dsteinbridge.opt.vanillaSidebarText=true}: the sidebar's text is drawn with an alpha
     * of 1/8, so it fades whenever blending was left on before it (default: opaque).
     */
    static final boolean VANILLA_SIDEBAR_TEXT = Opt.flag("steinbridge.opt.vanillaSidebarText");

    /**
     * {@code -Dsteinbridge.opt.vanillaFullscreenSave=true}: switching to and from fullscreen with
     * the key is not written to options.txt (default: saved, so the next start opens the same way).
     */
    static final boolean VANILLA_FULLSCREEN_SAVE =
            Opt.flag("steinbridge.opt.vanillaFullscreenSave");

    /**
     * {@code -Dsteinbridge.opt.vanillaBookHover=true}: a book's hover text is drawn before its
     * buttons, which cover it (default: after them).
     */
    static final boolean VANILLA_BOOK_HOVER = Opt.flag("steinbridge.opt.vanillaBookHover");
}
