/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.gfx.game.SteinGameGl;

import net.minecraft.client.renderer.ThreadDownloadImageData;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.resources.IResource;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.util.ResourceLocation;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.net.HttpURLConnection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

/**
 * Takes off the render thread the repeated work of loading skins when a crowd arrives.
 *
 * <p>In 1.8.9, each skin texture (ThreadDownloadImageData.loadTexture), when registered, does the
 * following on the render thread: (1) loads the resource pack's DEFAULT skin — lookup, PNG inflate,
 * upload — and (2) reads the real skin from the disk cache and runs parseUserSkin. With 400 players
 * arriving, the 400 tasks run in the same tick. JFR showed (1) as the expensive part: 43% of a 504
 * ms spike, the same image decoded 400 times. (2) costs ~0.3 ms per skin.
 *
 * <p>The patcher replaces the method body with this one:
 *
 * <ul>
 *   <li>(1) the default skin is decoded once and reused for a few seconds; each texture only
 *       repeats the 16 KB upload. If the resource has metadata (the vanilla default has none), it
 *       follows the original path.
 *   <li>(2) goes to two background threads. Precedent in the game itself: on the network download,
 *       1.8.9 already calls setBufferedImage from a background thread. Until the skin arrives the
 *       player shows up with the default skin — the same as already happens on the download.
 * </ul>
 *
 * <p>{@code -Dsteinbridge.opt.vanillaSkinLoad=true} does everything as the original, for A/B.
 *
 * <p>Two fixes of the skin texture live here too, under {@code
 * -Dsteinbridge.opt.vanillaSkinDownload=true}: the download has a time limit ({@link #connect}),
 * and the decoded image is let go once uploaded ({@link #checkUploaded}). In 1.8.9 the image (16 KB
 * for a 64x64 skin) stayed in the texture for as long as the game ran, one per player ever seen: a
 * day in a lobby kept tens of megabytes of skins the GPU already had. Nothing reads the image after
 * the upload (the texture is never uploaded again), and a resource reload of an uploaded skin,
 * which in 1.8.9 read the skin from the cache again only to keep it on the heap, now leaves the
 * texture as it is.
 */
public final class AsyncSkins {

    private static final boolean VANILLA = EngineOptions.VANILLA_SKIN_LOAD;

    /** One texture per skin, as before sharing the default one; for the A/B measurement. */
    private static final boolean NO_SHARED = EngineOptions.NO_SHARED_SKIN;

    /** 1.8.9's download with no time limit and the image kept after the upload. */
    private static final boolean KEEP_IMAGE = NetFixOptions.VANILLA_SKIN_DOWNLOAD;

    /**
     * Connection and read limit of a skin or cape download. The skin server answers in well under a
     * second; a connection that stalls (a proxy, a dropped packet) held its download thread forever
     * and the player stayed with the default skin.
     */
    private static final int DOWNLOAD_TIMEOUT_MS = 10_000;

    /**
     * Validity of the decoded default skin. A burst lasts under a second; the short deadline limits
     * the window in which a resource pack change would still show the old default — and only until
     * each player's real skin arrives.
     */
    private static final long DEFAULT_TTL_NANOS = 5_000_000_000L;

    // A "super" call (SimpleTexture.loadTexture on a ThreadDownloadImageData instance) cannot be
    // written as plain Java from outside the class, so it stays a MethodHandle (invokespecial; "a"
    // is the method's name in the real game).
    private static final MethodHandle SUPER_LOAD;

    // The same for AbstractTexture.getGlTextureId: ThreadDownloadImageData overrides it to upload
    // first, so the upload itself needs the parent's ("b" in the real game).
    private static final MethodHandle SUPER_ID;

    // The sbSharedSkin field is created by the patcher: it does not exist in the readable-name jar
    // the Bridge compiles against, so it is reached by VarHandle.
    private static final VarHandle SHARED;

    static {
        try {
            MethodHandles.Lookup t =
                    MethodHandles.privateLookupIn(
                            ThreadDownloadImageData.class, MethodHandles.lookup());
            SUPER_LOAD =
                    t.findSpecial(
                                    SimpleTexture.class,
                                    "a",
                                    MethodType.methodType(void.class, IResourceManager.class),
                                    ThreadDownloadImageData.class)
                            .asType(MethodType.methodType(void.class, Object.class, Object.class));
            SUPER_ID =
                    t.findSpecial(
                                    SimpleTexture.class,
                                    "b",
                                    MethodType.methodType(int.class),
                                    ThreadDownloadImageData.class)
                            .asType(MethodType.methodType(int.class, Object.class));
            SHARED = t.findVarHandle(ThreadDownloadImageData.class, "sbSharedSkin", int.class);
        } catch (ReflectiveOperationException e) {
            // Wrong names mean the wrong game version: failing loudly is better
            // than leaving skins silently unloaded.
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final AtomicInteger THREADS = new AtomicInteger();

    /** Priority below the render thread: competing with it for CPU would cancel the gain. */
    private static final ExecutorService POOL =
            Executors.newFixedThreadPool(
                    2,
                    r -> {
                        Thread thread =
                                new Thread(
                                        r, "SteinBridge Skin Loader #" + THREADS.incrementAndGet());
                        thread.setDaemon(true);
                        thread.setPriority(Thread.NORM_PRIORITY - 1);
                        return thread;
                    });

    /** Avoids queueing the same texture twice if loadTexture is called again before the end. */
    private static final Set<Object> IN_FLIGHT =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /** Already decoded default skin, by resource location. Only the render thread uses it. */
    private static final Map<Object, Decoded> DEFAULTS = new HashMap<>();

    private static final class Decoded {
        final BufferedImage image;
        final long at;

        /**
         * The OpenGL texture with this image, shared by all the skins still waiting for the real
         * one.
         */
        int shared;

        Decoded(BufferedImage image, long at) {
            this.image = image;
            this.at = at;
        }
    }

    private AsyncSkins() {}

    /** Replacement body of ThreadDownloadImageData.loadTexture(IResourceManager). */
    public static void loadTexture(Object self, Object resourceManager) throws Throwable {
        ThreadDownloadImageData skin = (ThreadDownloadImageData) self;
        if (!KEEP_IMAGE && skin.textureUploaded) {
            // A resource reload of a skin already on the GPU: 1.8.9 changed nothing in the
            // texture either (it is never uploaded again), only read the cache back to the heap.
            return;
        }
        // Same condition as the original for loading the default skin while the real one has not
        // arrived.
        if (skin.bufferedImage == null && ((SimpleTexture) self).textureLocation != null) {
            if (VANILLA) {
                SUPER_LOAD.invokeExact(self, resourceManager);
            } else {
                loadDefault(skin, (IResourceManager) resourceManager);
            }
        }
        if (skin.imageThread != null) {
            return;
        }
        if (VANILLA) {
            readCache(skin);
            return;
        }
        if (!IN_FLIGHT.add(self)) {
            return;
        }
        POOL.execute(
                () -> {
                    try {
                        readCache(skin);
                    } catch (Throwable e) {
                        Log.info("skins", "failed to load skin from cache: " + e);
                    } finally {
                        IN_FLIGHT.remove(self);
                    }
                });
    }

    /**
     * Equivalent of SimpleTexture.loadTexture with the decoded image reused: deletes the texture,
     * and uploads the image with blur and clamp off — what the original does when the resource has
     * no metadata.
     */
    private static void loadDefault(ThreadDownloadImageData self, IResourceManager resourceManager)
            throws Throwable {
        ResourceLocation location = self.textureLocation;
        long now = System.nanoTime();
        Decoded d = DEFAULTS.get(location);
        if (d == null || now - d.at > DEFAULT_TTL_NANOS) {
            IResource res = resourceManager.getResource(location);
            if (res.hasMetadata()) {
                // Blur/clamp come from the metadata: keeps the original path.
                SUPER_LOAD.invokeExact((Object) self, (Object) resourceManager);
                return;
            }
            try (InputStream in = res.getInputStream()) {
                d = new Decoded(toIntArgb(TextureUtil.readBufferedImage(in)), now);
            }
            DEFAULTS.put(location, d);
        }
        if (NO_SHARED) {
            self.deleteGlTexture();
            TextureUtil.uploadTextureImageAllocate(self.getGlTextureId(), d.image, false, false);
            return;
        }
        if (d.shared == 0) {
            d.shared =
                    TextureUtil.uploadTextureImageAllocate(
                            SteinGameGl.glGenTextures(), d.image, false, false);
        }
        // All the skins still waiting for the real one point to the same texture: the image is the
        // same, and uploading it 400 times when a crowd arrives cost a whole frame (pixel
        // conversion and 16 KB per skin).
        self.deleteGlTexture();
        SHARED.set(self, d.shared);
    }

    /**
     * In place of ITextureObject.getGlTextureId in TextureManager.bindTexture: while the real skin
     * has not arrived, the texture is the shared default; once it arrives, the game's path uploads
     * the real one (ThreadDownloadImageData's getGlTextureId does the pending upload).
     */
    public static int textureId(Object texture) {
        if (!VANILLA && !NO_SHARED && texture instanceof ThreadDownloadImageData) {
            int shared = (int) SHARED.get(texture);
            ThreadDownloadImageData skin = (ThreadDownloadImageData) texture;
            if (shared != 0 && !skin.textureUploaded && skin.bufferedImage == null) {
                return shared;
            }
        }
        return ((ITextureObject) texture).getGlTextureId();
    }

    /**
     * The body of ThreadDownloadImageData.checkTextureUploaded: the same upload, then the image let
     * go (see the class comment).
     */
    public static void checkUploaded(Object self) throws Throwable {
        ThreadDownloadImageData skin = (ThreadDownloadImageData) self;
        BufferedImage image = skin.bufferedImage;
        if (skin.textureUploaded || image == null) {
            return;
        }
        if (skin.textureLocation != null) {
            skin.deleteGlTexture();
        }
        TextureUtil.uploadTextureImage((int) SUPER_ID.invokeExact(self), image);
        skin.textureUploaded = true;
        if (!KEEP_IMAGE) {
            skin.bufferedImage = null;
        }
    }

    /**
     * HttpURLConnection.connect in the skin download thread (ThreadDownloadImageData$1.run): with
     * the time limits set first.
     */
    public static void connect(Object connection) throws IOException {
        HttpURLConnection c = (HttpURLConnection) connection;
        if (!KEEP_IMAGE) {
            c.setConnectTimeout(DOWNLOAD_TIMEOUT_MS);
            c.setReadTimeout(DOWNLOAD_TIMEOUT_MS);
        }
        c.connect();
    }

    /**
     * Copies pixel by pixel to TYPE_INT_ARGB. The decoded PNG comes in a generic format, and the
     * getRGB that TextureUtil calls on each upload converts each pixel through the color model: JFR
     * showed this conversion dominating the arrival spike, 400 times the same image. In INT_ARGB,
     * getRGB takes the fast path. getRGB/setRGB preserve the exact ARGB, including the RGB of
     * transparent pixels — drawImage would not.
     */
    private static BufferedImage toIntArgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) {
            return src;
        }
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, w, h, src.getRGB(0, 0, w, h, null, 0, w), 0, w);
        return out;
    }

    /**
     * The original's cache branch, including the request to the server when the file is missing or
     * fails.
     */
    private static void readCache(ThreadDownloadImageData self) {
        File cache = self.cacheFile;
        if (cache == null || !cache.isFile()) {
            self.loadTextureFromServer();
            return;
        }
        try {
            BufferedImage img = ImageIO.read(cache);
            self.bufferedImage = img;
            if (self.imageBuffer != null) {
                self.setBufferedImage(self.imageBuffer.parseUserSkin(img));
            }
        } catch (IOException e) {
            Log.info("skins", "Couldn't load skin " + cache + ": " + e);
            self.loadTextureFromServer();
        }
    }
}
