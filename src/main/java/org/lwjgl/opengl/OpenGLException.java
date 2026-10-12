/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
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
