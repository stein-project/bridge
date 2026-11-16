/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import dev.xavier.stein.bridge.core.Log;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.base64.Base64;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiScreenAddServer;
import net.minecraft.client.gui.GuiTextField;

import java.net.IDN;
import java.util.List;

/**
 * Fixes for crashes of the multiplayer screen and of server addresses: a bad server icon, a stale
 * list index, an address made only of colons, an address IDN rejects, and addresses saved with
 * spaces around them.
 *
 * <ul>
 *   <li>ServerListEntryNormal.prepareServerIcon decodes the server's Base64 favicon before its try
 *       block: a favicon with a character outside Base64 (any server can send one in its status)
 *       threw IllegalArgumentException on the game thread and closed the game from the server list.
 *       {@link #iconData} turns a failed decoding into an empty image, which the game's own try
 *       then rejects as it rejects any broken image: it logs "Invalid icon for server", clears the
 *       icon and the entry shows the default one.
 *   <li>ServerSelectionList.getListEntry reads the LAN list without checking the index, and the
 *       screen keeps a selected index across a LAN list that shrank (a LAN world closed): the next
 *       key or button press threw IndexOutOfBoundsException. {@link #listEntry} answers null for an
 *       index outside the list, which the screen's callers already take as "nothing selected".
 *   <li>ServerAddress.fromString(":") (or "::") reads the first piece of an empty split and threw
 *       ArrayIndexOutOfBoundsException when connecting. {@link #splitAddress} hands back the whole
 *       text as the host in that case, so the connection fails as for any unknown host.
 *   <li>ServerAddress.getIP passes the host to IDN.toASCII, which throws IllegalArgumentException
 *       on a label it rejects (longer than 63 characters, for example): {@link #asciiHost} keeps
 *       the host as typed then, and the lookup reports it as an unknown host.
 *   <li>The add/edit server screen and direct connect save the address as typed, spaces included,
 *       and " play.example.net" then never resolves. {@link #typedAddress} and {@link
 *       #directAddress} trim the address when it is saved (the server name is kept as typed);
 *       addresses saved before are trimmed on lookup by {@link NetAddress#getByName}.
 * </ul>
 */
public final class ServerListFixes {

    private ServerListFixes() {}

    /** Base64.decode(buffer) in ServerListEntryNormal.prepareServerIcon. */
    public static Object iconData(Object base64) {
        try {
            return Base64.decode((ByteBuf) base64);
        } catch (RuntimeException e) {
            Log.info("servers", "server icon is not valid Base64, using the default: " + e);
            return Unpooled.buffer(0);
        }
    }

    /** List.get(index) in ServerSelectionList.getListEntry: null outside the list. */
    public static Object listEntry(Object list, int index) {
        List<?> entries = (List<?>) list;
        return index >= 0 && index < entries.size() ? entries.get(index) : null;
    }

    /** String.split(":") in ServerAddress.fromString: never an empty array. */
    public static Object splitAddress(Object address, Object separator) {
        String text = (String) address;
        String[] parts = text.split((String) separator);
        return parts.length == 0 ? new String[] {text} : parts;
    }

    /** IDN.toASCII(host) in ServerAddress.getIP: the host as typed when IDN rejects it. */
    public static Object asciiHost(Object host) {
        try {
            return IDN.toASCII((String) host);
        } catch (IllegalArgumentException e) {
            return host;
        }
    }

    /**
     * GuiTextField.getText in GuiScreenAddServer.actionPerformed: the address field's text trimmed,
     * the name field's as typed.
     */
    public static Object typedAddress(Object field) {
        GuiTextField f = (GuiTextField) field;
        GuiScreen screen = Minecraft.getMinecraft().currentScreen;
        if (screen instanceof GuiScreenAddServer
                && ((GuiScreenAddServer) screen).serverIPField == f) {
            return f.getText().trim();
        }
        return f.getText();
    }

    /** GuiTextField.getText in GuiScreenServerList.actionPerformed (only the address field). */
    public static Object directAddress(Object field) {
        return ((GuiTextField) field).getText().trim();
    }
}
