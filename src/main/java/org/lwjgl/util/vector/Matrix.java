/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package org.lwjgl.util.vector;

import java.io.Serializable;

/**
 * Base of the LWJGL 2 matrix hierarchy (removed in LWJGL 3).
 *
 * <p>Deliberately minimal: Minecraft 1.8.9 only references {@code setIdentity()} on this class. See
 * {@code scan/lwjgl-methods.txt}.
 */
public abstract class Matrix implements Serializable {
    public abstract Matrix setIdentity();
}
