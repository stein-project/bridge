/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiUtilRenderComponents;
import net.minecraft.util.ChatComponentStyle;
import net.minecraft.util.IChatComponent;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.List;
import java.util.Objects;

/**
 * Sign text kept between frames.
 *
 * <p>1.8.9's TileEntitySignRenderer redoes the text layout every frame, for every line of every
 * sign: GuiUtilRenderComponents.splitText (wraps the line, measures widths, creates new components)
 * and getFormattedText (builds the string with the colour codes). In a lobby scene, that is more
 * than half of the cost of signs — and the text only changes when the sign changes.
 *
 * <p>The results live in fields the patcher creates on the text component itself
 * (ChatComponentStyle): object identity, no external map. New text on the sign is a new object (the
 * game replaces the component, it does not edit it). The cache holds as long as the font, the
 * arguments, the unicode-font state and the generation are the same — the generation goes up on
 * every resource reload (language and glyph widths may change).
 *
 * <p>{@code -Dsteinbridge.opt.vanillaSignText=true}: layout redone every frame. {@code
 * -Dsteinbridge.opt.checkSignText=true}: uses the cache and checks every answer against the
 * original computation.
 */
public final class SignText {

    private static final boolean VANILLA = EngineOptions.VANILLA_SIGN_TEXT;
    private static final boolean CHECK = !VANILLA && EngineOptions.CHECK_SIGN_TEXT;

    /** Cache generation; starts at 1 so the fields' default value (0) never matches. */
    private static int generation = 1;

    // The fields the patcher adds to ChatComponentStyle do not exist in the game's own classes (nor
    // in the readable-name jar the Bridge compiles against), so they are reached by VarHandle.
    private static final VarHandle C_SPLIT;
    private static final VarHandle C_SPLIT_FONT;
    private static final VarHandle C_SPLIT_KEY;
    private static final VarHandle C_SPLIT_GEN;
    private static final VarHandle C_SPLIT_UNICODE;
    private static final VarHandle C_TEXT;
    private static final VarHandle C_TEXT_GEN;

    private static final CheckReport REPORT = new CheckReport(10_000_000_000L);

    static {
        try {
            MethodHandles.Lookup sl =
                    MethodHandles.privateLookupIn(ChatComponentStyle.class, MethodHandles.lookup());
            C_SPLIT = sl.findVarHandle(ChatComponentStyle.class, "sbSplit", Object.class);
            C_SPLIT_FONT = sl.findVarHandle(ChatComponentStyle.class, "sbSplitFont", Object.class);
            C_SPLIT_KEY = sl.findVarHandle(ChatComponentStyle.class, "sbSplitKey", int.class);
            C_SPLIT_GEN = sl.findVarHandle(ChatComponentStyle.class, "sbSplitGen", int.class);
            C_SPLIT_UNICODE =
                    sl.findVarHandle(ChatComponentStyle.class, "sbSplitUnicode", boolean.class);
            C_TEXT = sl.findVarHandle(ChatComponentStyle.class, "sbText", String.class);
            C_TEXT_GEN = sl.findVarHandle(ChatComponentStyle.class, "sbTextGen", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
        if (CHECK) {
            Log.info("signtext", "check: each answer compared with the original computation");
        } else if (VANILLA) {
            Log.info("signtext", "sign layout rebuilt every frame (vanilla)");
        }
    }

    private SignText() {}

    /** GuiUtilRenderComponents.splitText, called by the sign render. */
    public static Object splitText(Object component, int width, Object font, boolean a, boolean b) {
        IChatComponent text = (IChatComponent) component;
        FontRenderer renderer = (FontRenderer) font;
        if (VANILLA || !(component instanceof ChatComponentStyle)) {
            return GuiUtilRenderComponents.splitText(text, width, renderer, a, b);
        }
        int key = width * 4 + (a ? 2 : 0) + (b ? 1 : 0);
        boolean unicode = renderer.unicodeFlag;
        if ((int) C_SPLIT_GEN.get(component) == generation
                && C_SPLIT_FONT.get(component) == font
                && (int) C_SPLIT_KEY.get(component) == key
                && (boolean) C_SPLIT_UNICODE.get(component) == unicode) {
            Object cached = C_SPLIT.get(component);
            if (CHECK) {
                verifySplit(text, width, renderer, a, b, (List<?>) cached);
            }
            return cached;
        }
        List<?> r = GuiUtilRenderComponents.splitText(text, width, renderer, a, b);
        C_SPLIT.set(component, r);
        C_SPLIT_FONT.set(component, font);
        C_SPLIT_KEY.set(component, key);
        C_SPLIT_UNICODE.set(component, unicode);
        C_SPLIT_GEN.set(component, generation);
        return r;
    }

    /** IChatComponent.getFormattedText, called by the sign render. */
    public static Object formatted(Object component) {
        IChatComponent text = (IChatComponent) component;
        if (VANILLA || !(component instanceof ChatComponentStyle)) {
            return text.getFormattedText();
        }
        if ((int) C_TEXT_GEN.get(component) == generation) {
            String cached = (String) C_TEXT.get(component);
            if (CHECK) {
                verifyText(text, cached);
            }
            return cached;
        }
        String r = text.getFormattedText();
        C_TEXT.set(component, r);
        C_TEXT_GEN.set(component, generation);
        return r;
    }

    /** After every resource reload: glyph widths and translations may have changed. */
    public static void invalidate() {
        generation++;
    }

    /**
     * The current generation (goes up on every resource reload): whoever stores something made from
     * a texture or text (the retained tile entities of the new renderer) compares against it.
     */
    public static int generation() {
        return generation;
    }

    private static void verifySplit(
            IChatComponent component,
            int width,
            FontRenderer font,
            boolean a,
            boolean b,
            List<?> cached) {
        List<?> real = GuiUtilRenderComponents.splitText(component, width, font, a, b);
        boolean same = real.size() == cached.size();
        for (int i = 0; same && i < real.size(); i++) {
            same =
                    Objects.equals(
                            ((IChatComponent) real.get(i)).getFormattedText(),
                            ((IChatComponent) cached.get(i)).getFormattedText());
        }
        count(same, "line wrap");
    }

    private static void verifyText(IChatComponent component, String cached) {
        count(Objects.equals(component.getFormattedText(), cached), "formatted text");
    }

    private static void count(boolean same, String what) {
        if (REPORT.compared(same)) {
            Log.info("signtext", "DIVERGENCE in " + what);
        }
        REPORT.maybeReport("signtext");
    }
}
