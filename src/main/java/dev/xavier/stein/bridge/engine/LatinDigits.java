/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;

import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Fix for numbers written with the system language's digits: String.format keeps the player's
 * number format but writes Latin digits.
 *
 * <p>The game builds texture paths, the debug screen and many texts with String.format, which
 * formats in the system's language. On a system in Arabic, Persian or another language whose
 * numbers use their own digits, {@code %d} wrote those digits: the paths stopped matching any file
 * (a resource pack's numbered textures went missing) and the coordinates came out in a script the
 * game's font may not have. At startup (before Main reads its arguments), when the format
 * language's zero is not '0', the format language becomes the same language with Latin digits (the
 * Unicode "nu-latn" extension): the decimal and grouping separators stay the player's, and the
 * game's own language setting, which does not use this, is untouched. Every other language is left
 * as it is. {@code -Dsteinbridge.opt.vanillaDigits=true} keeps the system's digits.
 */
public final class LatinDigits {

    private LatinDigits() {}

    private static final boolean VANILLA = NetFixOptions.VANILLA_DIGITS;

    private static boolean done;

    /** Before the first System.setProperty in net.minecraft.client.main.Main.main. */
    public static void startup() {
        if (VANILLA || done) {
            return;
        }
        done = true;
        Locale format = Locale.getDefault(Locale.Category.FORMAT);
        if (DecimalFormatSymbols.getInstance(format).getZeroDigit() == '0') {
            return;
        }
        Locale latin =
                new Locale.Builder()
                        .setLocale(format)
                        .setUnicodeLocaleKeyword("nu", "latn")
                        .build();
        Locale.setDefault(Locale.Category.FORMAT, latin);
        Log.info(
                "locale", "number format " + format + " written with Latin digits (" + latin + ")");
    }
}
