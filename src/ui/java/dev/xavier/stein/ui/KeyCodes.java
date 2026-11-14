/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.ui;

/**
 * LWJGL 2 key codes the screens use besides the few that the Bridge's {@code Keyboard} declares
 * ({@code KEY_ESCAPE}, {@code KEY_RETURN}, {@code KEY_LSHIFT}).
 */
public final class KeyCodes {

    public static final int BACK = 14;
    public static final int RSHIFT = 54;
    public static final int NUMPADENTER = 156;
    public static final int HOME = 199;
    public static final int UP = 200;
    public static final int PRIOR = 201;
    public static final int LEFT = 203;
    public static final int RIGHT = 205;
    public static final int END = 207;
    public static final int DOWN = 208;
    public static final int NEXT = 209;
    public static final int INSERT = 210;
    public static final int DELETE = 211;

    private KeyCodes() {}
}
