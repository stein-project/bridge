/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.hooks;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.engine.FrameTime;
import dev.xavier.stein.gfx.SteinGfxSelfTest;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Fixed points in the game through which the events pass — the same ones where Forge 1.8.9 fires
 * the equivalents (patcher/redirects-mods.tsv).
 *
 * <p>Each hook hands the event to a {@link HookSink}, if there is one on the classpath (the Stein
 * Loader). Without it, each hook goes straight to what the game would do: nothing changes.
 *
 * <p>Two bridge services that the Loader API exposes also live here: removing the effects list from
 * the inventory and the fraction of the current tick.
 *
 * <p>This class is the stable face the patcher and the Loader call: the method names and
 * descriptors are frozen. The lifecycle and input hooks are implemented here; the rest delegate to
 * {@link GameHooksHud} (screens and HUD), {@link GameHooksRender} (camera, atlas, models), {@link
 * GameHooksNet} (player actions, packets) and {@link GameHooksEntity} (hidden and ghost entities,
 * health), each holding the obfuscated handles of its area.
 */
public final class GameHooks {

    static final HookSink SINK = load();

    static {
        // The obfuscated handles of every area are resolved here, as one static block once did.
        GameHooksHud.init();
        GameHooksRender.init();
        GameHooksNet.init();
        GameHooksEntity.init();
    }

    /** -Dsteinbridge.hooks.debug=true: logs every key the game handles. */
    static final boolean DEBUG = HookOptions.DEBUG;

    private static boolean keyEvent;
    private static boolean mouseEvent;
    private static int failures;

    private GameHooks() {}

    /**
     * All the listeners on the classpath (ServiceLoader): the bridge's own, with the visual
     * options, and the Stein Loader's when it is installed. One that fails to load does not take
     * the others down with it.
     */
    private static HookSink load() {
        List<HookSink> found = new ArrayList<>();
        try {
            Iterator<HookSink> it = ServiceLoader.load(HookSink.class).iterator();
            while (it.hasNext()) {
                try {
                    HookSink s = it.next();
                    Log.info("hooks", "game events to " + s.getClass().getName());
                    found.add(s);
                } catch (ServiceConfigurationError | LinkageError e) {
                    Log.error("hooks", "a listener failed to load, the game goes on without it", e);
                }
            }
        } catch (ServiceConfigurationError | LinkageError e) {
            Log.error("hooks", "listener failed to load, the game goes on without it", e);
        }
        return found.isEmpty() ? null : found.size() == 1 ? found.get(0) : new Sinks(found);
    }

    static void fail(String event, Throwable e) {
        if (failures++ < Sinks.MAX_REPORTED_FAILURES) {
            Log.error("hooks", "listener error in " + event, e);
        }
    }

    // ---- hooks (patcher/redirects-mods.tsv) ----

    /** Before startGame (ave.am), in Minecraft.run. */
    public static void beforeStartGame() {
        if (SINK != null) {
            try {
                SINK.beforeStartGame();
            } catch (Throwable e) {
                fail("beforeStartGame", e);
            }
        }
    }

    /** After startGame. */
    public static void afterStartGame() {
        SteinGfxSelfTest.runIfRequested();
        if (SINK != null) {
            try {
                SINK.afterStartGame();
            } catch (Throwable e) {
                fail("afterStartGame", e);
            }
        }
    }

    /** After each runTick (ave.s), in runGameLoop. */
    public static void tickEnd() {
        FrameTime.ticked();
        if (SINK != null) {
            try {
                SINK.tickEnd();
            } catch (Throwable e) {
                fail("tickEnd", e);
            }
        }
    }

    /**
     * In place of Keyboard.next() in runTick's keyboard loop. The loop calls next() at the end of
     * each handled event: that is where Forge fires KeyInputEvent, so the previous event is
     * announced before fetching the next one.
     */
    public static boolean keyboardNext() {
        if (keyEvent) {
            keyEvent = false;
            try {
                SINK.keyInput();
            } catch (Throwable e) {
                fail("keyInput", e);
            }
        }
        boolean next = Keyboard.next();
        keyEvent = next && SINK != null;
        if (DEBUG && next) {
            Log.info(
                    "hooks",
                    "key "
                            + Keyboard.getEventKey()
                            + (Keyboard.getEventKeyState() ? " down" : " up"));
        }
        return next;
    }

    /** Likewise for runTick's mouse loop (MouseInputEvent). */
    public static boolean mouseNext() {
        if (mouseEvent) {
            mouseEvent = false;
            try {
                SINK.mouseInput();
            } catch (Throwable e) {
                fail("mouseInput", e);
            }
        }
        boolean next = Mouse.next();
        mouseEvent = next && SINK != null;
        if (DEBUG && next && Mouse.getEventButton() >= 0) {
            Log.info(
                    "hooks",
                    "mouse "
                            + Mouse.getEventButton()
                            + (Mouse.getEventButtonState() ? " down" : " up")
                            + " (game tick)");
        }
        return next;
    }

    /**
     * The whole HUD disappears (the game's and the mods'), even with a screen open — the game draws
     * the HUD underneath every screen, even with F1. It is what a replay uses to keep the scene
     * clean. Turned on by the Loader (Hud.setHidden).
     */
    public static volatile boolean hideHud;

    /** See {@link GameHooksHud#gameOverlay}. */
    public static void gameOverlay(Object gui, float partialTicks) throws Throwable {
        GameHooksHud.gameOverlay(gui, partialTicks);
    }

    /** See {@link GameHooksHud#overlay}. */
    public static void overlay() {
        GameHooksHud.overlay();
    }

    /** See {@link GameHooksHud#drawScreen}. */
    public static void drawScreen(Object screen, int mouseX, int mouseY, float partialTicks)
            throws Throwable {
        GameHooksHud.drawScreen(screen, mouseX, mouseY, partialTicks);
    }

    /** See {@link GameHooksHud#handleMouseInput}. */
    public static void handleMouseInput(Object screen) throws Throwable {
        GameHooksHud.handleMouseInput(screen);
    }

    /** See {@link GameHooksHud#printChat}. */
    public static void printChat(Object chatGui, Object message) throws Throwable {
        GameHooksHud.printChat(chatGui, message);
    }

    /** After handleJoinGame (S01PacketJoinGame.processPacket), on the game thread. */
    public static void joinGame() {
        if (SINK != null) {
            try {
                SINK.joinGame();
            } catch (Throwable e) {
                fail("joinGame", e);
            }
        }
    }

    /** See {@link GameHooksHud#debugInfoRight}. */
    public static Object debugInfoRight(Object overlay) throws Throwable {
        return GameHooksHud.debugInfoRight(overlay);
    }

    /** See {@link GameHooksHud#mainMenuString}. */
    public static void mainMenuString(Object gui, Object font, Object text, int x, int y, int color)
            throws Throwable {
        GameHooksHud.mainMenuString(gui, font, text, x, y, color);
    }

    /** See {@link GameHooksHud#initGui}. */
    public static void initGui(Object screen) throws Throwable {
        GameHooksHud.initGui(screen);
    }

    /** See {@link GameHooksHud#actionPerformed}. */
    public static void actionPerformed(Object screen, Object button) throws Throwable {
        GameHooksHud.actionPerformed(screen, button);
    }

    /** See {@link GameHooksRender#fov}. */
    public static float fov(Object renderer, float partialTicks, boolean useFovSetting)
            throws Throwable {
        return GameHooksRender.fov(renderer, partialTicks, useFovSetting);
    }

    /** See {@link GameHooksRender#fovModifier}. */
    public static float fovModifier(Object player) throws Throwable {
        return GameHooksRender.fovModifier(player);
    }

    /** See {@link GameHooksRender#loadTextureAtlas}. */
    public static void loadTextureAtlas(Object map, Object resourceManager) throws Throwable {
        GameHooksRender.loadTextureAtlas(map, resourceManager);
    }

    /** See {@link GameHooksRender#spriteLocation}. */
    public static Object spriteLocation(Object map, Object location, int mip) throws Throwable {
        return GameHooksRender.spriteLocation(map, location, mip);
    }

    /** See {@link GameHooksRender#modelsReloaded}. */
    public static void modelsReloaded() {
        GameHooksRender.modelsReloaded();
    }

    /** See {@link GameHooksRender#renderWorld}. */
    public static void renderWorld() {
        GameHooksRender.renderWorld();
    }

    /** See {@link GameHooksNet#customPayload}. */
    public static void customPayload(Object packet, Object handler, Object listener)
            throws Throwable {
        GameHooksNet.customPayload(packet, handler, listener);
    }

    /** See {@link GameHooksNet#sendChat}. */
    public static void sendChat(Object player, Object message) throws Throwable {
        GameHooksNet.sendChat(player, message);
    }

    /**
     * Whoever can keep a chest's screen instead of it being shown (the Loader: the virtual
     * container).
     */
    public interface ChestScreenFilter {
        /** true: the screen was handled (do not show). */
        boolean consume(Object screen);
    }

    public static volatile ChestScreenFilter chestScreen;

    /** See {@link GameHooksNet#openChestScreen}. */
    public static void openChestScreen(Object mc, Object screen) throws Throwable {
        GameHooksNet.openChestScreen(mc, screen);
    }

    /** See {@link GameHooksNet#playSound}. */
    public static void playSound(Object manager, Object sound) throws Throwable {
        GameHooksNet.playSound(manager, sound);
    }

    /** See {@link GameHooksRender#spriteAt}. */
    public static Object spriteAt(float u, float v) {
        return GameHooksRender.spriteAt(u, v);
    }

    /** See {@link GameHooksRender#watchBlockModel}. */
    public static void watchBlockModel(Object block, boolean watched) {
        GameHooksRender.watchBlockModel(block, watched);
    }

    /** See {@link GameHooksRender#blockModel}. */
    public static boolean blockModel(
            Object renderer,
            Object world,
            Object model,
            Object state,
            Object pos,
            Object worldRenderer)
            throws Throwable {
        return GameHooksRender.blockModel(renderer, world, model, state, pos, worldRenderer);
    }

    /** See {@link GameHooksRender#itemModel}. */
    public static Object itemModel(Object mesher, Object stack) throws Throwable {
        return GameHooksRender.itemModel(mesher, stack);
    }

    /** See {@link GameHooksRender#armorStack}. */
    public static Object armorStack(Object layer, Object entity, int slot) throws Throwable {
        return GameHooksRender.armorStack(layer, entity, slot);
    }

    /** See {@link GameHooksRender#armorTexture}. */
    public static Object armorTexture(Object layer, Object item, boolean legs) throws Throwable {
        return GameHooksRender.armorTexture(layer, item, legs);
    }

    /** See {@link GameHooksRender#armorTextureOverlay}. */
    public static Object armorTextureOverlay(
            Object layer, Object item, boolean legs, Object overlay) throws Throwable {
        return GameHooksRender.armorTextureOverlay(layer, item, legs, overlay);
    }

    /** See {@link GameHooksRender#itemData}. */
    public static Object itemData(Object stack) {
        return GameHooksRender.itemData(stack);
    }

    /** See {@link GameHooksRender#setItemData}. */
    public static void setItemData(Object stack, Object value) {
        GameHooksRender.setItemData(stack, value);
    }

    /** See {@link GameHooksRender#resetCameraSmoothing}. */
    public static void resetCameraSmoothing() {
        GameHooksRender.resetCameraSmoothing();
    }

    /** See {@link GameHooksHud#setInventoryEffectsHidden}. */
    public static void setInventoryEffectsHidden(boolean hide) {
        GameHooksHud.setInventoryEffectsHidden(hide);
    }

    /** See {@link GameHooksHud#inventoryEffects}. */
    public static Object inventoryEffects(Object player) throws Throwable {
        return GameHooksHud.inventoryEffects(player);
    }

    /** See {@link GameHooksRender#partialTicks}. */
    public static float partialTicks() {
        return GameHooksRender.partialTicks();
    }

    /** See {@link GameHooksNet#setMissPenalty}. */
    public static void setMissPenalty(boolean on) {
        GameHooksNet.setMissPenalty(on);
    }

    /** See {@link GameHooksNet#missedClicks}. */
    public static int missedClicks() {
        return GameHooksNet.missedClicks();
    }

    /** See {@link GameHooksNet#armMissPenalty}. */
    public static boolean armMissPenalty(Object controller) throws Throwable {
        return GameHooksNet.armMissPenalty(controller);
    }

    /** See {@link GameHooksNet#attackEntity}. */
    public static void attackEntity(Object controller, Object player, Object target)
            throws Throwable {
        GameHooksNet.attackEntity(controller, player, target);
    }

    /** See {@link GameHooksNet#rightClickEntityAt}. */
    public static boolean rightClickEntityAt(
            Object controller, Object player, Object target, Object hit) throws Throwable {
        return GameHooksNet.rightClickEntityAt(controller, player, target, hit);
    }

    /** See {@link GameHooksNet#interactEntity}. */
    public static boolean interactEntity(Object controller, Object player, Object target)
            throws Throwable {
        return GameHooksNet.interactEntity(controller, player, target);
    }

    /** See {@link GameHooksNet#windowClick}. */
    public static Object windowClick(
            Object controller, int windowId, int slot, int button, int mode, Object player)
            throws Throwable {
        return GameHooksNet.windowClick(controller, windowId, slot, button, mode, player);
    }

    /**
     * See {@link GameHooksNet#setSession}. It never throws ReflectiveOperationException: the clause
     * stays only so callers written when the swap was reflective (the Loader's Accounts catches it)
     * keep compiling. A link failure surfaces as a LinkageError (BootstrapMethodError).
     */
    public static void setSession(Object session) throws ReflectiveOperationException {
        GameHooksNet.setSession(session);
    }

    /** See {@link GameHooksHud#hoveredSlot}. */
    public static int hoveredSlot(Object screen) {
        return GameHooksHud.hoveredSlot(screen);
    }

    /** See {@link GameHooksNet#setSlot}. */
    public static void setSlot(Object container, int slot, Object stack) throws Throwable {
        GameHooksNet.setSlot(container, slot, stack);
    }

    /** See {@link GameHooksNet#setCursor}. */
    public static void setCursor(Object inventory, Object stack) throws Throwable {
        GameHooksNet.setCursor(inventory, stack);
    }

    /** See {@link GameHooksEntity#entityStatus}. */
    public static void entityStatus(Object entity, byte status) throws Throwable {
        GameHooksEntity.entityStatus(entity, status);
    }

    /** See {@link GameHooksEntity#entityMetadata}. */
    public static void entityMetadata(Object watcher, Object list) throws Throwable {
        GameHooksEntity.entityMetadata(watcher, list);
    }

    /** See {@link GameHooksEntity#playerHealth}. */
    public static void playerHealth(Object player, float health) throws Throwable {
        GameHooksEntity.playerHealth(player, health);
    }

    /** See {@link GameHooksNet#setWindowItems}. */
    public static void setWindowItems(Object container, Object stacks) throws Throwable {
        GameHooksNet.setWindowItems(container, stacks);
    }

    /** See {@link GameHooksHud#playerList}. */
    public static void playerList(Object overlay, int width, Object scoreboard, Object objective)
            throws Throwable {
        GameHooksHud.playerList(overlay, width, scoreboard, objective);
    }

    /** See {@link GameHooksEntity#renderEntity}. */
    public static boolean renderEntity(Object manager, Object entity, float partialTicks)
            throws Throwable {
        return GameHooksEntity.renderEntity(manager, entity, partialTicks);
    }

    /** See {@link GameHooksEntity#renderMultipass}. */
    public static void renderMultipass(Object manager, Object entity, float partialTicks)
            throws Throwable {
        GameHooksEntity.renderMultipass(manager, entity, partialTicks);
    }

    /** See {@link GameHooksEntity#ghostInvisible}. */
    public static boolean ghostInvisible(Object entity) throws Throwable {
        return GameHooksEntity.ghostInvisible(entity);
    }

    /** See {@link GameHooksEntity#ghostInvisibleToPlayer}. */
    public static boolean ghostInvisibleToPlayer(Object entity, Object player) throws Throwable {
        return GameHooksEntity.ghostInvisibleToPlayer(entity, player);
    }

    /** See {@link GameHooksEntity#ghostLayers}. */
    public static void ghostLayers(
            Object renderer,
            Object entity,
            float limbSwing,
            float limbSwingAmount,
            float partialTicks,
            float ageInTicks,
            float netHeadYaw,
            float headPitch,
            float scale)
            throws Throwable {
        GameHooksEntity.ghostLayers(
                renderer,
                entity,
                limbSwing,
                limbSwingAmount,
                partialTicks,
                ageInTicks,
                netHeadYaw,
                headPitch,
                scale);
    }

    /** See {@link GameHooksEntity#ghostFire}. */
    public static boolean ghostFire(Object entity) throws Throwable {
        return GameHooksEntity.ghostFire(entity);
    }

    /** See {@link GameHooksEntity#ghostPotionColor}. */
    public static int ghostPotionColor(Object watcher, int id) throws Throwable {
        return GameHooksEntity.ghostPotionColor(watcher, id);
    }

    /** See {@link GameHooksEntity#ghostHurt}. */
    public static boolean ghostHurt(Object renderer, Object entity, float partialTicks)
            throws Throwable {
        return GameHooksEntity.ghostHurt(renderer, entity, partialTicks);
    }

    /** See {@link GameHooksEntity#ghost}. */
    public static boolean ghost(Object entity) {
        return GameHooksEntity.ghost(entity);
    }

    /** See {@link GameHooksEntity#hidden}. */
    public static boolean hidden(Object entity) {
        return GameHooksEntity.hidden(entity);
    }

    /** See {@link GameHooksHud#playerListName}. */
    public static Object playerListName(Object overlay, Object info) throws Throwable {
        return GameHooksHud.playerListName(overlay, info);
    }

    /** See {@link GameHooksEntity#nametag}. */
    public static Object nametag(Object entity) throws Throwable {
        return GameHooksEntity.nametag(entity);
    }
}
