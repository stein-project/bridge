/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.engine;

import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;

/**
 * Fixes in the client's handling of the team packet (S3EPacketTeams, NetHandlerPlayClient
 * .handleTeams), which minigame and PvP servers send all the time for the name colours, prefixes
 * and the sidebar.
 *
 * <p>1.8.9 trusts the server's order of team packets, and three cases threw, losing the whole
 * packet (the exception ends in "Error executing task"), so a team kept the wrong colour or prefix
 * or a player stayed on a team they had left, until the server happened to send it again:
 *
 * <ul>
 *   <li>creating a team that already exists (servers and proxies recreate teams on a world or
 *       server change): Scoreboard.createTeam threw IllegalArgumentException. Here the existing
 *       team is taken and the packet goes on as an update of it, as later versions do;
 *   <li>updating, filling or removing a team that does not exist: getTeam returned null and the
 *       handler threw a NullPointerException. Here the handler gets a team of its own that is not
 *       on the scoreboard, so the packet changes nothing, as later versions skip it;
 *   <li>removing a player from a team they are not on (they moved to another team in between):
 *       removePlayerFromTeam threw IllegalStateException, and the other players of the same packet
 *       stayed on the team. Here that one player is passed over and the others are removed.
 * </ul>
 *
 * Each case writes one log line (collapsed when repeated). The patcher sends the three calls of
 * handleTeams here (redirects-mc.tsv); {@code -Dsteinbridge.opt.vanillaTeams=true} brings back
 * 1.8.9's behaviour. Only the client's scoreboard is touched: nothing is sent to the server.
 */
public final class TeamPackets {

    private TeamPackets() {}

    private static final boolean VANILLA = NetFixOptions.VANILLA_TEAMS;

    private static final RepeatLog LOG = new RepeatLog("teams", 10);

    /** Scoreboard.createTeam(name) in handleTeams (action 0, create). */
    public static Object create(Object scoreboard, Object name) {
        Scoreboard board = (Scoreboard) scoreboard;
        String team = (String) name;
        if (!VANILLA) {
            ScorePlayerTeam existing = board.getTeam(team);
            if (existing != null) {
                LOG.note("team " + team + " created again by the server: updated instead");
                return existing;
            }
        }
        return board.createTeam(team);
    }

    /** Scoreboard.getTeam(name) in handleTeams (the other actions). */
    public static Object team(Object scoreboard, Object name) {
        Scoreboard board = (Scoreboard) scoreboard;
        String team = (String) name;
        ScorePlayerTeam found = board.getTeam(team);
        if (found != null || VANILLA) {
            return found;
        }
        LOG.note("packet for team " + team + ", which does not exist: ignored");
        // Not put on the scoreboard: the setters only change this object, and removeTeam with it
        // removes nothing from the scoreboard's maps (the client scoreboard has no listeners).
        return new ScorePlayerTeam(board, team);
    }

    /** Scoreboard.removePlayerFromTeam(player, team) in handleTeams (action 4). */
    public static void removeMember(Object scoreboard, Object player, Object team) {
        Scoreboard board = (Scoreboard) scoreboard;
        String name = (String) player;
        ScorePlayerTeam from = (ScorePlayerTeam) team;
        if (!VANILLA && board.getPlayersTeam(name) != from) {
            LOG.note(
                    "player "
                            + name
                            + " removed from team "
                            + from.getRegisteredName()
                            + ", which they are not on: passed over");
            return;
        }
        board.removePlayerFromTeam(name, from);
    }
}
