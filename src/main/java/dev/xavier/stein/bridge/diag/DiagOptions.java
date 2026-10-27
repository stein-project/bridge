/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.diag;

import dev.xavier.stein.bridge.core.Opt;

/**
 * Every {@code -Dsteinbridge.diag.*} option of the verification scenes, grouped by the scene class
 * that uses it. The {@code ...AtSec} options are the second (after startup) a scene runs at; {@code
 * -1} means off. A scene's behaviour is documented on the scene itself.
 */
final class DiagOptions {

    private DiagOptions() {}

    // ---- DiagCapture ----

    /**
     * {@code -Dsteinbridge.diag.screenshotAtSec=N}: takes a screenshot N seconds after startup
     * (default: off).
     */
    static final long SCREENSHOT_AT = Opt.number("steinbridge.diag.screenshotAtSec", -1L);

    /**
     * {@code -Dsteinbridge.diag.screenshotName=name}: file name of the screenshot (default {@code
     * diag}).
     */
    static final String SCREENSHOT_NAME = Opt.text("steinbridge.diag.screenshotName", "diag");

    /**
     * {@code -Dsteinbridge.diag.screenshotCount=K}: how many consecutive screenshots to take
     * (default 1).
     */
    static final int SCREENSHOT_COUNT = Opt.integer("steinbridge.diag.screenshotCount", 1);

    /**
     * {@code -Dsteinbridge.diag.screenshotEveryMs=N}: milliseconds between consecutive screenshots
     * (default 250).
     */
    static final long SCREENSHOT_EVERY_MS = Opt.number("steinbridge.diag.screenshotEveryMs", 250L);

    // ---- DiagChatScenes ----

    /**
     * {@code -Dsteinbridge.diag.chatFile=file}: file with the {@code S:command} chat entries, one
     * per line (default: unset).
     */
    static final String CHAT_FILE = Opt.text("steinbridge.diag.chatFile", null);

    /**
     * {@code -Dsteinbridge.diag.chat=S:command|...}: chat entries run at second S (default: none).
     */
    static final String CHAT = Opt.text("steinbridge.diag.chat", "");

    // ---- DiagClickScenes ----

    /**
     * {@code -Dsteinbridge.diag.cpsTestAtSec=N}: runs the clicks-per-second test N seconds after
     * startup (default: off).
     */
    static final long CPS_TEST_AT = Opt.number("steinbridge.diag.cpsTestAtSec", -1L);

    /** {@code -Dsteinbridge.diag.cpsClicks=N}: how many clicks the CPS test makes (default 10). */
    static final int CPS_CLICKS = Opt.integer("steinbridge.diag.cpsClicks", 10);

    /**
     * {@code -Dsteinbridge.diag.cpsStepMs=N}: milliseconds between the CPS test's clicks (default
     * 80).
     */
    static final long CPS_STEP_MS = Opt.number("steinbridge.diag.cpsStepMs", 80L);

    /**
     * {@code -Dsteinbridge.diag.clickTestAtSec=N}: runs the miss-penalty click test N seconds after
     * startup (default: off).
     */
    static final long CLICK_TEST_AT = Opt.number("steinbridge.diag.clickTestAtSec", -1L);

    /**
     * {@code -Dsteinbridge.diag.clickHoldMs=N}: milliseconds the button stays pressed in the click
     * test (default 5).
     */
    static final long CLICK_HOLD_MS = Opt.number("steinbridge.diag.clickHoldMs", 5L);

    /**
     * {@code -Dsteinbridge.diag.clickGapMs=N}: milliseconds the button stays released in the click
     * test (default 5).
     */
    static final long CLICK_GAP_MS = Opt.number("steinbridge.diag.clickGapMs", 5L);

    /**
     * {@code -Dsteinbridge.diag.clickForSec=N}: seconds each phase of the click test lasts (default
     * 4).
     */
    static final long CLICK_FOR_SEC = Opt.number("steinbridge.diag.clickForSec", 4L);

    /**
     * {@code -Dsteinbridge.diag.clickInject=false}: the click test only watches, it does not click
     * (default: clicks; any value but {@code false} clicks).
     */
    static final boolean CLICK_INJECT = Opt.unlessFalse("steinbridge.diag.clickInject");

    /**
     * {@code -Dsteinbridge.diag.pickTestAtSec=N}: runs the pick-block test N seconds after startup
     * (default: off).
     */
    static final long PICK_TEST_AT = Opt.number("steinbridge.diag.pickTestAtSec", -1L);

    /**
     * {@code -Dsteinbridge.diag.serverClicksAtSec=N}: runs the server-list clicks test N seconds
     * after startup (default: off).
     */
    static final long SERVER_CLICKS_AT = Opt.number("steinbridge.diag.serverClicksAtSec", -1L);

    // ---- DiagInputScenes ----

    /**
     * {@code -Dsteinbridge.diag.drag=S:x:y:x:y[:button]|...}: mouse drags at second S (default:
     * none).
     */
    static final String[] DRAGS = list("steinbridge.diag.drag", "\\|", 0);

    /**
     * {@code -Dsteinbridge.diag.type=S:text}: types text at second S (default: none; split at the
     * first colon).
     */
    static final String[] TYPE = list("steinbridge.diag.type", ":", 2);

    /**
     * {@code -Dsteinbridge.diag.typeGapMs=N}: milliseconds between typed characters (default 60).
     */
    static final long TYPE_GAP_MS = Opt.number("steinbridge.diag.typeGapMs", 60L);

    /**
     * {@code -Dsteinbridge.diag.holdKeyAtSec=N}: holds a key down N seconds after startup (default:
     * off).
     */
    static final long HOLD_KEY_AT = Opt.number("steinbridge.diag.holdKeyAtSec", -1L);

    /**
     * {@code -Dsteinbridge.diag.holdKey=code}: key code (or mouse button) held by the hold-key
     * scene (default 46).
     */
    static final int HOLD_KEY = Opt.integer("steinbridge.diag.holdKey", 46);

    /** {@code -Dsteinbridge.diag.holdKeyMs=N}: milliseconds the key stays held (default 5000). */
    static final long HOLD_KEY_MS = Opt.number("steinbridge.diag.holdKeyMs", 5000L);

    /**
     * {@code -Dsteinbridge.diag.wheel=S:x:y:steps|...}: mouse wheel turns at second S (default:
     * none).
     */
    static final String[] WHEELS = list("steinbridge.diag.wheel", "\\|", 0);

    /**
     * {@code -Dsteinbridge.diag.inputProbe=true}: prints the input state every so often (default:
     * off).
     */
    static final boolean INPUT_PROBE = Opt.flag("steinbridge.diag.inputProbe");

    /** {@code -Dsteinbridge.diag.keys=S:code:...|...}: key presses at second S (default: none). */
    static final String[] KEYS = list("steinbridge.diag.keys", "\\|", 0);

    /**
     * {@code -Dsteinbridge.diag.attackAtSec=S1,S2...}: seconds at which the nearest player is hit
     * (default: none).
     */
    static final String[] ATTACKS = list("steinbridge.diag.attackAtSec", ",", 0);

    // ---- DiagScreenScenes ----

    /**
     * {@code -Dsteinbridge.diag.inventoryAtSec=N}: opens the inventory N seconds after startup
     * (default: off).
     */
    static final long INVENTORY_AT = Opt.number("steinbridge.diag.inventoryAtSec", -1L);

    /**
     * {@code -Dsteinbridge.diag.pauseAtSec=N}: opens the pause menu N seconds after startup
     * (default: off).
     */
    static final long PAUSE_AT = Opt.number("steinbridge.diag.pauseAtSec", -1L);

    /**
     * {@code -Dsteinbridge.diag.controlsAtSec=N}: opens the controls screen N seconds after startup
     * (default: off).
     */
    static final long CONTROLS_AT = Opt.number("steinbridge.diag.controlsAtSec", -1L);

    /**
     * {@code -Dsteinbridge.diag.serverListAtSec=N}: opens the server list N seconds after startup
     * (default: off).
     */
    static final long SERVER_LIST_AT = Opt.number("steinbridge.diag.serverListAtSec", -1L);

    /**
     * {@code -Dsteinbridge.diag.soundReloadAtSec=N}: reloads resources twice in a row N seconds
     * after startup (default: off).
     */
    static final long SOUND_RELOAD_AT = Opt.number("steinbridge.diag.soundReloadAtSec", -1L);

    /**
     * {@code -Dsteinbridge.diag.serverListClick=false}: only measures the server ping, no click
     * (default: clicks; any value but {@code false} clicks).
     */
    static final boolean SERVER_LIST_CLICK = Opt.unlessFalse("steinbridge.diag.serverListClick");

    /**
     * {@code -Dsteinbridge.diag.videoAtSec=N}: opens the video settings N seconds after startup
     * (default: off).
     */
    static final long VIDEO_AT = Opt.number("steinbridge.diag.videoAtSec", -1L);

    /**
     * {@code -Dsteinbridge.diag.loaderMenuAtSec=N}: opens the Stein Loader menu N seconds after
     * startup (default: off).
     */
    static final long LOADER_MENU_AT = Opt.number("steinbridge.diag.loaderMenuAtSec", -1L);

    /** {@code -Dsteinbridge.diag.loaderMenuPage=N}: page the Loader menu opens on (default 0). */
    static final int LOADER_MENU_PAGE = Opt.integer("steinbridge.diag.loaderMenuPage", 0);

    /**
     * {@code -Dsteinbridge.diag.loaderMenuSelected=N}: mod list line selected when the Loader menu
     * opens (default -1: none).
     */
    static final int LOADER_MENU_SELECTED = Opt.integer("steinbridge.diag.loaderMenuSelected", -1);

    /**
     * {@code -Dsteinbridge.diag.minimizeAtSec=N}: minimizes the window N seconds after startup
     * (default: off).
     */
    static final long MINIMIZE_AT = Opt.number("steinbridge.diag.minimizeAtSec", -1L);

    /**
     * {@code -Dsteinbridge.diag.resizeAtSec=S:WxH}: sets the window to W by H pixels S seconds
     * after startup, as dragging its border would (default: off).
     */
    static final String RESIZE_AT = Opt.text("steinbridge.diag.resizeAtSec", "");

    /**
     * {@code -Dsteinbridge.diag.minimizeMs=N}: milliseconds the window stays minimized (default
     * 3000).
     */
    static final long MINIMIZE_MS = Opt.number("steinbridge.diag.minimizeMs", 3000L);

    /**
     * {@code -Dsteinbridge.diag.soundOptionsAtSec=N}: opens the sound options N seconds after
     * startup (default: off).
     */
    static final long SOUND_OPTIONS_AT = Opt.number("steinbridge.diag.soundOptionsAtSec", -1L);

    /**
     * {@code -Dsteinbridge.diag.clicks=S:id,...}: button clicks on the open screen, by second and
     * button id (default: none).
     */
    static final String CLICKS = Opt.text("steinbridge.diag.clicks", "");

    /**
     * {@code -Dsteinbridge.diag.actionBar=S:text|...}: action bar messages at second S (default:
     * none).
     */
    static final String[] ACTION_BAR = list("steinbridge.diag.actionBar", "\\|", 0);

    /**
     * {@code -Dsteinbridge.diag.boss=from:to:name}: shows a boss bar between two seconds (default:
     * unset, one empty entry).
     */
    static final String[] BOSS = Opt.text("steinbridge.diag.boss", "").split(":", 3);

    // ---- DiagSingleplayerScene ----

    /**
     * {@code -Dsteinbridge.diag.singleplayer=name}: opens (creating it if needed) this singleplayer
     * world (default: unset).
     */
    static final String SP_WORLD = Opt.text("steinbridge.diag.singleplayer", null);

    /**
     * {@code -Dsteinbridge.diag.singleplayerAtSec=N}: seconds after startup the world is opened
     * (default 6).
     */
    static final long SP_AT = Opt.number("steinbridge.diag.singleplayerAtSec", 6L);

    /**
     * {@code -Dsteinbridge.diag.stallServerAtSec=N}: stops the integrated server thread once, N
     * seconds after startup (default: off).
     */
    static final long SP_STALL_AT = Opt.number("steinbridge.diag.stallServerAtSec", -1L);

    /**
     * {@code -Dsteinbridge.diag.stallServerMs=N}: milliseconds the server thread stays stopped
     * (default 800).
     */
    static final long SP_STALL_MS = Opt.number("steinbridge.diag.stallServerMs", 800L);

    // ---- DiagWorldScenes ----

    /**
     * {@code -Dsteinbridge.diag.nbtAtSec=N}: prints the NBT map of some players' armor N seconds
     * after startup (default: off).
     */
    static final long NBT_AT = Opt.number("steinbridge.diag.nbtAtSec", -1L);

    /**
     * {@code -Dsteinbridge.diag.debugOff=true}: keeps the F3 screen closed during benchmark runs
     * (default: off).
     */
    static final boolean DEBUG_OFF = Opt.flag("steinbridge.diag.debugOff");

    /**
     * {@code -Dsteinbridge.diag.gearAtSec=N}: dresses the player in test gear N seconds after
     * startup (default: off).
     */
    static final long GEAR_AT = Opt.number("steinbridge.diag.gearAtSec", -1L);

    /**
     * {@code -Dsteinbridge.diag.gearProtection=N}: Protection enchantment level on the test gear
     * (default 4).
     */
    static final int GEAR_PROTECTION = Opt.integer("steinbridge.diag.gearProtection", 4);

    /**
     * {@code -Dsteinbridge.diag.reloadChunksEverySec=N}: reloads the terrain every N seconds
     * (default: off).
     */
    static final long RELOAD_EVERY = Opt.number("steinbridge.diag.reloadChunksEverySec", -1L);

    /**
     * {@code -Dsteinbridge.diag.graphicsAtSec=S:1,S:0...}: switches Fancy (1) or Fast (0) graphics
     * at second S (default: none).
     */
    static final String[] GRAPHICS = list("steinbridge.diag.graphicsAtSec", ",", 0);

    /**
     * {@code -Dsteinbridge.diag.citAtSec=N}: runs the custom-item-textures scene N seconds after
     * startup (default: off).
     */
    static final long CIT_AT = Opt.number("steinbridge.diag.citAtSec", -1L);

    /**
     * {@code -Dsteinbridge.diag.thirdPerson=N}: keeps the third-person view, 1 from behind or 2
     * from the front (default 0: off).
     */
    static final int THIRD_PERSON = Opt.integer("steinbridge.diag.thirdPerson", 0);

    /**
     * {@code -Dsteinbridge.diag.pitch=N}: keeps the player looking N degrees down (negative: up),
     * every frame — the player's own shadow in first person (default: off).
     */
    static final int PITCH = Opt.integer("steinbridge.diag.pitch", -1000);

    /** {@code -Dsteinbridge.diag.yaw=N}: with {@link #PITCH}, keeps the player facing N degrees. */
    static final int YAW = Opt.integer("steinbridge.diag.yaw", -1000);

    /**
     * {@code -Dsteinbridge.diag.showcaseAtSec=N}: opens the video settings' showcase (the landscape
     * drawn instead of the world) N seconds after startup, with a world loaded (default: off).
     */
    static final long SHOWCASE_AT = Opt.number("steinbridge.diag.showcaseAtSec", -1L);

    /**
     * {@code -Dsteinbridge.diag.showcaseNightAtSec=N}: turns the showcase to night N seconds after
     * startup, and back to day N seconds after that, alternating (default: off).
     */
    static final long SHOWCASE_NIGHT_AT = Opt.number("steinbridge.diag.showcaseNightAtSec", -1L);

    /**
     * A list option: empty when the property is unset or empty, otherwise the value split at {@code
     * regex}.
     */
    private static String[] list(String name, String regex, int limit) {
        String value = Opt.text(name, "");
        return value.isEmpty() ? new String[0] : value.split(regex, limit);
    }
}
