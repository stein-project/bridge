/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package org.lwjgl.opengl;

import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR;
import static org.lwjgl.glfw.GLFW.GLFW_DEPTH_BITS;
import static org.lwjgl.glfw.GLFW.GLFW_DONT_CARE;
import static org.lwjgl.glfw.GLFW.GLFW_FALSE;
import static org.lwjgl.glfw.GLFW.GLFW_FOCUSED;
import static org.lwjgl.glfw.GLFW.GLFW_FOCUS_ON_SHOW;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_ANY_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_DEBUG_CONTEXT;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_FORWARD_COMPAT;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_RESIZABLE;
import static org.lwjgl.glfw.GLFW.GLFW_SAMPLES;
import static org.lwjgl.glfw.GLFW.GLFW_STENCIL_BITS;
import static org.lwjgl.glfw.GLFW.GLFW_TRUE;
import static org.lwjgl.glfw.GLFW.GLFW_VISIBLE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDefaultWindowHints;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwExtensionSupported;
import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.glfw.GLFW.glfwGetPrimaryMonitor;
import static org.lwjgl.glfw.GLFW.glfwGetVideoMode;
import static org.lwjgl.glfw.GLFW.glfwGetVideoModes;
import static org.lwjgl.glfw.GLFW.glfwGetWindowPos;
import static org.lwjgl.glfw.GLFW.glfwGetWindowSize;
import static org.lwjgl.glfw.GLFW.glfwIconifyWindow;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwPollEvents;
import static org.lwjgl.glfw.GLFW.glfwSetErrorCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowAttrib;
import static org.lwjgl.glfw.GLFW.glfwSetWindowIcon;
import static org.lwjgl.glfw.GLFW.glfwSetWindowMonitor;
import static org.lwjgl.glfw.GLFW.glfwSetWindowPos;
import static org.lwjgl.glfw.GLFW.glfwSetWindowPosCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowSize;
import static org.lwjgl.glfw.GLFW.glfwSetWindowTitle;
import static org.lwjgl.glfw.GLFW.glfwShowWindow;
import static org.lwjgl.glfw.GLFW.glfwSwapBuffers;
import static org.lwjgl.glfw.GLFW.glfwSwapInterval;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.glfw.GLFW.glfwWindowShouldClose;
import static org.lwjgl.system.MemoryUtil.NULL;

import dev.xavier.stein.bridge.bench.Bench;
import dev.xavier.stein.bridge.bench.BenchZones;
import dev.xavier.stein.bridge.core.CoreOptions;
import dev.xavier.stein.bridge.core.Input;
import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.core.Win;
import dev.xavier.stein.bridge.diag.Scenes;
import dev.xavier.stein.bridge.engine.ChunkArena;
import dev.xavier.stein.bridge.engine.ClientSettings;
import dev.xavier.stein.bridge.engine.DirectRender;
import dev.xavier.stein.bridge.engine.FrameTime;
import dev.xavier.stein.bridge.engine.IdleLimit;
import dev.xavier.stein.bridge.engine.ModelBatch;
import dev.xavier.stein.bridge.engine.ServerWatch;
import dev.xavier.stein.gfx.SteinBackend;
import dev.xavier.stein.gfx.SteinDevice;
import dev.xavier.stein.gfx.SteinGfxOptions;
import dev.xavier.stein.gfx.game.SteinGameFramebuffers;
import dev.xavier.stein.gfx.gl.SteinGlDebug;
import dev.xavier.stein.gfx.gl.SteinGlFrame;
import dev.xavier.stein.gfx.gl.SteinGlGpuTiming;
import dev.xavier.stein.gfx.vk.SteinVkGpuTiming;
import dev.xavier.stein.gfx.vk.SteinVkWindow;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.glfw.GLFWWindowPosCallback;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * LWJGL 2 window API implemented over GLFW.
 *
 * <p>Pipeline: both — creates a core context for Stein GFX, or the compatibility context for the
 * fixed pipeline, and ends the frame in each. With Vulkan ({@link SteinBackend}) the window has no
 * OpenGL context and the frame ends by presenting the game's screen.
 */
public final class Display {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    /** Below this much time left in the frame, {@link #sync(int)} spins instead of sleeping. */
    private static final long SPIN_THRESHOLD_NANOS = 2_000_000L;

    /** A frame this many periods late counts as a stall, and the limiter re-anchors. */
    private static final int STALL_PERIODS = 4;

    private static String title = "Minecraft";
    private static boolean resizable;
    private static boolean vsync;
    private static DisplayMode mode = new DisplayMode(854, 480);
    private static GLFWErrorCallback errorCallback;

    /** Target instant of the next frame, in nanos, for {@link #sync(int)}. */
    private static long nextFrame;

    /**
     * VSync without the two usual defects: adaptive when the card has the extension (a late frame
     * goes out immediately, instead of waiting for the next flip and dropping 60 to 30) and a GPU
     * fence per frame, which stops the driver from stacking up finished frames — it is the stacking
     * that gives the feeling of a heavy mouse.
     */
    private static final boolean LATENCY_FIX = !CoreOptions.NO_VSYNC_LATENCY_FIX;

    private static long fence;
    private static int adaptive = -1;
    private static int refresh;

    /** With Vulkan: a window move shows the last frame again ({@link #onMoved}). */
    private static GLFWWindowPosCallback moved;

    /** Inside the glfwPollEvents of {@link #swap()}, between two frames. */
    private static boolean polling;

    private Display() {}

    // ---- creation ----

    public static void create() {
        create(new PixelFormat());
    }

    public static void create(PixelFormat pf) {
        if (Win.isCreated()) {
            return;
        }
        errorCallback = GLFWErrorCallback.createPrint(System.err);
        glfwSetErrorCallback(errorCallback);
        if (!glfwInit()) {
            throw new IllegalStateException("Failed to initialize GLFW");
        }

        boolean background = CoreOptions.DIAG_BACKGROUND;
        long win = NULL;
        if (CoreOptions.GFX && SteinBackend.requested() == SteinBackend.VULKAN) {
            applyWindowHints(pf, background);
            win = SteinVkWindow.create(mode.getWidth(), mode.getHeight(), title);
        }
        if (win == NULL) {
            applyWindowHints(pf, background);
            win = createWindow();
        }
        SteinBackend.started(Win.vulkan ? SteinBackend.VULKAN : SteinBackend.OPENGL);
        // Stein GFX reads Win.coreContext and Win.vulkan once, when SteinGfxOptions loads: it must
        // load after the window exists, or it would see neither and stay off.
        if ((Win.coreContext || Win.vulkan) && !SteinGfxOptions.ENABLED) {
            throw new IllegalStateException("SteinGfxOptions was loaded before the window");
        }
        Win.handle = win;
        Win.windowedWidth = mode.getWidth();
        Win.windowedHeight = mode.getHeight();
        applyIcons(); // requested by the game before the window existed

        centerOnPrimary();
        if (!Win.vulkan) {
            glfwMakeContextCurrent(win);
            GL.createCapabilities();
            SteinGlDebug.install();
        }
        applyInterval();

        Input.install(win);
        if (Win.vulkan) {
            moved = GLFWWindowPosCallback.create(Display::onMoved);
            glfwSetWindowPosCallback(win, moved);
        }
        refreshSizes();
        if (background) {
            glfwIconifyWindow(win);
        }
        glfwShowWindow(win);
    }

    /** Window hints: buffers, resizability and the test bench background mode. */
    private static void applyWindowHints(PixelFormat pf, boolean background) {
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, resizable ? GLFW_TRUE : GLFW_FALSE);
        glfwWindowHint(GLFW_DEPTH_BITS, pf.getDepthBits());
        glfwWindowHint(GLFW_STENCIL_BITS, pf.getStencilBits());
        if (pf.getSamples() > 0) {
            glfwWindowHint(GLFW_SAMPLES, pf.getSamples());
        }
        if (CoreOptions.GL_DEBUG) {
            glfwWindowHint(GLFW_OPENGL_DEBUG_CONTEXT, GLFW_TRUE);
        }

        // -Dsteinbridge.diag.background=true (test bench only): the window does not take focus
        // when it appears and already comes out minimized — without focus the cursor is not
        // captured, and whoever is using the computer is not interrupted. The saved size remains
        // valid while minimized (refreshSizes), so the game draws and measures as usual.
        if (background) {
            glfwWindowHint(GLFW_FOCUS_ON_SHOW, GLFW_FALSE);
            glfwWindowHint(GLFW_FOCUSED, GLFW_FALSE);
        }
    }

    /**
     * Creates the window. With Stein GFX (the default) the context is core, 4.6 or else 4.1 (the
     * Mac's maximum, which only gives core with forward compatible): the driver has no
     * fixed-function pipeline, and only Stein GFX draws. When the driver gives neither, or with
     * {@code -Dsteinbridge.gfx=false}, the window gets the compatibility context the driver
     * delivers by default and the game draws with its own fixed-function pipeline.
     */
    private static long createWindow() {
        if (CoreOptions.GFX) {
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 6);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            long win = glfwCreateWindow(mode.getWidth(), mode.getHeight(), title, NULL, NULL);
            if (win == NULL) {
                glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
                glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
                win = glfwCreateWindow(mode.getWidth(), mode.getHeight(), title, NULL, NULL);
            }
            if (win != NULL) {
                Win.coreContext = true;
                return win;
            }
            Log.warn("gfx", "no OpenGL 4.1 core context: drawing with the fixed-function pipeline");
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 1);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 0);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_ANY_PROFILE);
            glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_FALSE);
        }
        long win = glfwCreateWindow(mode.getWidth(), mode.getHeight(), title, NULL, NULL);
        if (win == NULL) {
            throw new IllegalStateException("Failed to create the GLFW window");
        }
        return win;
    }

    private static void centerOnPrimary() {
        GLFWVidMode vm = glfwGetVideoMode(glfwGetPrimaryMonitor());
        if (vm == null) {
            return;
        }
        int x = (vm.width() - Win.windowedWidth) / 2;
        int y = (vm.height() - Win.windowedHeight) / 2;
        glfwSetWindowPos(Win.handle, x, y);
        Win.windowedX = x;
        Win.windowedY = y;
    }

    /**
     * The framebuffer size is what OpenGL sees and may differ from the window size on scaled
     * screens. We keep both: the framebuffer governs getWidth/getHeight, and the ratio between them
     * corrects the mouse.
     */
    private static boolean refreshSizes() {
        int[] w = new int[1];
        int[] h = new int[1];
        glfwGetFramebufferSize(Win.handle, w, h);
        // Minimized, the framebuffer comes back 0x0. LWJGL 2 kept the previous size; passing it on
        // would make the game rebuild the screens at 1x1 (and text wrapped to a negative width
        // overflows the stack).
        if (w[0] <= 0 || h[0] <= 0) {
            return false;
        }
        Win.width = w[0];
        Win.height = h[0];
        glfwGetWindowSize(Win.handle, w, h);
        Win.windowWidth = Math.max(1, w[0]);
        Win.windowHeight = Math.max(1, h[0]);
        return true;
    }

    public static void destroy() {
        // the game's own shutdown: a Vulkan start closed in its first seconds is not a crash
        SteinBackend.closing();
        if (!Win.isCreated()) {
            return;
        }
        Input.free();
        if (Win.vulkan) {
            // the GPU idle and the swapchain and surface gone before their window
            SteinVkWindow.destroy();
        }
        glfwDestroyWindow(Win.handle);
        Win.handle = 0L;
        if (moved != null) {
            moved.free();
            moved = null;
        }
        glfwTerminate();
        if (errorCallback != null) {
            errorCallback.free();
            errorCallback = null;
        }
    }

    public static boolean isCreated() {
        return Win.isCreated();
    }

    // ---- loop ----

    public static void update() {
        if (!Win.isCreated()) {
            return;
        }
        endOfFrame();
        swap();
        if (Win.sizeDirty) {
            Win.sizeDirty = false;
            Win.resized = refreshSizes();
        }
        // The game idle (out of focus, title screen, Esc menu): fewer frames, less load on the
        // processor and the graphics card.
        BenchZones.begin(BenchZones.IDLE);
        IdleLimit.frame();
        BenchZones.end(BenchZones.IDLE);
        // The benchmark's row for this frame (only with -Dsteinbridge.bench).
        Bench.frameEnd();
    }

    /** Everything the engine does after the game has drawn and before the buffer swap. */
    private static void endOfFrame() {
        // F3 frame time.
        FrameTime.tick();
        if (Win.vulkan) {
            // in the submit zone like OpenGL's same work, so the two compare zone for zone
            BenchZones.begin(BenchZones.SUBMIT);
            endOfVulkanFrame();
            BenchZones.end(BenchZones.SUBMIT);
            return;
        }
        // Stein GFX recorded frame: the whole list goes to the driver here, before everything in
        // the swap (only with the renderer).
        BenchZones.begin(BenchZones.SUBMIT);
        SteinGlFrame.endFrame();
        // Batched model parts go out before the swap; fence in the frame's region.
        ModelBatch.endFrame();
        ChunkArena.endFrame();
        // Deferred F2 of the direct drawing.
        DirectRender.beforeSwap();
        // Integrated server delay in the log.
        ServerWatch.frame();
        // Pending options.txt of the volume screen.
        ClientSettings.frame();
        if (Scenes.ENABLED) {
            Scenes.onFrame();
        }
        // GPU time per pass (only with gpuPassTiming).
        SteinGlGpuTiming.frame();
        // Fences requested after the recorded frame was submitted (end of frame of the terrain and
        // the rings): together, before the swap.
        SteinGlFrame.flushFences();
        BenchZones.end(BenchZones.SUBMIT);
        // Waits for the GPU to finish the previous frame before sending this one: at most one frame
        // in flight.
        BenchZones.begin(BenchZones.AWAIT);
        awaitFrame();
        BenchZones.end(BenchZones.AWAIT);
    }

    /**
     * The end of the frame with Vulkan: the engine's per-frame work that does not touch OpenGL (the
     * presentation in {@link #swap()} submits the frame).
     */
    private static void endOfVulkanFrame() {
        // the terrain's end of frame (Stein GFX: the slices released go back after their fence)
        ChunkArena.endFrame();
        DirectRender.beforeSwap();
        ServerWatch.frame();
        ClientSettings.frame();
        if (Scenes.ENABLED) {
            Scenes.onFrame();
        }
        // GPU time per pass (only with gpuPassTiming)
        SteinVkGpuTiming.frame();
    }

    /**
     * {@code -Dsteinbridge.perf.timeSwap=true}: measures how much of the frame goes to the handoff
     * to the window — the buffer swap and the event queue — and prints the worst case and the
     * average per second. It serves to separate what is ours (drawing) from what is the system's
     * (driver and Windows composition) in frametime irregularity.
     */
    private static final boolean TIME_SWAP = CoreOptions.TIME_SWAP;

    private static long swapNanos;
    private static long swapMax;
    private static long pollNanos;
    private static long pollMax;
    private static int swapFrames;
    private static long swapReport;

    /** Swaps the buffers and polls the events, timing both when {@link #TIME_SWAP} is on. */
    private static void swap() {
        long t0 = TIME_SWAP ? System.nanoTime() : 0L;
        // with Vulkan the frame's submission is in the presentation
        BenchZones.begin(BenchZones.SWAP);
        if (Win.vulkan) {
            SteinGameFramebuffers.present();
        } else {
            glfwSwapBuffers(Win.handle);
        }
        BenchZones.end(BenchZones.SWAP);
        long t1 = TIME_SWAP ? System.nanoTime() : 0L;
        markFrame();
        Win.resized = false;
        polling = Win.vulkan;
        BenchZones.begin(BenchZones.EVENTS);
        glfwPollEvents();
        BenchZones.end(BenchZones.EVENTS);
        polling = false;
        Input.afterEvents();
        if (TIME_SWAP) {
            recordSwap(t0, t1, System.nanoTime());
        }
    }

    /**
     * Dragging the title bar runs Windows' move loop inside glfwPollEvents: the game draws nothing
     * until the button is released, and with a Vulkan swapchain the image only follows the window
     * on a new presentation (it lagged behind the frame). Each move presents the last frame again.
     */
    private static void onMoved(long w, int x, int y) {
        if (polling) {
            SteinGameFramebuffers.present();
        }
    }

    private static void recordSwap(long t0, long t1, long t2) {
        swapNanos += t1 - t0;
        pollNanos += t2 - t1;
        swapMax = Math.max(swapMax, t1 - t0);
        pollMax = Math.max(pollMax, t2 - t1);
        swapFrames++;
        if (t2 - swapReport >= NANOS_PER_SECOND) {
            swapReport = t2;
            Log.info(
                    "present",
                    "%d frames: swap %.2f ms (worst %.2f) | events %.2f ms (worst %.2f)",
                    swapFrames,
                    swapNanos / 1e6 / swapFrames,
                    swapMax / 1e6,
                    pollNanos / 1e6 / swapFrames,
                    pollMax / 1e6);
            swapNanos = 0;
            pollNanos = 0;
            swapMax = 0;
            pollMax = 0;
            swapFrames = 0;
        }
    }

    private static void awaitFrame() {
        if (fence == 0L) {
            return;
        }
        GL32.glClientWaitSync(fence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, NANOS_PER_SECOND);
        GL32.glDeleteSync(fence);
        fence = 0L;
    }

    private static void markFrame() {
        if (Win.vulkan) {
            return; // the swapchain keeps its own frames in flight
        }
        if (vsync && LATENCY_FIX && GL.getCapabilities().OpenGL32) {
            fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        }
    }

    /**
     * Frame limiter. With VSync on, the monitor is in charge: adding another limit on top makes the
     * two beat against each other (one frame of 16 ms, another of 33). It sleeps most of the
     * interval and yields the rest in {@code onSpinWait}: {@code Thread.sleep} alone has too coarse
     * a granularity and would produce irregular frametime.
     */
    public static void sync(int fps) {
        BenchZones.begin(BenchZones.LIMITER);
        // the benchmark's title-menus part measures them without the game's 30 fps menu cap
        limit(Bench.uncappedMenus() ? 0 : fps);
        BenchZones.end(BenchZones.LIMITER);
    }

    private static void limit(int fps) {
        if (vsync && fps >= refreshRate()) {
            nextFrame = 0;
            return;
        }
        if (fps <= 0) {
            nextFrame = 0;
            return;
        }
        long period = NANOS_PER_SECOND / fps;
        long now = System.nanoTime();
        if (nextFrame == 0 || now - nextFrame > period * STALL_PERIODS) {
            nextFrame = now; // first call or long stall: re-anchor
        }
        nextFrame += period;

        long remaining = nextFrame - System.nanoTime();
        while (remaining > 0) {
            if (remaining > SPIN_THRESHOLD_NANOS) {
                try {
                    Thread.sleep(1L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } else {
                Thread.onSpinWait();
            }
            remaining = nextFrame - System.nanoTime();
        }
    }

    public static boolean isCloseRequested() {
        return Win.isCreated() && glfwWindowShouldClose(Win.handle);
    }

    public static boolean isActive() {
        return Win.focused;
    }

    /** Consumes the flag: LWJGL 2 reports the resize only once. */
    public static boolean wasResized() {
        boolean r = Win.resized;
        Win.resized = false;
        return r;
    }

    /**
     * The framebuffer size right now, asked of GLFW (not the stored one, which is only updated
     * after the buffer swap of the game loop): whoever draws outside the loop (in the middle of
     * startup or of a reload) gets the size of a window that may have just changed. Minimized
     * (0x0), the stored one remains.
     */
    public static int[] framebufferSize() {
        int[] w = new int[1];
        int[] h = new int[1];
        glfwGetFramebufferSize(Win.handle, w, h);
        return w[0] > 0 && h[0] > 0 ? new int[] {w[0], h[0]} : new int[] {Win.width, Win.height};
    }

    public static int getWidth() {
        return Win.width;
    }

    public static int getHeight() {
        return Win.height;
    }

    // ---- configuration ----

    public static String getTitle() {
        return title;
    }

    public static void setTitle(String t) {
        title = t == null ? "" : t;
        if (Win.isCreated()) {
            glfwSetWindowTitle(Win.handle, title);
        }
    }

    public static void setResizable(boolean r) {
        resizable = r;
        if (Win.isCreated()) {
            glfwSetWindowAttrib(Win.handle, GLFW_RESIZABLE, r ? GLFW_TRUE : GLFW_FALSE);
        }
    }

    public static void setVSyncEnabled(boolean v) {
        vsync = v;
        if (Win.isCreated()) {
            applyInterval();
        }
    }

    /**
     * 0 without VSync; -1 adaptive (the card lets a late frame go out immediately); 1 the usual
     * one.
     */
    private static void applyInterval() {
        if (Win.vulkan) {
            SteinDevice.get().vsync(vsync); // the swapchain's present mode
            return;
        }
        if (adaptive < 0) {
            adaptive =
                    glfwExtensionSupported("WGL_EXT_swap_control_tear")
                                    || glfwExtensionSupported("GLX_EXT_swap_control_tear")
                            ? 1
                            : 0;
            Log.info(
                    "bridge",
                    "vsync "
                            + (LATENCY_FIX && adaptive == 1 ? "adaptive" : "normal")
                            + ", monitor at "
                            + refreshRate()
                            + " Hz");
        }
        // -Dsteinbridge.opt.noVsyncLatencyFix=true goes back to the usual VSync (never tears the
        // image, but drops by half).
        glfwSwapInterval(!vsync ? 0 : LATENCY_FIX && adaptive == 1 ? -1 : 1);
    }

    /** Monitor frames per second; 60 when GLFW does not know. */
    private static int refreshRate() {
        if (refresh <= 0) {
            GLFWVidMode vm = glfwGetVideoMode(glfwGetPrimaryMonitor());
            refresh = vm != null && vm.refreshRate() > 0 ? vm.refreshRate() : 60;
        }
        return refresh;
    }

    public static void setDisplayMode(DisplayMode dm) {
        mode = dm;
        if (!Win.isCreated()) {
            return;
        }
        Win.windowedWidth = dm.getWidth();
        Win.windowedHeight = dm.getHeight();
        if (!Win.fullscreen) {
            glfwSetWindowSize(Win.handle, dm.getWidth(), dm.getHeight());
            refreshSizes();
        }
    }

    public static void setFullscreen(boolean fs) {
        if (!Win.isCreated() || fs == Win.fullscreen) {
            Win.fullscreen = fs;
            return;
        }
        long monitor = glfwGetPrimaryMonitor();
        GLFWVidMode vm = glfwGetVideoMode(monitor);
        if (vm == null) {
            return;
        }
        if (fs) {
            int[] x = new int[1];
            int[] y = new int[1];
            glfwGetWindowPos(Win.handle, x, y);
            Win.windowedX = x[0];
            Win.windowedY = y[0];
            glfwSetWindowMonitor(
                    Win.handle, monitor, 0, 0, vm.width(), vm.height(), vm.refreshRate());
        } else {
            glfwSetWindowMonitor(
                    Win.handle,
                    NULL,
                    Win.windowedX,
                    Win.windowedY,
                    Win.windowedWidth,
                    Win.windowedHeight,
                    GLFW_DONT_CARE);
        }
        Win.fullscreen = fs;
        // glfwSetWindowMonitor discards the swap interval.
        applyInterval();
        refreshSizes();
        Win.resized = true;
    }

    /** Icons of the last setIcon, in direct buffers; reapplied when the window is born. */
    private static ByteBuffer[] icons;

    /**
     * LWJGL 2 receives icons as raw RGBA and infers the dimension assuming a square image. GLFW
     * wants explicit width and height.
     *
     * <p>1.8.9 calls setIcon before create (and LWJGL 2 kept the icons for the window that was
     * coming): here they are kept and enter in create. The game delivers heap buffers; GLFW reads
     * by address, so they are copied into direct buffers.
     *
     * @return number of icons accepted
     */
    public static int setIcon(ByteBuffer[] given) {
        if (given == null || given.length == 0) {
            return 0;
        }
        List<ByteBuffer> valid = new ArrayList<>();
        for (ByteBuffer b : given) {
            if (b == null) {
                continue;
            }
            int side = iconSide(b);
            if (side > 0 && side * side * 4 == b.remaining()) {
                ByteBuffer direct = ByteBuffer.allocateDirect(b.remaining());
                direct.put(b.duplicate()).flip();
                valid.add(direct);
            }
        }
        if (valid.isEmpty()) {
            return 0;
        }
        icons = valid.toArray(new ByteBuffer[0]);
        Log.info(
                "window",
                "icon: "
                        + icons.length
                        + " images"
                        + (Win.isCreated() ? "" : " (before the window)"));
        if (Win.isCreated()) {
            applyIcons();
        }
        return icons.length;
    }

    /** Side of the square RGBA image in the buffer (the remaining bytes / 4, square-rooted). */
    private static int iconSide(ByteBuffer rgba) {
        return (int) Math.round(Math.sqrt(rgba.remaining() / 4.0));
    }

    private static void applyIcons() {
        if (icons == null) {
            return;
        }
        try (GLFWImage.Buffer images = GLFWImage.malloc(icons.length)) {
            for (int i = 0; i < icons.length; i++) {
                int side = iconSide(icons[i]);
                images.position(i).width(side).height(side).pixels(icons[i]);
            }
            images.position(0);
            glfwSetWindowIcon(Win.handle, images);
        }
    }

    // ---- video modes ----

    public static DisplayMode getDisplayMode() {
        return mode;
    }

    public static DisplayMode getDesktopDisplayMode() {
        boolean tempInit = ensureGlfw();
        GLFWVidMode vm = glfwGetVideoMode(glfwGetPrimaryMonitor());
        DisplayMode out =
                vm == null ? new DisplayMode(1920, 1080, 32, 60, true) : toDisplayMode(vm);
        if (tempInit) {
            glfwTerminate();
        }
        return out;
    }

    public static DisplayMode[] getAvailableDisplayModes() {
        boolean tempInit = ensureGlfw();
        GLFWVidMode.Buffer modes = glfwGetVideoModes(glfwGetPrimaryMonitor());
        List<DisplayMode> out = new ArrayList<>();
        if (modes != null) {
            for (int i = 0; i < modes.limit(); i++) {
                GLFWVidMode vm = modes.get(i);
                out.add(toDisplayMode(vm));
            }
        }
        if (tempInit) {
            glfwTerminate();
        }
        return out.toArray(new DisplayMode[0]);
    }

    private static DisplayMode toDisplayMode(GLFWVidMode vm) {
        return new DisplayMode(
                vm.width(),
                vm.height(),
                vm.redBits() + vm.greenBits() + vm.blueBits(),
                vm.refreshRate(),
                true);
    }

    /**
     * Minecraft queries video modes before creating the window. It initializes GLFW temporarily in
     * that case.
     *
     * @return true if this method was the one that initialized it, and so it must shut it down
     */
    private static boolean ensureGlfw() {
        if (Win.isCreated()) {
            return false;
        }
        return glfwInit();
    }
}
