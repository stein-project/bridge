/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.IOFileFilter;

import java.io.File;

/**
 * Fix for the first server resource pack failing: the server-resource-packs folder is created
 * before the old packs in it are listed.
 *
 * <p>ResourcePackRepository.downloadResourcePack first trims the old server packs
 * (deleteOldServerResourcesPacks), which lists the folder with FileUtils.listFiles; nothing ever
 * creates the folder before the first download does, and listFiles throws on a folder that does not
 * exist. On a fresh game folder the first server that sent a pack got no pack and no answer (the
 * exception ended the task), and the player joined without it. The folder is created first, as the
 * download would have done. {@code -Dsteinbridge.opt.vanillaServerPackDir=true} brings back 1.8.9's
 * behaviour.
 */
public final class ServerPackDir {

    private ServerPackDir() {}

    private static final boolean VANILLA = NetFixOptions.VANILLA_SERVER_PACK_DIR;

    /**
     * FileUtils.listFiles(dir, files, dirs) in
     * ResourcePackRepository.deleteOldServerResourcesPacks.
     */
    public static Object listFiles(Object dir, Object files, Object dirs) {
        File folder = (File) dir;
        if (!VANILLA && !folder.isDirectory()) {
            folder.mkdirs();
        }
        return FileUtils.listFiles(folder, (IOFileFilter) files, (IOFileFilter) dirs);
    }
}
