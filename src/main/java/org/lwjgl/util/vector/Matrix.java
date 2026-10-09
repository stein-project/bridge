/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
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
