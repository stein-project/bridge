/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.diag;

import com.mojang.authlib.GameProfile;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.engine.AsyncSkins;
import dev.xavier.stein.bridge.engine.CloseProjectiles;
import dev.xavier.stein.bridge.engine.LanguageReload;
import dev.xavier.stein.bridge.engine.QueuedPackets;
import dev.xavier.stein.bridge.engine.WorldLeaks;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.renderer.ThreadDownloadImageData;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.client.renderer.tileentity.TileEntityBannerRenderer;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.resources.Language;
import net.minecraft.entity.monster.EntityZombie;
import net.minecraft.entity.projectile.EntitySnowball;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.server.S23PacketBlockChange;
import net.minecraft.network.play.server.S2EPacketCloseWindow;
import net.minecraft.network.play.server.S3EPacketTeams;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.tileentity.TileEntityBanner;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.storage.RegionFileCache;

import org.apache.commons.io.FileUtils;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URL;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/**
 * The checks of {@link DiagFixChecks} that run in one go: each builds the situation that showed a
 * 1.8.9 bug, goes through the patched game code where it can (the handler, the texture manager, the
 * vertex builder) and logs whether the fix held. Diagnostic only: nothing here runs unless the
 * scene is on.
 */
final class DiagFixUnits {

    private DiagFixUnits() {}

    /** One check's body; a throw counts as a FAIL with the exception. */
    private interface Check {
        void run() throws Throwable;
    }

    static void result(String name, boolean ok, String detail) {
        Log.info("fixcheck", name + (ok ? " ok: " : " FAIL: ") + detail);
    }

    private static void check(String name, Check c) {
        try {
            c.run();
        } catch (Throwable t) {
            result(name, false, "threw " + t);
        }
    }

    static void runAll(Minecraft mc) {
        check("teams", () -> teams(mc));
        check("worldlessPackets", () -> worldless(mc));
        check("joinTitles", () -> titles(mc));
        check("builderGrowth", DiagFixUnits::builder);
        check("closeProjectiles", () -> projectiles(mc));
        check("unreadableImages", () -> images(mc));
        check("nbtNull", DiagFixUnits::nbtNull);
        check("serverPackDir", () -> packDir(mc));
        check("skinImage", () -> skinImage(mc));
        check("chunkStream", () -> chunkStream(mc));
        check("pathfinderArea", () -> pathfinder(mc));
        check("digits", DiagFixUnits::digits);
        check("worldGcCost", DiagFixUnits::gcCost);
        check("languageReload", () -> language(mc));
    }

    // ---- teams ----

    private static void teams(Minecraft mc) {
        NetHandlerPlayClient handler = mc.getNetHandler();
        Scoreboard board = mc.theWorld.getScoreboard();
        Scoreboard scratch = new Scoreboard();
        ScorePlayerTeam a = scratch.createTeam("fxA");
        ScorePlayerTeam b = scratch.createTeam("fxB");
        ScorePlayerTeam none = scratch.createTeam("fxNone");
        a.setNamePrefix("[A]");
        StringBuilder failed = new StringBuilder();
        scratch.addPlayerToTeam("fxPlayer", "fxA");
        team(handler, new S3EPacketTeams(a, 0), "create", failed);
        team(handler, new S3EPacketTeams(a, 0), "create again", failed);
        team(handler, new S3EPacketTeams(none, 2), "update missing", failed);
        scratch.addPlayerToTeam("fxPlayer", "fxB");
        team(handler, new S3EPacketTeams(b, 0), "move", failed);
        team(handler, new S3EPacketTeams(a, Arrays.asList("fxPlayer"), 4), "stale remove", failed);
        team(handler, new S3EPacketTeams(none, 1), "remove missing", failed);
        ScorePlayerTeam onB = board.getPlayersTeam("fxPlayer");
        boolean ok =
                failed.length() == 0
                        && board.getTeam("fxA") != null
                        && onB != null
                        && "fxB".equals(onB.getRegisteredName());
        result("teams", ok, failed.length() == 0 ? "all packets applied" : failed.toString());
        for (String t : new String[] {"fxA", "fxB"}) {
            ScorePlayerTeam left = board.getTeam(t);
            if (left != null) {
                board.removeTeam(left);
            }
        }
    }

    private static void team(
            NetHandlerPlayClient handler, S3EPacketTeams p, String what, StringBuilder failed) {
        try {
            handler.handleTeams(p);
        } catch (Throwable t) {
            failed.append(what).append(" threw ").append(t).append("; ");
        }
    }

    // ---- packets with no world ----

    private static void worldless(Minecraft mc) {
        NetworkManager net = new NetworkManager(EnumPacketDirection.CLIENTBOUND);
        NetHandlerPlayClient fresh =
                new NetHandlerPlayClient(
                        mc, null, net, new GameProfile(UUID.randomUUID(), "fxNoWorld"));
        S23PacketBlockChange p = new S23PacketBlockChange(mc.theWorld, new BlockPos(0, 64, 0));
        try {
            QueuedPackets.process(p, fresh);
            result("worldlessPackets", true, "block change before the join left out");
        } catch (NullPointerException e) {
            result("worldlessPackets", false, "block change before the join threw " + e);
        }
    }

    // ---- title cleared on join (title set by DiagFixChecks before the world opened) ----

    private static void titles(Minecraft mc) {
        String title = mc.ingameGUI.displayedTitle;
        if (DiagOptions.FIX_TITLE_AT < 0) {
            Log.info("fixcheck", "joinTitles skipped: no -Dsteinbridge.diag.fixTitleAtSec");
            return;
        }
        result("joinTitles", title.isEmpty(), "title after the join: \"" + title + "\"");
    }

    // ---- vertex builder ----

    private static void builder() {
        WorldRenderer b = new WorldRenderer(256);
        b.begin(7, DefaultVertexFormats.POSITION);
        try {
            for (int i = 0; i < 400; i++) {
                b.pos(i, 0, 0).endVertex();
            }
            result(
                    "builderGrowth",
                    true,
                    "400 vertices in a 256-int builder, now "
                            + b.getByteBuffer().capacity()
                            + " B");
        } catch (IndexOutOfBoundsException e) {
            result("builderGrowth", false, "400 vertices in a 256-int builder threw " + e);
        } finally {
            b.finishDrawing();
        }
    }

    // ---- thrown items near the camera ----

    private static void projectiles(Minecraft mc) {
        EntitySnowball ball = new EntitySnowball(mc.theWorld, mc.thePlayer);
        Object render = mc.getRenderManager().getEntityRenderObject(ball);
        boolean young = CloseProjectiles.hidden(render, ball);
        ball.ticksExisted = 2;
        boolean older = CloseProjectiles.hidden(render, ball);
        result(
                "closeProjectiles",
                young && !older,
                "hidden at tick 0: " + young + ", at tick 2: " + older);
    }

    // ---- unreadable image as a texture ----

    private static void images(Minecraft mc) {
        ResourceLocation notAnImage = new ResourceLocation("sounds.json");
        try {
            boolean loaded =
                    mc.getTextureManager()
                            .loadTexture(
                                    new ResourceLocation("steinfix", "unreadable"),
                                    new SimpleTexture(notAnImage));
            result("unreadableImages", true, "json as a texture: loaded=" + loaded + ", no crash");
        } catch (RuntimeException e) {
            result("unreadableImages", false, "json as a texture: " + e);
        }
    }

    // ---- null NBT value ----

    private static void nbtNull() throws IOException {
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setTag("fxNull", null);
        nbt.setString("fxKept", "x");
        try {
            CompressedStreamTools.write(nbt, new DataOutputStream(new ByteArrayOutputStream()));
            result("nbtNull", true, "compound with a null put written, keys " + nbt.getKeySet());
        } catch (NullPointerException e) {
            result("nbtNull", false, "writing a compound with a null put threw " + e);
        }
    }

    // ---- server resource pack folder ----

    private static void packDir(Minecraft mc) throws IOException {
        File dir = mc.getResourcePackRepository().dirServerResourcepacks;
        FileUtils.deleteDirectory(dir);
        try {
            mc.getResourcePackRepository().deleteOldServerResourcesPacks();
            result("serverPackDir", dir.isDirectory(), "old packs trimmed with no folder");
        } catch (IllegalArgumentException e) {
            result("serverPackDir", false, "trimming old packs with no folder threw " + e);
        }
    }

    // ---- skin image released after upload ----

    private static void skinImage(Minecraft mc) throws IOException {
        ResourceLocation loc = new ResourceLocation("steinfix", "skin");
        ThreadDownloadImageData skin =
                new ThreadDownloadImageData(
                        null,
                        "http://127.0.0.1:9/none.png",
                        DefaultPlayerSkin.getDefaultSkinLegacy(),
                        null);
        mc.getTextureManager().loadTexture(loc, skin);
        skin.setBufferedImage(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB));
        mc.getTextureManager().bindTexture(loc);
        boolean released = skin.bufferedImage == null && skin.textureUploaded;
        int id = skin.getGlTextureId();
        skin.loadTexture(mc.getResourceManager());
        boolean sameAfterReload = skin.getGlTextureId() == id && AsyncSkins.textureId(skin) == id;
        result(
                "skinImage",
                released && sameAfterReload,
                "image after upload: "
                        + (skin.bufferedImage == null ? "released" : "kept")
                        + ", texture after a reload: "
                        + (sameAfterReload ? "the uploaded one" : "changed"));
    }

    // ---- skin download time limit ----

    private static volatile String timeoutOutcome;
    private static ServerSocket silent;

    static void skinTimeoutStart() {
        try {
            silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            result("skinTimeout", false, "no local socket: " + e);
            return;
        }
        Thread t =
                new Thread(
                        () -> {
                            long start = System.nanoTime();
                            try {
                                URL url =
                                        new URL("http://127.0.0.1:" + silent.getLocalPort() + "/");
                                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                                AsyncSkins.connect(c);
                                c.getResponseCode();
                                timeoutOutcome = "answered";
                            } catch (IOException e) {
                                timeoutOutcome =
                                        "gave up after "
                                                + (System.nanoTime() - start) / 1_000_000L
                                                + " ms: "
                                                + e;
                            }
                        },
                        "fixcheck skin timeout");
        t.setDaemon(true);
        t.start();
    }

    static void skinTimeoutCheck() {
        String outcome = timeoutOutcome;
        result(
                "skinTimeout",
                outcome != null,
                outcome != null ? outcome : "still waiting on a silent server after 11 s");
    }

    // ---- chunk stream closed ----

    private static void chunkStream(Minecraft mc) throws Exception {
        IntegratedServer server = mc.getIntegratedServer();
        File dir = server.worldServers[0].getSaveHandler().getWorldDirectory();
        int cx = mc.thePlayer.chunkCoordX;
        int cz = mc.thePlayer.chunkCoordZ;
        DataInputStream in = RegionFileCache.getChunkInputStream(dir, cx, cz);
        if (in == null) {
            Log.info("fixcheck", "chunkStream skipped: the player's chunk is not saved yet");
            return;
        }
        WorldLeaks.readChunk(in);
        boolean closed;
        try {
            in.read();
            closed = false;
        } catch (IOException e) {
            closed = true;
        }
        result(
                "chunkStream",
                closed,
                "stream after reading the chunk: " + (closed ? "closed" : "open"));
    }

    // ---- path finder area ----

    private static void pathfinder(Minecraft mc) throws Exception {
        IntegratedServer server = mc.getIntegratedServer();
        WorldServer world = server.worldServers[0];
        double x = mc.thePlayer.posX;
        double y = mc.thePlayer.posY;
        double z = mc.thePlayer.posZ;
        Object kept =
                server.callFromMainThread(
                                () -> {
                                    EntityZombie zombie = new EntityZombie(world);
                                    zombie.setPosition(x, y, z);
                                    zombie.onGround = true;
                                    zombie.getNavigator()
                                            .getPathToPos(new BlockPos(x + 4, y, z + 4));
                                    Object area =
                                            zombie.getNavigator()
                                                    .pathFinder
                                                    .nodeProcessor
                                                    .blockaccess;
                                    return area == null ? "nothing" : area.getClass().getName();
                                })
                        .get();
        result("pathfinderArea", "nothing".equals(kept), "processor keeps after a search: " + kept);
    }

    // ---- digits ----

    private static void digits() {
        String formatted = String.format("%d %.1f", 1234, 2.5);
        boolean latin = formatted.chars().allMatch(c -> c < 128);
        result(
                "digits",
                latin,
                "format locale "
                        + Locale.getDefault(Locale.Category.FORMAT)
                        + " writes \""
                        + formatted
                        + "\"");
    }

    // ---- System.gc cost on this heap ----

    private static void gcCost() {
        long start = System.nanoTime();
        System.gc();
        long ms = (System.nanoTime() - start) / 1_000_000L;
        Runtime r = Runtime.getRuntime();
        Log.info(
                "fixcheck",
                "worldGcCost: one System.gc() on the game thread took "
                        + ms
                        + " ms (heap "
                        + (r.totalMemory() - r.freeMemory()) / (1024 * 1024)
                        + " MB used)");
    }

    // ---- language change ----

    private static void language(Minecraft mc) {
        String before = mc.gameSettings.language;
        String other = "en_US".equals(before) ? "pt_BR" : "en_US";
        long full = switchLanguage(mc, other, true);
        String translated = I18n.format("gui.done");
        long only = switchLanguage(mc, before, false);
        String back = I18n.format("gui.done");
        Log.info(
                "fixcheck",
                "languageReload: refreshResources took "
                        + full
                        + " ms, the language screen's reload took "
                        + only
                        + " ms (\""
                        + translated
                        + "\" -> \""
                        + back
                        + "\")");
        result(
                "languageReload",
                !translated.equals(back),
                "translations follow the language with the screen's reload");
    }

    private static long switchLanguage(Minecraft mc, String code, boolean fullReload) {
        Language lang =
                mc.getLanguageManager().getLanguages().stream()
                        .filter(l -> l.getLanguageCode().equals(code))
                        .findFirst()
                        .orElseThrow();
        long start = System.nanoTime();
        mc.getLanguageManager().setCurrentLanguage(lang);
        mc.gameSettings.language = code;
        if (fullReload) {
            mc.refreshResources();
        } else {
            LanguageReload.languageChosen(mc);
        }
        return (System.nanoTime() - start) / 1_000_000L;
    }

    // ---- banner cache ----

    private static int bannerSerial;

    /** Fills the banner cache with 256 designs. */
    static void bannersFill() {
        check(
                "bannerCache",
                () -> Log.info("fixcheck", "bannerCache: " + designs(256) + "/256 drawn"));
    }

    /** More than a second later: new designs must still be drawn. */
    static void bannersAfterSecond() {
        check(
                "bannerCache",
                () -> {
                    int drawn = designs(20);
                    result(
                            "bannerCache",
                            drawn == 20,
                            drawn + "/20 new designs drawn with the cache full");
                });
    }

    private static int designs(int count) {
        TileEntityBannerRenderer r =
                (TileEntityBannerRenderer)
                        TileEntityRendererDispatcher.instance
                                .<TileEntityBanner>getSpecialRendererByClass(
                                        TileEntityBanner.class);
        TileEntityBanner.EnumBannerPattern[] patterns = TileEntityBanner.EnumBannerPattern.values();
        int drawn = 0;
        for (int i = 0; i < count; i++) {
            int n = bannerSerial++;
            NBTTagCompound nbt = new NBTTagCompound();
            nbt.setInteger("x", 0);
            nbt.setInteger("y", 0);
            nbt.setInteger("z", 0);
            nbt.setInteger("Base", n % 16);
            NBTTagList list = new NBTTagList();
            NBTTagCompound layer = new NBTTagCompound();
            layer.setString(
                    "Pattern", patterns[1 + (n / 16) % (patterns.length - 1)].getPatternID());
            layer.setInteger("Color", (n / 16 / (patterns.length - 1)) % 16);
            list.appendTag(layer);
            nbt.setTag("Patterns", list);
            TileEntityBanner banner = new TileEntityBanner();
            banner.readFromNBT(nbt);
            if (r.func_178463_a(banner) != null) {
                drawn++;
            }
        }
        return drawn;
    }

    // ---- chat left open by a server's close window ----

    static void chatClose(Minecraft mc) {
        check(
                "chatClose",
                () -> {
                    mc.getNetHandler().handleCloseWindow(new S2EPacketCloseWindow(0));
                    boolean open = mc.currentScreen instanceof GuiChat;
                    result(
                            "chatClose",
                            open,
                            "chat after the server closed a window: " + (open ? "open" : "closed"));
                });
    }
}
