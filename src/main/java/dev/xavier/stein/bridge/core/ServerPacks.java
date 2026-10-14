/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

/**
 * Keeps a server's resource pack request from telling the server anything about the player's
 * machine. Patcher rules in redirects-mc.tsv, inside NetHandlerPlayClient.handleResourcePack
 * (bcy.a(hd)), ResourcePackRepository.downloadResourcePack (bnm.a(String, String)) and the download
 * itself (nj$1.run):
 *
 * <ul>
 *   <li><b>level://</b>: 1.8.9 opens {@code saves/<what the server sent>} and answers "accepted" or
 *       "failed", with no check on {@code ..}. A server could ask whether any file exists on the
 *       disk. Only {@code <world>/resources.zip} under saves is accepted now, as in later versions;
 *       anything else fails exactly like a missing file.
 *   <li><b>Cache shared by every account</b>: a pack already in server-resource-packs is not
 *       downloaded again, so a server learns that this machine has seen a pack it handed out
 *       before, across accounts and IPs. The cache now only counts for packs this account has used;
 *       for any other the game downloads again, as on a clean machine.
 *   <li><b>Local addresses</b>: the pack URL could point at the player's machine or network
 *       (router, local services), and the answer tells the server whether they exist. See {@link
 *       ServerPackConnection}.
 * </ul>
 */
public final class ServerPacks {

    private static final String TAG = "SteinBridge";

    /** A name {@link File#isFile} always rejects (a NUL makes the path invalid). */
    private static final String NO_FILE = "\0";

    private static final String RESOURCES = "/resources.zip";

    private static final Object LOCK = new Object();
    private static Path usedFile;
    private static Set<String> used;

    private ServerPacks() {}

    /**
     * In place of {@code url.substring("level://".length())}: the path under saves, or a name that
     * matches no file.
     */
    public static Object levelPath(Object url, int begin) {
        String path = ((String) url).substring(begin);
        return insideSaves(path) ? path : NO_FILE;
    }

    static boolean insideSaves(String path) {
        if (!path.endsWith(RESOURCES)
                || path.startsWith("/")
                || path.startsWith("\\")
                || path.indexOf(':') >= 0
                || path.indexOf('\0') >= 0) {
            return false;
        }
        for (String part : path.split("[/\\\\]")) {
            if (part.isEmpty() || part.equals("..") || part.equals(".")) {
                return false;
            }
        }
        return true;
    }

    /**
     * In place of {@code file.exists()} on a cached server pack: true only if the file is there and
     * this account has used it before. Asking marks it as used, since the game downloads it next.
     */
    public static boolean cached(Object file) {
        File f = (File) file;
        if (!f.exists()) {
            markUsed(f.getName());
            return false;
        }
        return markUsed(f.getName());
    }

    /** In place of {@code url.openConnection(proxy)} for a server pack. */
    public static Object open(Object url, Object proxy) throws IOException {
        return new ServerPackConnection((URL) url, (Proxy) proxy, localServer());
    }

    /**
     * Whether the server sending the pack is itself on this machine or its networks: the address of
     * the open connection (server list, direct connect, the launcher's --server, LAN), not the
     * typed name.
     */
    private static boolean localServer() {
        NetHandlerPlayClient handler = Minecraft.getMinecraft().getNetHandler();
        if (handler == null) {
            return false;
        }
        SocketAddress remote = handler.getNetworkManager().getRemoteAddress();
        if (!(remote instanceof InetSocketAddress inet)) {
            return true; // the integrated server's in-memory channel
        }
        return inet.getAddress() != null && ServerPackConnection.local(inet.getAddress());
    }

    /** Records the pack for this account; true if it already was. */
    private static boolean markUsed(String pack) {
        synchronized (LOCK) {
            try {
                load();
                if (used.contains(pack)) {
                    return true;
                }
                used.add(pack);
                Files.createDirectories(usedFile.getParent());
                Files.write(usedFile, String.join("\n", used).getBytes(StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                Log.warn(TAG, "server pack cache list: " + e);
            }
            return false;
        }
    }

    private static void load() throws IOException {
        if (used != null) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        usedFile =
                mc.mcDataDir
                        .toPath()
                        .resolve("steinbridge")
                        .resolve("server-packs")
                        .resolve(account(mc.getSession().getPlayerID()) + ".txt");
        used = new HashSet<>();
        if (Files.isRegularFile(usedFile)) {
            for (String line : Files.readAllLines(usedFile, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    used.add(line.strip());
                }
            }
        }
    }

    /** A file name for the account that does not spell out its id. */
    private static String account(String id) {
        try {
            byte[] h =
                    MessageDigest.getInstance("SHA-256")
                            .digest(String.valueOf(id).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
