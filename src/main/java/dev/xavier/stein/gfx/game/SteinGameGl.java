/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx.game;

import dev.xavier.stein.gfx.SteinDevice;
import dev.xavier.stein.gfx.SteinGfxOptions;
import dev.xavier.stein.gfx.SteinGlTrack;
import dev.xavier.stein.gfx.SteinHdr;
import dev.xavier.stein.gfx.SteinTessCapture;

import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.ARBShaderObjects;
import org.lwjgl.opengl.ARBVertexBufferObject;
import org.lwjgl.opengl.ARBVertexShader;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * The OpenGL calls the game still makes by itself (patcher rules in {@code redirects-opt.tsv}, same
 * name and signature as LWJGL's): textures, framebuffers and renderbuffers, buffers, shaders,
 * queries. With OpenGL each one goes to the driver as before; with Vulkan there is no context, and
 * they reach the game's objects on the API ({@link SteinGameTextures}, {@link
 * SteinGameFramebuffers}) or answer what OpenGL would.
 *
 * <p>With Vulkan, the game's buffers and shaders exist only as names: nothing of the game draws
 * through them (Stein GFX draws everything, and with it the game's post-processing is off on OpenGL
 * too). A draw that still arrives ({@link #glDrawArrays}) is dropped and logged with where it came
 * from.
 *
 * <p>Pipeline: both — passes to the driver with OpenGL; the objects on the API with Vulkan.
 */
public final class SteinGameGl {

    private static final boolean VULKAN = SteinGfxOptions.VULKAN;

    /** A name for the buffers, shaders and programs that only exist as names in Vulkan. */
    private static int nextName = 1;

    private SteinGameGl() {}

    // ---- textures ----

    public static int glGenTextures() {
        return VULKAN ? SteinGameTextures.gen() : GL11.glGenTextures();
    }

    public static void glDeleteTextures(int texture) {
        if (VULKAN) {
            SteinGameTextures.delete(texture);
            return;
        }
        GL11.glDeleteTextures(texture);
    }

    public static void glTexImage2D(
            int target,
            int level,
            int internalFormat,
            int w,
            int h,
            int border,
            int format,
            int type,
            ByteBuffer pixels) {
        if (VULKAN) {
            SteinGameTextures.texImage2D(target, level, internalFormat, w, h, format, type, pixels);
            return;
        }
        GL11.glTexImage2D(
                target,
                level,
                glInternal(internalFormat, pixels == null),
                w,
                h,
                border,
                format,
                type,
                pixels);
    }

    /**
     * On OpenGL, the internal format of a texture made without pixels: the game's framebuffer
     * colour (Framebuffer.createFramebuffer, the only GL_RGBA8 made empty; the game's own textures
     * ask for GL_RGBA) in 16 bits while the Lens's light is on ({@link SteinHdr#framebufferWide}),
     * so the lights go past white until the post-processing brings them back, as with Vulkan.
     */
    private static int glInternal(int internalFormat, boolean empty) {
        return empty
                        && internalFormat == GL11.GL_RGBA8
                        && !SteinGfxOptions.GL_SCENE_8
                        && SteinHdr.framebufferWide()
                ? GL30.GL_RGBA16F
                : internalFormat;
    }

    public static void glTexImage2D(
            int target,
            int level,
            int internalFormat,
            int w,
            int h,
            int border,
            int format,
            int type,
            IntBuffer pixels) {
        if (VULKAN) {
            SteinGameTextures.texImage2D(target, level, internalFormat, w, h, format, type, pixels);
            return;
        }
        GL11.glTexImage2D(
                target,
                level,
                glInternal(internalFormat, pixels == null),
                w,
                h,
                border,
                format,
                type,
                pixels);
    }

    public static void glTexSubImage2D(
            int target,
            int level,
            int x,
            int y,
            int w,
            int h,
            int format,
            int type,
            IntBuffer pixels) {
        if (VULKAN) {
            SteinGameTextures.texSubImage2D(target, level, x, y, w, h, format, type, pixels);
            return;
        }
        GL11.glTexSubImage2D(target, level, x, y, w, h, format, type, pixels);
    }

    /** The ByteBuffer variant (the Loader's textures). */
    public static void glTexSubImage2D(
            int target,
            int level,
            int x,
            int y,
            int w,
            int h,
            int format,
            int type,
            ByteBuffer pixels) {
        if (VULKAN) {
            SteinGameTextures.texSubImage2D(target, level, x, y, w, h, format, type, pixels);
            return;
        }
        GL11.glTexSubImage2D(target, level, x, y, w, h, format, type, pixels);
    }

    public static void glGetTexImage(
            int target, int level, int format, int type, IntBuffer pixels) {
        if (VULKAN) {
            SteinGameTextures.getTexImage(target, level, format, type, pixels);
            return;
        }
        GL11.glGetTexImage(target, level, format, type, pixels);
    }

    public static int glGetTexLevelParameteri(int target, int level, int pname) {
        return VULKAN
                ? SteinGameTextures.getTexLevelParameter(target, level, pname)
                : GL11.glGetTexLevelParameteri(target, level, pname);
    }

    public static void glPixelStorei(int pname, int value) {
        if (VULKAN) {
            SteinGameTextures.pixelStore(pname, value);
            return;
        }
        GL11.glPixelStorei(pname, value);
    }

    public static void glCopyTexSubImage2D(
            int target, int level, int xoff, int yoff, int x, int y, int w, int h) {
        if (VULKAN) {
            SteinGameTextures.copyTexSubImage2D(target, level, xoff, yoff, x, y, w, h);
            return;
        }
        GL11.glCopyTexSubImage2D(target, level, xoff, yoff, x, y, w, h);
    }

    public static void glReadPixels(
            int x, int y, int w, int h, int format, int type, IntBuffer pixels) {
        if (VULKAN) {
            SteinGameFramebuffers.readPixels(x, y, w, h, format, pixels);
            return;
        }
        GL11.glReadPixels(x, y, w, h, format, type, pixels);
    }

    // ---- queries ----

    /** No context, no errors: the game only asks to log them. */
    public static int glGetError() {
        return VULKAN ? GL11.GL_NO_ERROR : GL11.glGetError();
    }

    public static int glGetInteger(int pname) {
        return VULKAN ? integer(pname) : GL11.glGetInteger(pname);
    }

    public static void glGetIntegerv(int pname, IntBuffer out) {
        if (!VULKAN) {
            GL11.glGetIntegerv(pname, out);
            return;
        }
        if (pname == GL11.GL_VIEWPORT) {
            int[] v = SteinGlTrack.viewport();
            for (int i = 0; i < 4 && out.position() + i < out.limit(); i++) {
                out.put(out.position() + i, v[i]);
            }
            return;
        }
        out.put(out.position(), integer(pname));
    }

    /**
     * glGetString with Vulkan (through {@code SteinCoreGl}): the card and the API, for the F3
     * screen and the crash report.
     */
    public static String glGetString(int name) {
        return switch (name) {
            case GL11.GL_VENDOR -> "Stein GFX";
            case GL11.GL_RENDERER -> SteinDevice.get().caps().renderer;
            case GL11.GL_VERSION -> "3.0 (" + SteinDevice.get().caps().backend + ")";
            case GL11.GL_EXTENSIONS -> "";
            default -> null;
        };
    }

    /** What OpenGL would answer, for what the game asks (the snooper, the texture size). */
    private static int integer(int pname) {
        return switch (pname) {
            case GL11.GL_MAX_TEXTURE_SIZE -> SteinDevice.get().caps().maxTextureSize;
            case GL11.GL_TEXTURE_BINDING_2D ->
                    SteinGameTextures.bound(SteinGameTextures.activeUnit());
            case GL30.GL_FRAMEBUFFER_BINDING -> SteinGameFramebuffers.drawBound();
            case GL11.GL_MAX_TEXTURE_STACK_DEPTH, GL11.GL_MAX_PROJECTION_STACK_DEPTH -> 2;
            case GL11.GL_MAX_MODELVIEW_STACK_DEPTH -> 32;
            case GL11.GL_MAX_LIGHTS, GL11.GL_MAX_CLIP_PLANES -> 8;
            case GL11.GL_MAX_ATTRIB_STACK_DEPTH, GL11.GL_MAX_NAME_STACK_DEPTH -> 16;
            case GL11.GL_MAX_LIST_NESTING, GL11.GL_MAX_EVAL_ORDER -> 64;
            case GL11.GL_MAX_PIXEL_MAP_TABLE -> 256;
            case GL11.GL_MAX_VIEWPORT_DIMS -> Math.max(Display.getWidth(), 16384);
            default -> 0;
        };
    }

    // ---- framebuffers (GL 3.0 and the ARB and EXT paths of OpenGlHelper) ----

    public static int glGenFramebuffers() {
        return VULKAN ? SteinGameFramebuffers.genFramebuffer() : GL30.glGenFramebuffers();
    }

    public static int glGenFramebuffersARB() {
        return VULKAN
                ? SteinGameFramebuffers.genFramebuffer()
                : ARBFramebufferObject.glGenFramebuffers();
    }

    public static int glGenFramebuffersEXT() {
        return VULKAN
                ? SteinGameFramebuffers.genFramebuffer()
                : EXTFramebufferObject.glGenFramebuffersEXT();
    }

    public static void glFramebufferTexture2D(
            int target, int attachment, int textarget, int texture, int level) {
        if (VULKAN) {
            SteinGameFramebuffers.framebufferTexture2D(
                    target, attachment, textarget, texture, level);
            return;
        }
        GL30.glFramebufferTexture2D(target, attachment, textarget, texture, level);
    }

    public static void glFramebufferTexture2DARB(
            int target, int attachment, int textarget, int texture, int level) {
        if (VULKAN) {
            SteinGameFramebuffers.framebufferTexture2D(
                    target, attachment, textarget, texture, level);
            return;
        }
        ARBFramebufferObject.glFramebufferTexture2D(target, attachment, textarget, texture, level);
    }

    public static void glFramebufferTexture2DEXT(
            int target, int attachment, int textarget, int texture, int level) {
        if (VULKAN) {
            SteinGameFramebuffers.framebufferTexture2D(
                    target, attachment, textarget, texture, level);
            return;
        }
        EXTFramebufferObject.glFramebufferTexture2DEXT(
                target, attachment, textarget, texture, level);
    }

    public static void glFramebufferRenderbuffer(
            int target, int attachment, int rbTarget, int renderbuffer) {
        if (VULKAN) {
            SteinGameFramebuffers.framebufferRenderbuffer(
                    target, attachment, rbTarget, renderbuffer);
            return;
        }
        GL30.glFramebufferRenderbuffer(target, attachment, rbTarget, renderbuffer);
    }

    public static void glFramebufferRenderbufferARB(
            int target, int attachment, int rbTarget, int renderbuffer) {
        if (VULKAN) {
            SteinGameFramebuffers.framebufferRenderbuffer(
                    target, attachment, rbTarget, renderbuffer);
            return;
        }
        ARBFramebufferObject.glFramebufferRenderbuffer(target, attachment, rbTarget, renderbuffer);
    }

    public static void glFramebufferRenderbufferEXT(
            int target, int attachment, int rbTarget, int renderbuffer) {
        if (VULKAN) {
            SteinGameFramebuffers.framebufferRenderbuffer(
                    target, attachment, rbTarget, renderbuffer);
            return;
        }
        EXTFramebufferObject.glFramebufferRenderbufferEXT(
                target, attachment, rbTarget, renderbuffer);
    }

    public static int glCheckFramebufferStatus(int target) {
        return VULKAN
                ? SteinGameFramebuffers.checkFramebufferStatus(target)
                : GL30.glCheckFramebufferStatus(target);
    }

    public static int glCheckFramebufferStatusARB(int target) {
        return VULKAN
                ? SteinGameFramebuffers.checkFramebufferStatus(target)
                : ARBFramebufferObject.glCheckFramebufferStatus(target);
    }

    public static int glCheckFramebufferStatusEXT(int target) {
        return VULKAN
                ? SteinGameFramebuffers.checkFramebufferStatus(target)
                : EXTFramebufferObject.glCheckFramebufferStatusEXT(target);
    }

    public static int glGenRenderbuffers() {
        return VULKAN ? SteinGameFramebuffers.genRenderbuffer() : GL30.glGenRenderbuffers();
    }

    public static int glGenRenderbuffersARB() {
        return VULKAN
                ? SteinGameFramebuffers.genRenderbuffer()
                : ARBFramebufferObject.glGenRenderbuffers();
    }

    public static int glGenRenderbuffersEXT() {
        return VULKAN
                ? SteinGameFramebuffers.genRenderbuffer()
                : EXTFramebufferObject.glGenRenderbuffersEXT();
    }

    public static void glBindRenderbuffer(int target, int renderbuffer) {
        if (VULKAN) {
            SteinGameFramebuffers.bindRenderbuffer(target, renderbuffer);
            return;
        }
        GL30.glBindRenderbuffer(target, renderbuffer);
    }

    public static void glBindRenderbufferARB(int target, int renderbuffer) {
        if (VULKAN) {
            SteinGameFramebuffers.bindRenderbuffer(target, renderbuffer);
            return;
        }
        ARBFramebufferObject.glBindRenderbuffer(target, renderbuffer);
    }

    public static void glBindRenderbufferEXT(int target, int renderbuffer) {
        if (VULKAN) {
            SteinGameFramebuffers.bindRenderbuffer(target, renderbuffer);
            return;
        }
        EXTFramebufferObject.glBindRenderbufferEXT(target, renderbuffer);
    }

    public static void glRenderbufferStorage(int target, int format, int w, int h) {
        if (VULKAN) {
            SteinGameFramebuffers.renderbufferStorage(target, format, w, h);
            return;
        }
        GL30.glRenderbufferStorage(target, format, w, h);
    }

    public static void glRenderbufferStorageARB(int target, int format, int w, int h) {
        if (VULKAN) {
            SteinGameFramebuffers.renderbufferStorage(target, format, w, h);
            return;
        }
        ARBFramebufferObject.glRenderbufferStorage(target, format, w, h);
    }

    public static void glRenderbufferStorageEXT(int target, int format, int w, int h) {
        if (VULKAN) {
            SteinGameFramebuffers.renderbufferStorage(target, format, w, h);
            return;
        }
        EXTFramebufferObject.glRenderbufferStorageEXT(target, format, w, h);
    }

    public static void glDeleteRenderbuffers(int renderbuffer) {
        if (VULKAN) {
            SteinGameFramebuffers.deleteRenderbuffer(renderbuffer);
            return;
        }
        GL30.glDeleteRenderbuffers(renderbuffer);
    }

    public static void glDeleteRenderbuffersARB(int renderbuffer) {
        if (VULKAN) {
            SteinGameFramebuffers.deleteRenderbuffer(renderbuffer);
            return;
        }
        ARBFramebufferObject.glDeleteRenderbuffers(renderbuffer);
    }

    public static void glDeleteRenderbuffersEXT(int renderbuffer) {
        if (VULKAN) {
            SteinGameFramebuffers.deleteRenderbuffer(renderbuffer);
            return;
        }
        EXTFramebufferObject.glDeleteRenderbuffersEXT(renderbuffer);
    }

    // ---- drawing that should not reach here ----

    /**
     * The game's own glDrawArrays (WorldVertexBufferUploader, VertexBuffer): with Stein GFX nothing
     * gets here (the capture draws it); with Vulkan, whatever does is dropped.
     */
    public static void glDrawArrays(int mode, int first, int count) {
        if (VULKAN) {
            SteinTessCapture.droppedDirect("game glDrawArrays");
            return;
        }
        GL11.glDrawArrays(mode, first, count);
    }

    public static void glBlendEquation(int mode) {
        if (!VULKAN) {
            GL14.glBlendEquation(mode);
        }
    }

    // ---- buffers (VertexBuffer): names only in Vulkan ----

    public static int glGenBuffers() {
        return VULKAN ? nextName++ : GL15.glGenBuffers();
    }

    public static int glGenBuffersARB() {
        return VULKAN ? nextName++ : ARBVertexBufferObject.glGenBuffersARB();
    }

    public static void glBindBuffer(int target, int buffer) {
        if (!VULKAN) {
            GL15.glBindBuffer(target, buffer);
        }
    }

    public static void glBindBufferARB(int target, int buffer) {
        if (!VULKAN) {
            ARBVertexBufferObject.glBindBufferARB(target, buffer);
        }
    }

    public static void glBufferData(int target, ByteBuffer data, int usage) {
        if (!VULKAN) {
            GL15.glBufferData(target, data, usage);
        }
    }

    public static void glBufferDataARB(int target, ByteBuffer data, int usage) {
        if (!VULKAN) {
            ARBVertexBufferObject.glBufferDataARB(target, data, usage);
        }
    }

    public static void glDeleteBuffers(int buffer) {
        if (!VULKAN) {
            GL15.glDeleteBuffers(buffer);
        }
    }

    public static void glDeleteBuffersARB(int buffer) {
        if (!VULKAN) {
            ARBVertexBufferObject.glDeleteBuffersARB(buffer);
        }
    }

    // ---- shaders (ShaderLoader, ShaderManager): names only in Vulkan ----

    public static int glCreateShader(int type) {
        return VULKAN ? nextName++ : GL20.glCreateShader(type);
    }

    public static int glCreateShaderObjectARB(int type) {
        return VULKAN ? nextName++ : ARBShaderObjects.glCreateShaderObjectARB(type);
    }

    public static void glCompileShader(int shader) {
        if (!VULKAN) {
            GL20.glCompileShader(shader);
        }
    }

    public static void glCompileShaderARB(int shader) {
        if (!VULKAN) {
            ARBShaderObjects.glCompileShaderARB(shader);
        }
    }

    public static int glCreateProgram() {
        return VULKAN ? nextName++ : GL20.glCreateProgram();
    }

    public static int glCreateProgramObjectARB() {
        return VULKAN ? nextName++ : ARBShaderObjects.glCreateProgramObjectARB();
    }

    public static void glAttachShader(int program, int shader) {
        if (!VULKAN) {
            GL20.glAttachShader(program, shader);
        }
    }

    public static void glAttachObjectARB(int program, int shader) {
        if (!VULKAN) {
            ARBShaderObjects.glAttachObjectARB(program, shader);
        }
    }

    public static void glLinkProgram(int program) {
        if (!VULKAN) {
            GL20.glLinkProgram(program);
        }
    }

    public static void glLinkProgramARB(int program) {
        if (!VULKAN) {
            ARBShaderObjects.glLinkProgramARB(program);
        }
    }

    /** Compile and link status: OK (nothing was compiled). */
    public static int glGetShaderi(int shader, int pname) {
        return VULKAN ? GL11.GL_TRUE : GL20.glGetShaderi(shader, pname);
    }

    public static int glGetProgrami(int program, int pname) {
        return VULKAN ? GL11.GL_TRUE : GL20.glGetProgrami(program, pname);
    }

    public static int glGetObjectParameteriARB(int object, int pname) {
        return VULKAN ? GL11.GL_TRUE : ARBShaderObjects.glGetObjectParameteriARB(object, pname);
    }

    public static String glGetShaderInfoLog(int shader, int max) {
        return VULKAN ? "" : GL20.glGetShaderInfoLog(shader, max);
    }

    public static String glGetProgramInfoLog(int program, int max) {
        return VULKAN ? "" : GL20.glGetProgramInfoLog(program, max);
    }

    public static String glGetInfoLogARB(int object, int max) {
        return VULKAN ? "" : ARBShaderObjects.glGetInfoLogARB(object, max);
    }

    public static void glDeleteShader(int shader) {
        if (!VULKAN) {
            GL20.glDeleteShader(shader);
        }
    }

    public static void glDeleteProgram(int program) {
        if (!VULKAN) {
            GL20.glDeleteProgram(program);
        }
    }

    public static void glDeleteObjectARB(int object) {
        if (!VULKAN) {
            ARBShaderObjects.glDeleteObjectARB(object);
        }
    }

    public static int glGetUniformLocation(int program, CharSequence name) {
        return VULKAN ? 0 : GL20.glGetUniformLocation(program, name);
    }

    public static int glGetUniformLocationARB(int program, CharSequence name) {
        return VULKAN ? 0 : ARBShaderObjects.glGetUniformLocationARB(program, name);
    }

    public static int glGetAttribLocation(int program, CharSequence name) {
        return VULKAN ? 0 : GL20.glGetAttribLocation(program, name);
    }

    public static int glGetAttribLocationARB(int program, CharSequence name) {
        return VULKAN ? 0 : ARBVertexShader.glGetAttribLocationARB(program, name);
    }

    public static void glUniform1i(int location, int v) {
        if (!VULKAN) {
            GL20.glUniform1i(location, v);
        }
    }

    public static void glUniform1iARB(int location, int v) {
        if (!VULKAN) {
            ARBShaderObjects.glUniform1iARB(location, v);
        }
    }

    public static void glUniform1iv(int location, IntBuffer v) {
        if (!VULKAN) {
            GL20.glUniform1iv(location, v);
        }
    }

    public static void glUniform1ivARB(int location, IntBuffer v) {
        if (!VULKAN) {
            ARBShaderObjects.glUniform1ivARB(location, v);
        }
    }

    public static void glUniform2iv(int location, IntBuffer v) {
        if (!VULKAN) {
            GL20.glUniform2iv(location, v);
        }
    }

    public static void glUniform2ivARB(int location, IntBuffer v) {
        if (!VULKAN) {
            ARBShaderObjects.glUniform2ivARB(location, v);
        }
    }

    public static void glUniform3iv(int location, IntBuffer v) {
        if (!VULKAN) {
            GL20.glUniform3iv(location, v);
        }
    }

    public static void glUniform3ivARB(int location, IntBuffer v) {
        if (!VULKAN) {
            ARBShaderObjects.glUniform3ivARB(location, v);
        }
    }

    public static void glUniform4iv(int location, IntBuffer v) {
        if (!VULKAN) {
            GL20.glUniform4iv(location, v);
        }
    }

    public static void glUniform4ivARB(int location, IntBuffer v) {
        if (!VULKAN) {
            ARBShaderObjects.glUniform4ivARB(location, v);
        }
    }

    public static void glUniform1fv(int location, FloatBuffer v) {
        if (!VULKAN) {
            GL20.glUniform1fv(location, v);
        }
    }

    public static void glUniform1fvARB(int location, FloatBuffer v) {
        if (!VULKAN) {
            ARBShaderObjects.glUniform1fvARB(location, v);
        }
    }

    public static void glUniform2fv(int location, FloatBuffer v) {
        if (!VULKAN) {
            GL20.glUniform2fv(location, v);
        }
    }

    public static void glUniform2fvARB(int location, FloatBuffer v) {
        if (!VULKAN) {
            ARBShaderObjects.glUniform2fvARB(location, v);
        }
    }

    public static void glUniform3fv(int location, FloatBuffer v) {
        if (!VULKAN) {
            GL20.glUniform3fv(location, v);
        }
    }

    public static void glUniform3fvARB(int location, FloatBuffer v) {
        if (!VULKAN) {
            ARBShaderObjects.glUniform3fvARB(location, v);
        }
    }

    public static void glUniform4fv(int location, FloatBuffer v) {
        if (!VULKAN) {
            GL20.glUniform4fv(location, v);
        }
    }

    public static void glUniform4fvARB(int location, FloatBuffer v) {
        if (!VULKAN) {
            ARBShaderObjects.glUniform4fvARB(location, v);
        }
    }

    public static void glUniformMatrix2fv(int location, boolean transpose, FloatBuffer v) {
        if (!VULKAN) {
            GL20.glUniformMatrix2fv(location, transpose, v);
        }
    }

    public static void glUniformMatrix2fvARB(int location, boolean transpose, FloatBuffer v) {
        if (!VULKAN) {
            ARBShaderObjects.glUniformMatrix2fvARB(location, transpose, v);
        }
    }

    public static void glUniformMatrix3fv(int location, boolean transpose, FloatBuffer v) {
        if (!VULKAN) {
            GL20.glUniformMatrix3fv(location, transpose, v);
        }
    }

    public static void glUniformMatrix3fvARB(int location, boolean transpose, FloatBuffer v) {
        if (!VULKAN) {
            ARBShaderObjects.glUniformMatrix3fvARB(location, transpose, v);
        }
    }

    public static void glUniformMatrix4fv(int location, boolean transpose, FloatBuffer v) {
        if (!VULKAN) {
            GL20.glUniformMatrix4fv(location, transpose, v);
        }
    }

    public static void glUniformMatrix4fvARB(int location, boolean transpose, FloatBuffer v) {
        if (!VULKAN) {
            ARBShaderObjects.glUniformMatrix4fvARB(location, transpose, v);
        }
    }
}
