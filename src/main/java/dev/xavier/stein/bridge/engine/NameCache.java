/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Names and texts that the game rebuilds every frame, for every entity, with the same result.
 *
 * <p>With the crowd, the steady state allocates ~150 MB/s, and most of it comes from the nametag:
 *
 * <ul>
 *   <li><b>Name hover</b> ({@code Entity.getHoverEvent}, pk.aQ). The player and entity
 *       getDisplayName builds, on every call, an NBT with the UUID as text, type and name, its
 *       {@code toString} and a chat component — for the mouse-over event, which the nametag does
 *       not even read. It depends only on the entity's class (the type), the UUID and the name: one
 *       per entity, rebuilt if any of the three changes. The HoverEvent is immutable, and whoever
 *       reads it (the chat, on mouse-over) sees the same text.
 *   <li><b>Lowercase in the FontRenderer</b>. renderStringAtPos (avn.a(String, boolean)) does
 *       {@code text.toLowerCase(ENGLISH).charAt(i + 1)} for each formatting code: the whole text
 *       copied per color code, to read one character. String is immutable and the conversion is
 *       deterministic: a text -> lowercase map gives the same result without the copy.
 *   <li><b>"Dinnerbone"/"Grumm"</b>. rotateCorpse (bjl.a(pr, float, float, float)) passes the name
 *       of every living entity, every frame, through the regular expression of {@code
 *       getTextWithoutFormattingCodes} just to compare. Without a '§' there is nothing to strip,
 *       and Matcher.replaceAll returns the text itself — here too.
 * </ul>
 *
 * <p>{@code -Dsteinbridge.opt.vanillaNames=true}: all three through the original path.
 */
public final class NameCache {

    private static final boolean VANILLA = EngineOptions.VANILLA_NAMES;

    /** Distinct texts kept in lowercase before the map starts over. */
    private static final int MAX_LOWER = 2048;

    private static final class Hover {
        final Class<?> type;
        final UUID id;
        final String name;
        final Object event;

        Hover(Class<?> type, UUID id, String name, Object event) {
            this.type = type;
            this.id = id;
            this.name = name;
            this.event = event;
        }
    }

    /**
     * The hover kept on the entity itself (patcher field pk.sbHover): no hash, no equals by
     * entityId and no weak-reference cleanup on every lookup (~1.7% of the render thread in the
     * crowd). It goes away with the entity. The check is the class, UUID and name.
     */
    private static final VarHandle SLOT;

    private static final Map<String, String> LOWER = new HashMap<>();
    private static final int ID_SLOTS = 2048;
    private static final String[] ID_KEY = new String[ID_SLOTS];
    private static final String[] ID_VALUE = new String[ID_SLOTS];
    private static Thread renderThread;

    static {
        // The sbHover field is created by the patcher: not in the readable-name jar, so a
        // VarHandle.
        try {
            Class<?> entity = Entity.class;
            SLOT =
                    MethodHandles.privateLookupIn(entity, MethodHandles.lookup())
                            .findVarHandle(entity, "sbHover", Object.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
        if (VANILLA) {
            Log.info(
                    "names",
                    "hover, lower case and unformatted name rebuilt every frame (vanilla)");
        }
    }

    private NameCache() {}

    /** In place of getHoverEvent() inside getDisplayName (Entity and EntityPlayer). */
    public static Object hover(Object entity) {
        // Only compares: the render thread is the one lower() saw draw text (getDisplayName also
        // runs on other threads, which must not claim the cache).
        if (VANILLA || Thread.currentThread() != renderThread) {
            return ((Entity) entity).getHoverEvent();
        }
        UUID id = ((Entity) entity).getUniqueID();
        String name = ((Entity) entity).getName();
        VarHandle field = SLOT;
        // VarHandle with only Object in the descriptor: a call with a polymorphic signature keeps
        // the types it was compiled with, and Object names none of our classes.
        Hover h = (Hover) (Object) field.get(entity);
        if (h == null || h.type != entity.getClass() || !h.id.equals(id) || !h.name.equals(name)) {
            h = new Hover(entity.getClass(), id, name, ((Entity) entity).getHoverEvent());
            field.set(entity, (Object) h);
        }
        return h.event;
    }

    /** In place of String.toLowerCase(Locale) in the FontRenderer's renderStringAtPos. */
    public static Object lower(Object receiver, Object loc) {
        String text = (String) receiver;
        Locale locale = (Locale) loc;
        if (VANILLA || locale != Locale.ENGLISH || Thread.currentThread() != renderThread()) {
            return text.toLowerCase(locale);
        }
        // In front of the map, a cache by the String's identity: the same text (the same object)
        // comes back every frame for each name, and comparing by reference avoids the hash and the
        // equals.
        int slot = System.identityHashCode(text) & (ID_SLOTS - 1);
        if (ID_KEY[slot] == text) {
            return ID_VALUE[slot];
        }
        String low = LOWER.get(text);
        if (low == null) {
            if (LOWER.size() >= MAX_LOWER) {
                LOWER.clear();
            }
            low = text.toLowerCase(locale);
            LOWER.put(text, low);
        }
        ID_KEY[slot] = text;
        ID_VALUE[slot] = low;
        return low;
    }

    /**
     * The translated default name of one entity class: the key is built once, and the translation
     * is kept until the language changes.
     */
    private static final class DefaultName {
        final String key;
        String value;
        int version = -1;

        DefaultName(String key) {
            this.key = key;
        }
    }

    /** Marks a class whose getName is its own (player, item, horse...): always the game's path. */
    private static final DefaultName OWN_NAME = new DefaultName(null);

    /** Entity.getName (pk.e_), to find the classes that override it. */
    private static final String GET_NAME = "e_";

    private static final Map<Class<?>, DefaultName> DEFAULT_NAMES = new HashMap<>();
    private static Class<?> lastType;
    private static DefaultName lastName;

    /** Bumped after every StringTranslate.replaceWith (a language or resource pack change). */
    private static volatile int languageVersion;

    /** After every StringTranslate.replaceWith: the translations changed. */
    public static void languageChanged() {
        languageVersion++;
    }

    /**
     * In place of Entity.getName() in rotateCorpse, which asks every living entity, every frame,
     * whether it is called Dinnerbone or Grumm. Without a custom name, the game's getName builds
     * "entity." + EntityList.getEntityString + ".name" and translates it, on every call (~12% of
     * the render thread with 300 chickens). The class's key never changes (EntityList is fixed
     * after start) and the translation only changes with replaceWith, so both are kept. Classes
     * that override getName, entities with a custom name, other threads and the vanilla option take
     * the game's path.
     */
    public static Object rotateName(Object e) {
        Entity entity = (Entity) e;
        if (VANILLA || Thread.currentThread() != renderThread() || entity.hasCustomName()) {
            return entity.getName();
        }
        Class<?> type = entity.getClass();
        DefaultName n;
        if (type == lastType) {
            n = lastName;
        } else {
            n = DEFAULT_NAMES.get(type);
            if (n == null) {
                n = ownName(type) ? OWN_NAME : new DefaultName(defaultKey(entity));
                DEFAULT_NAMES.put(type, n);
            }
            lastType = type;
            lastName = n;
        }
        if (n == OWN_NAME) {
            return entity.getName();
        }
        int v = languageVersion;
        if (n.version != v) {
            n.value = StatCollector.translateToLocal(n.key);
            n.version = v;
        }
        return n.value;
    }

    /** The key Entity.getName translates for an entity without a custom name. */
    private static String defaultKey(Entity entity) {
        String s = EntityList.getEntityString(entity);
        return "entity." + (s == null ? "generic" : s) + ".name";
    }

    /** Whether the class, or a superclass below Entity, has its own getName. */
    private static boolean ownName(Class<?> type) {
        for (Class<?> c = type; c != null && c != Entity.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(GET_NAME)
                        && m.getParameterCount() == 0
                        && m.getReturnType() == String.class) {
                    return true;
                }
            }
        }
        return false;
    }

    /** In place of EnumChatFormatting.getTextWithoutFormattingCodes in rotateCorpse. */
    public static String stripFormatting(String text) {
        if (VANILLA || text == null || text.indexOf('§') >= 0) {
            return EnumChatFormatting.getTextWithoutFormattingCodes(text);
        }
        return text;
    }

    /**
     * The game thread: the first one that draws text (renderStringAtPos only runs with the GL
     * context). Before that, and on any other thread, the original path.
     */
    private static Thread renderThread() {
        Thread t = renderThread;
        if (t == null) {
            renderThread = t = Thread.currentThread();
        }
        return t;
    }
}
