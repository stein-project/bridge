/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.bridge.diag;

/**
 * Entry point of the visual-check scenes triggered by time, with nobody touching the game: decides
 * whether any scene is on and, every frame, hands over to each scene in turn.
 *
 * <p>The scenes live in cohesive groups, each owning its {@code -Dsteinbridge.diag.*} option reads
 * and its state: {@link DiagClickScenes} (clicks), {@link DiagInputScenes} (drags, typing, keys,
 * wheel), {@link DiagScreenScenes} (screens, menus, HUD), {@link DiagWorldScenes} (gear, terrain,
 * settings), {@link DiagSingleplayerScene} (integrated server), {@link DiagChatScenes} and {@link
 * DiagLocalCommands} (chat and local commands), {@link DiagCapture} (screenshots). {@link DiagGame}
 * holds the shared reflection helpers.
 *
 * <p>Called from Display.update, on the render thread, only when {@link #ENABLED}: a static final
 * flag, so in a normal game (no {@code steinbridge.diag.*} property) the JIT drops the call and the
 * scenes cost nothing per frame.
 */
public final class Scenes {

    /** True only when some {@code steinbridge.diag.*} system property is set; read once. */
    public static final boolean ENABLED = anyDiagProperty();

    private static boolean anyDiagProperty() {
        for (Object k : System.getProperties().keySet()) {
            if (k instanceof String && ((String) k).startsWith("steinbridge.diag.")) {
                return true;
            }
        }
        return false;
    }

    /** Nano time at which the scenes started counting (their "N seconds after startup"). */
    static final long START = System.nanoTime();

    private Scenes() {}

    /** Nanoseconds since {@link #START}. */
    static long elapsed() {
        return System.nanoTime() - START;
    }

    /**
     * One frame of every scene. The order is part of the behaviour (two scenes due in the same
     * frame act in this order).
     */
    public static void onFrame() {
        DiagWorldScenes.keepDebugOffFrame();
        DiagScreenScenes.soundOptionsFrame();
        DiagSingleplayerScene.frame();
        DiagWorldScenes.thirdPersonFrame();
        DiagClickScenes.pickTestFrame();
        DiagClickScenes.serverClicksFrame();
        DiagScreenScenes.serverListFrame();
        DiagScreenScenes.soundReloadFrame();
        DiagWorldScenes.graphicsFrame();
        DiagScreenScenes.clicksFrame();
        DiagScreenScenes.loaderMenuFrame();
        DiagInputScenes.wheelFrame();
        DiagInputScenes.inputProbeFrame();
        DiagScreenScenes.minimizeFrame();
        DiagInputScenes.attackFrame();
        DiagInputScenes.keysFrame();
        DiagChatScenes.frame();
        DiagScreenScenes.actionBarFrame();
        DiagScreenScenes.bossFrame();
        DiagCapture.frame();
        DiagInputScenes.holdKeyFrame();
        DiagClickScenes.clickTestFrame();
        DiagClickScenes.cpsTestFrame();
        DiagInputScenes.dragFrame();
        DiagInputScenes.typeFrame();
        DiagWorldScenes.citFrame();
        DiagScreenScenes.videoFrame();
        DiagWorldScenes.reloadChunksFrame();
        DiagWorldScenes.gearFrame();
        DiagScreenScenes.controlsFrame();
        DiagWorldScenes.nbtFrame();
        DiagScreenScenes.pauseFrame();
        DiagScreenScenes.inventoryFrame();
    }
}
