/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.bridge.hooks;

import dev.xavier.stein.bridge.core.Log;

import java.util.List;

/**
 * Several listeners as one: the Bridge has its own (the visual options, {@code
 * dev.xavier.stein.bridge.lens.Lens}) and the Stein Loader registers its own when installed.
 *
 * <p>Each one receives the event in classpath order. In the hooks that return a value, one's output
 * is the next one's input (the last value is what the game uses); in the ones that cancel, the
 * first to cancel ends it — the following ones do not even see the event, as would happen with the
 * game's screen. An error in one listener does not take the event away from the others: it goes to
 * the log, up to twenty times per listener.
 */
final class Sinks implements HookSink {

    /** Failures printed per listener (and by {@code GameHooks}); the rest are silent. */
    static final int MAX_REPORTED_FAILURES = 20;

    private final HookSink[] sinks;
    private final int[] failures;

    Sinks(List<HookSink> sinks) {
        this.sinks = sinks.toArray(new HookSink[0]);
        this.failures = new int[this.sinks.length];
    }

    // Throwable on purpose: a listener's Error must not take the game down either.
    private void fail(int i, String event, Throwable e) {
        if (failures[i]++ < MAX_REPORTED_FAILURES) {
            Log.error("hooks", "error in " + sinks[i].getClass().getName() + "." + event, e);
        }
    }

    @Override
    public void beforeStartGame() {
        for (int i = 0; i < sinks.length; i++) {
            try {
                sinks[i].beforeStartGame();
            } catch (Throwable e) {
                fail(i, "beforeStartGame", e);
            }
        }
    }

    @Override
    public void afterStartGame() {
        for (int i = 0; i < sinks.length; i++) {
            try {
                sinks[i].afterStartGame();
            } catch (Throwable e) {
                fail(i, "afterStartGame", e);
            }
        }
    }

    @Override
    public void tickEnd() {
        for (int i = 0; i < sinks.length; i++) {
            try {
                sinks[i].tickEnd();
            } catch (Throwable e) {
                fail(i, "tickEnd", e);
            }
        }
    }

    @Override
    public void keyInput() {
        for (int i = 0; i < sinks.length; i++) {
            try {
                sinks[i].keyInput();
            } catch (Throwable e) {
                fail(i, "keyInput", e);
            }
        }
    }

    @Override
    public void mouseInput() {
        for (int i = 0; i < sinks.length; i++) {
            try {
                sinks[i].mouseInput();
            } catch (Throwable e) {
                fail(i, "mouseInput", e);
            }
        }
    }

    @Override
    public void overlay(float partialTicks) {
        for (int i = 0; i < sinks.length; i++) {
            try {
                sinks[i].overlay(partialTicks);
            } catch (Throwable e) {
                fail(i, "overlay", e);
            }
        }
    }

    @Override
    public void beforeDrawScreen(Object screen, int mouseX, int mouseY, float partialTicks) {
        for (int i = 0; i < sinks.length; i++) {
            try {
                sinks[i].beforeDrawScreen(screen, mouseX, mouseY, partialTicks);
            } catch (Throwable e) {
                fail(i, "beforeDrawScreen", e);
            }
        }
    }

    @Override
    public void afterDrawScreen(Object screen, int mouseX, int mouseY, float partialTicks) {
        for (int i = 0; i < sinks.length; i++) {
            try {
                sinks[i].afterDrawScreen(screen, mouseX, mouseY, partialTicks);
            } catch (Throwable e) {
                fail(i, "afterDrawScreen", e);
            }
        }
    }

    @Override
    public boolean guiMouseInput(Object screen) {
        for (int i = 0; i < sinks.length; i++) {
            try {
                if (sinks[i].guiMouseInput(screen)) {
                    return true;
                }
            } catch (Throwable e) {
                fail(i, "guiMouseInput", e);
            }
        }
        return false;
    }

    @Override
    public Object chat(Object message) {
        for (int i = 0; i < sinks.length && message != null; i++) {
            try {
                message = sinks[i].chat(message);
            } catch (Throwable e) {
                fail(i, "chat", e);
            }
        }
        return message;
    }

    @Override
    public void joinGame() {
        for (int i = 0; i < sinks.length; i++) {
            try {
                sinks[i].joinGame();
            } catch (Throwable e) {
                fail(i, "joinGame", e);
            }
        }
    }

    @Override
    public void debugInfoRight(List<String> lines) {
        for (int i = 0; i < sinks.length; i++) {
            try {
                sinks[i].debugInfoRight(lines);
            } catch (Throwable e) {
                fail(i, "debugInfoRight", e);
            }
        }
    }

    @Override
    public void mainMenuBranding(List<String> lines) {
        for (int i = 0; i < sinks.length; i++) {
            try {
                sinks[i].mainMenuBranding(lines);
            } catch (Throwable e) {
                fail(i, "mainMenuBranding", e);
            }
        }
    }

    @Override
    public void guiInit(Object screen, List<Object> buttons) {
        for (int i = 0; i < sinks.length; i++) {
            try {
                sinks[i].guiInit(screen, buttons);
            } catch (Throwable e) {
                fail(i, "guiInit", e);
            }
        }
    }

    @Override
    public boolean guiAction(Object screen, Object button) {
        for (int i = 0; i < sinks.length; i++) {
            try {
                if (sinks[i].guiAction(screen, button)) {
                    return true;
                }
            } catch (Throwable e) {
                fail(i, "guiAction", e);
            }
        }
        return false;
    }

    @Override
    public float fov(float fov, float partialTicks, boolean useFovSetting) {
        for (int i = 0; i < sinks.length; i++) {
            try {
                fov = sinks[i].fov(fov, partialTicks, useFovSetting);
            } catch (Throwable e) {
                fail(i, "fov", e);
            }
        }
        return fov;
    }

    @Override
    public float fovModifier(float modifier) {
        for (int i = 0; i < sinks.length; i++) {
            try {
                modifier = sinks[i].fovModifier(modifier);
            } catch (Throwable e) {
                fail(i, "fovModifier", e);
            }
        }
        return modifier;
    }

    @Override
    public void textureStitch(Object textureMap) {
        for (int i = 0; i < sinks.length; i++) {
            try {
                sinks[i].textureStitch(textureMap);
            } catch (Throwable e) {
                fail(i, "textureStitch", e);
            }
        }
    }

    @Override
    public void modelsReloaded() {
        for (int i = 0; i < sinks.length; i++) {
            try {
                sinks[i].modelsReloaded();
            } catch (Throwable e) {
                fail(i, "modelsReloaded", e);
            }
        }
    }

    @Override
    public Object itemModel(Object stack, Object model) {
        for (int i = 0; i < sinks.length; i++) {
            try {
                model = sinks[i].itemModel(stack, model);
            } catch (Throwable e) {
                fail(i, "itemModel", e);
            }
        }
        return model;
    }

    @Override
    public Object armorTexture(Object stack, int layer, String overlay, Object location) {
        for (int i = 0; i < sinks.length; i++) {
            try {
                location = sinks[i].armorTexture(stack, layer, overlay, location);
            } catch (Throwable e) {
                fail(i, "armorTexture", e);
            }
        }
        return location;
    }

    /**
     * On the chunk building threads: several at once, with no state here besides the error counter.
     */
    @Override
    public Object blockModel(Object world, Object state, Object pos, Object model) {
        for (int i = 0; i < sinks.length; i++) {
            try {
                model = sinks[i].blockModel(world, state, pos, model);
            } catch (Throwable e) {
                fail(i, "blockModel", e);
            }
        }
        return model;
    }

    @Override
    public boolean attackEntity(Object target) {
        for (int i = 0; i < sinks.length; i++) {
            try {
                if (sinks[i].attackEntity(target)) {
                    return true;
                }
            } catch (Throwable e) {
                fail(i, "attackEntity", e);
            }
        }
        return false;
    }

    @Override
    public boolean interactEntity(Object target) {
        for (int i = 0; i < sinks.length; i++) {
            try {
                if (sinks[i].interactEntity(target)) {
                    return true;
                }
            } catch (Throwable e) {
                fail(i, "interactEntity", e);
            }
        }
        return false;
    }

    @Override
    public boolean windowClick(int windowId, int slot, int button, int mode) {
        for (int i = 0; i < sinks.length; i++) {
            try {
                if (sinks[i].windowClick(windowId, slot, button, mode)) {
                    return true;
                }
            } catch (Throwable e) {
                fail(i, "windowClick", e);
            }
        }
        return false;
    }

    @Override
    public void slotChanged(int windowId, int slot, Object stack) {
        for (int i = 0; i < sinks.length; i++) {
            try {
                sinks[i].slotChanged(windowId, slot, stack);
            } catch (Throwable e) {
                fail(i, "slotChanged", e);
            }
        }
    }

    @Override
    public void windowItems(int windowId) {
        for (int i = 0; i < sinks.length; i++) {
            try {
                sinks[i].windowItems(windowId);
            } catch (Throwable e) {
                fail(i, "windowItems", e);
            }
        }
    }

    @Override
    public boolean entityHurt(Object entity) {
        for (int i = 0; i < sinks.length; i++) {
            try {
                if (sinks[i].entityHurt(entity)) {
                    return true;
                }
            } catch (Throwable e) {
                fail(i, "entityHurt", e);
            }
        }
        return false;
    }

    @Override
    public void entityDeath(Object entity) {
        for (int i = 0; i < sinks.length; i++) {
            try {
                sinks[i].entityDeath(entity);
            } catch (Throwable e) {
                fail(i, "entityDeath", e);
            }
        }
    }

    @Override
    public void healthChanged(
            Object entity,
            float oldHealth,
            float newHealth,
            float oldAbsorption,
            float newAbsorption) {
        for (int i = 0; i < sinks.length; i++) {
            try {
                sinks[i].healthChanged(entity, oldHealth, newHealth, oldAbsorption, newAbsorption);
            } catch (Throwable e) {
                fail(i, "healthChanged", e);
            }
        }
    }

    @Override
    public boolean hideEntity(Object entity) {
        for (int i = 0; i < sinks.length; i++) {
            try {
                if (sinks[i].hideEntity(entity)) {
                    return true;
                }
            } catch (Throwable e) {
                fail(i, "hideEntity", e);
            }
        }
        return false;
    }

    @Override
    public boolean ghostEntity(Object entity) {
        for (int i = 0; i < sinks.length; i++) {
            try {
                if (sinks[i].ghostEntity(entity)) {
                    return true;
                }
            } catch (Throwable e) {
                fail(i, "ghostEntity", e);
            }
        }
        return false;
    }

    @Override
    public boolean playerList(Object overlay, int width, Object scoreboard, Object objective) {
        for (int i = 0; i < sinks.length; i++) {
            try {
                if (sinks[i].playerList(overlay, width, scoreboard, objective)) {
                    return true;
                }
            } catch (Throwable e) {
                fail(i, "playerList", e);
            }
        }
        return false;
    }

    @Override
    public String playerListName(Object playerInfo, String name) {
        for (int i = 0; i < sinks.length; i++) {
            try {
                name = sinks[i].playerListName(playerInfo, name);
            } catch (Throwable e) {
                fail(i, "playerListName", e);
            }
        }
        return name;
    }

    @Override
    public Object nametag(Object entity, Object name) {
        for (int i = 0; i < sinks.length; i++) {
            try {
                name = sinks[i].nametag(entity, name);
            } catch (Throwable e) {
                fail(i, "nametag", e);
            }
        }
        return name;
    }

    @Override
    public void renderWorld(float partialTicks) {
        for (int i = 0; i < sinks.length; i++) {
            try {
                sinks[i].renderWorld(partialTicks);
            } catch (Throwable e) {
                fail(i, "renderWorld", e);
            }
        }
    }

    @Override
    public void customPayload(String channel, byte[] data) {
        for (int i = 0; i < sinks.length; i++) {
            try {
                sinks[i].customPayload(channel, data);
            } catch (Throwable e) {
                fail(i, "customPayload", e);
            }
        }
    }

    @Override
    public String sendChat(String message) {
        for (int i = 0; i < sinks.length && message != null; i++) {
            try {
                message = sinks[i].sendChat(message);
            } catch (Throwable e) {
                fail(i, "sendChat", e);
            }
        }
        return message;
    }

    @Override
    public Object playSound(Object sound) {
        for (int i = 0; i < sinks.length && sound != null; i++) {
            try {
                sound = sinks[i].playSound(sound);
            } catch (Throwable e) {
                fail(i, "playSound", e);
            }
        }
        return sound;
    }
}
