/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package org.lwjgl;

/**
 * Checked exception of LWJGL 2, thrown by window and context creation operations. Removed in LWJGL
 * 3, but Minecraft has catch blocks that reference it — without the class, loading fails.
 */
public class LWJGLException extends Exception {

    private static final long serialVersionUID = 1L;

    public LWJGLException() {
        super();
    }

    public LWJGLException(String msg) {
        super(msg);
    }

    public LWJGLException(String msg, Throwable cause) {
        super(msg, cause);
    }

    public LWJGLException(Throwable cause) {
        super(cause);
    }
}
