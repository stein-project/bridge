/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.hooks;

import java.util.List;

/**
 * Whoever receives the game's events.
 *
 * <p>Internal to SteinBridge: the Bridge only knows there may be listeners, registered through
 * ServiceLoader (META-INF/services/dev.xavier.stein.bridge.hooks.HookSink). There are two today —
 * the Bridge's own visual options ({@code dev.xavier.stein.bridge.lens.Lens}) and the Stein Loader,
 * which relays to the mods through its API ({@code dev.xavier.stein.loader.api}). Each hook has a
 * default (nothing, or the game's value): a listener only writes the ones it wants. With more than
 * one, {@link Sinks} delivers to all of them, in classpath order.
 *
 * <p>Game types arrive as Object (the game's class names are obfuscated). Everything runs on the
 * game thread.
 */
public interface HookSink {

    /** Before Minecraft.startGame: the game exists, nothing has been loaded. */
    default void beforeStartGame() {}

    /** After startGame: window, resources and options are ready. */
    default void afterStartGame() {}

    /** End of each client tick. */
    default void tickEnd() {}

    /** After each keyboard event handled by the game. */
    default void keyInput() {}

    /** After each mouse event handled by the game. */
    default void mouseInput() {}

    /** After the HUD. */
    default void overlay(float partialTicks) {}

    /** Before the open screen draws itself. */
    default void beforeDrawScreen(Object screen, int mouseX, int mouseY, float partialTicks) {}

    /** After the open screen draws itself. */
    default void afterDrawScreen(Object screen, int mouseX, int mouseY, float partialTicks) {}

    /**
     * Mouse event on an open screen, before it.
     *
     * @return true so the screen does not receive the event
     */
    default boolean guiMouseInput(Object screen) {
        return false;
    }

    /**
     * Chat message from the server, before the chat.
     *
     * @return the message (the same or another), or null to discard it
     */
    default Object chat(Object message) {
        return message;
    }

    /** Joining a server or world completed. */
    default void joinGame() {}

    /** Right side of F3: lines to add after SteinBridge's. */
    default void debugInfoRight(List<String> lines) {}

    /** Bottom-left corner of the main menu: lines after "Minecraft 1.8.9" and SteinBridge's. */
    default void mainMenuBranding(List<String> lines) {}

    /** Screen built (after initGui): buttons and its button list, to add your own. */
    default void guiInit(Object screen, List<Object> buttons) {}

    /**
     * Button clicked on a screen, before its actionPerformed.
     *
     * @return true so the screen does not receive the click
     */
    default boolean guiAction(Object screen, Object button) {
        return false;
    }

    /**
     * Field of view of the frame, after the EntityRenderer.getFOVModifier computations.
     *
     * @return the field of view to use
     */
    default float fov(float fov, float partialTicks, boolean useFovSetting) {
        return fov;
    }

    /**
     * The player's FOV factor (speed, sprint, bow), before EntityRenderer's smoothing.
     *
     * @return the factor to use
     */
    default float fovModifier(float modifier) {
        return modifier;
    }

    /** Before the block and item atlas (TextureMap) is built, on every resource reload. */
    default void textureStitch(Object textureMap) {}

    /** Models and atlas ready, on every resource reload. */
    default void modelsReloaded() {}

    /**
     * Model of a drawn item.
     *
     * @return the model to use (IBakedModel)
     */
    default Object itemModel(Object stack, Object model) {
        return model;
    }

    /**
     * Texture of a worn armor piece: layer 1 (2 for leggings), overlay null or the layer name
     * ("overlay").
     *
     * @return the ResourceLocation to use
     */
    default Object armorTexture(Object stack, int layer, String overlay, Object location) {
        return location;
    }

    /**
     * Model of a marked block (GameHooks.watchBlockModel) assembled in the chunk, on the assembly
     * threads — several at the same time.
     *
     * @return the model to use (IBakedModel)
     */
    default Object blockModel(Object world, Object state, Object pos, Object model) {
        return model;
    }

    /**
     * The player's hit on an entity by click, before the server.
     *
     * @return true to cancel
     */
    default boolean attackEntity(Object target) {
        return false;
    }

    /**
     * Right click on an entity (the two interactions of rightClickMouse), before the server.
     *
     * @return true to cancel
     */
    default boolean interactEntity(Object target) {
        return false;
    }

    /**
     * PlayerControllerMP.windowClick, before the server: any click on a slot.
     *
     * @return true to cancel
     */
    default boolean windowClick(int windowId, int slot, int button, int mode) {
        return false;
    }

    /**
     * A slot changed from the server (S2F), already applied; window -1 and slot -1 = the item on
     * the cursor.
     */
    default void slotChanged(int windowId, int slot, Object stack) {}

    /** The server sent all the items of a window (S30), already applied. */
    default void windowItems(int windowId) {}

    /**
     * An entity took damage (S19, status 2), before the effect on the client.
     *
     * @return true to cancel the effect
     */
    default boolean entityHurt(Object entity) {
        return false;
    }

    /** An entity died (S19, status 3), before the animation. */
    default void entityDeath(Object entity) {}

    /**
     * The health or absorption of a living entity changed (S1C for others, S06 for the player),
     * already applied.
     */
    default void healthChanged(
            Object entity,
            float oldHealth,
            float newHealth,
            float oldAbsorption,
            float newAbsorption) {}

    /**
     * Name of a player in the Tab list (NetworkPlayerInfo).
     *
     * @return the name to show
     */
    default String playerListName(Object playerInfo, String name) {
        return name;
    }

    /**
     * Should this entity not be drawn? Called in place of RenderGlobal.renderEntities'
     * renderEntityStatic (and in our own passes, such as the entity shadow): true means the whole
     * draw does not happen — body, armor, name and shadow. It is what a mod uses to hide players
     * (its own clan, for example).
     */
    default boolean hideEntity(Object entity) {
        return false;
    }

    /**
     * Should this entity be drawn as a <b>ghost</b>? It is the same drawing the game already does
     * for the invisible and for your team (15% opacity, without writing depth), except that armor
     * and the held item are also left out — in the game they stay solid, and that is why an
     * invisible player in armor becomes "walking armor".
     *
     * <p>Whoever is hidden ({@link #hideEntity}) does not get here: vanishing beats becoming a
     * ghost.
     */
    default boolean ghostEntity(Object entity) {
        return false;
    }

    /**
     * The Tab list, in place of the game's (GuiIngame.renderGameOverlay).
     *
     * @return true so the game does not draw its own
     */
    default boolean playerList(Object overlay, int width, Object scoreboard, Object objective) {
        return false;
    }

    /**
     * Name above the head of a living entity (IChatComponent).
     *
     * @return the name to show
     */
    default Object nametag(Object entity, Object name) {
        return name;
    }

    /**
     * End of the drawn world, before the upper clouds and the hand, with the camera matrix
     * (RenderWorldLastEvent).
     */
    default void renderWorld(float partialTicks) {}

    /**
     * Server plugin packet (S3FPacketCustomPayload), on the game thread: the channel is a copy of
     * the bytes.
     */
    default void customPayload(String channel, byte[] data) {}

    /**
     * What the player sends (chat or command), before it goes out.
     *
     * @return the message (the same or another), or null to not send it
     */
    default String sendChat(String message) {
        return message;
    }

    /**
     * A sound about to play (ISound).
     *
     * @return the sound (the same or another), or null to not play it
     */
    default Object playSound(Object sound) {
        return sound;
    }
}
