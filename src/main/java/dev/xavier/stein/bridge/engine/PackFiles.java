/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.DesktopOpen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.ResourcePackRepository;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.List;

/**
 * The resource pack screen's folder button and links, and the zip files of the packs left out.
 *
 * <ul>
 *   <li>GuiScreenResourcePacks' "Open resource pack folder" on Linux, and GuiScreen.openWebLink
 *       (chat links, the links of the game's screens) everywhere, call Desktop.getDesktop() and
 *       browse() by reflection on the game thread: the window froze while AWT started, and on Linux
 *       without GTK (KDE, Wayland) nothing opened. Here those two reflective calls go through
 *       {@link DesktopOpen} instead (xdg-open first on Linux, off the game thread): getDesktop is
 *       not called at all, and browse hands its URI over. {@code
 *       -Dsteinbridge.opt.vanillaOpenFolder=true} brings back the game's calls.
 *   <li>A pack's zip, opened while the pack was in use, stayed open after the player took it off
 *       the selected list, until the game closed: on Windows the file could not be moved, renamed
 *       or deleted ("in use by another program"). Here, when the screen's Done applies the list
 *       (ResourcePackRepository.setRepositories), every pack that is not selected closes its file
 *       (the game's own closeResourcePack); it opens again by itself if selected later. {@code
 *       -Dsteinbridge.opt.vanillaPackFiles=true} keeps them open.
 * </ul>
 */
public final class PackFiles {

    private PackFiles() {}

    private static final boolean VANILLA_OPEN = GuiFixOptions.VANILLA_OPEN_FOLDER;
    private static final boolean VANILLA_FILES = GuiFixOptions.VANILLA_PACK_FILES;

    /**
     * In place of Method.invoke in GuiScreenResourcePacks.actionPerformed and
     * GuiScreen.openWebLink, whose only reflective calls are Desktop.getDesktop() and
     * Desktop.browse(uri).
     */
    public static Object invoke(Object methodObject, Object receiver, Object argsObject)
            throws ReflectiveOperationException {
        Method method = (Method) methodObject;
        Object[] args = (Object[]) argsObject;
        if (VANILLA_OPEN) {
            return method.invoke(receiver, args);
        }
        switch (method.getName()) {
            case "getDesktop":
                return null; // browse below does not need it
            case "browse":
                if (args != null && args.length == 1 && args[0] instanceof URI uri) {
                    DesktopOpen.open(openTarget(uri));
                    return null;
                }
                break;
            default:
                break;
        }
        return method.invoke(receiver, args);
    }

    /** A local folder as its path (what xdg-open and Desktop.open take), a link as it is. */
    private static String openTarget(URI uri) {
        if ("file".equals(uri.getScheme())) {
            try {
                return new java.io.File(uri).getPath();
            } catch (IllegalArgumentException e) {
                return uri.toString();
            }
        }
        return uri.toString();
    }

    /** After ResourcePackRepository.setRepositories in the screen's Done: closes the others. */
    public static void closeUnselected() {
        if (VANILLA_FILES) {
            return;
        }
        ResourcePackRepository repository = Minecraft.getMinecraft().getResourcePackRepository();
        List<ResourcePackRepository.Entry> selected = repository.repositoryEntries;
        for (ResourcePackRepository.Entry entry : repository.repositoryEntriesAll) {
            if (!selected.contains(entry)) {
                entry.closeResourcePack();
            }
        }
    }
}
