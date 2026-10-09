/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package org.lwjgl.opengl;

import org.lwjgl.util.glu.GLU;

/** Unchecked OpenGL error exception from LWJGL 2. Removed in LWJGL 3. */
public class OpenGLException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public OpenGLException() {
        super();
    }

    public OpenGLException(String msg) {
        super(msg);
    }

    public OpenGLException(int errorCode) {
        super(createMessage(errorCode));
    }

    public OpenGLException(String msg, Throwable cause) {
        super(msg, cause);
    }

    private static String createMessage(int errorCode) {
        String s = GLU.gluErrorString(errorCode);
        return s + " (" + errorCode + ")";
    }
}
