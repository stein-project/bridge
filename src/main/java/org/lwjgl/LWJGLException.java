/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
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
