/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.diag;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.showcase.Showcase;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.potion.PotionEffect;

import java.util.Map;

/**
 * Diagnostic scenes that change the client's world state: test gear and enchantments on the player,
 * terrain reloads, graphics and camera settings, the F3 screen kept closed, and the NBT dump.
 *
 * <p>Each {@code ...Frame()} method is called by {@link Scenes#onFrame()} and does nothing unless
 * its {@code -Dsteinbridge.diag.*} option is set; each scene owns its option reads and state.
 */
final class DiagWorldScenes {
    private DiagWorldScenes() {}

    private static boolean nbtDone;

    /**
     * {@code -Dsteinbridge.diag.nbtAtSec=N}: prints the NBT map of some players' armor. JFR showed
     * HashMap$TreeNode inside ItemStack.isItemEnchanted (hasKey("ench")) — a tree bin, which a
     * one-key NBT should not have.
     */
    private static void dumpArmorNbt() {
        try {
            if (DiagGame.world() == null) {
                return;
            }
            int shown = 0;
            for (Entity e : DiagGame.world().loadedEntityList) {
                if (!(e instanceof EntityLivingBase) || shown >= 3) {
                    continue;
                }
                ItemStack stack = ((EntityLivingBase) e).getCurrentArmor(2);
                if (stack == null) {
                    continue;
                }
                NBTTagCompound nbt = stack.stackTagCompound;
                if (nbt == null) {
                    Log.info("diag", "nbt: armor without NBT");
                    continue;
                }
                Map<String, NBTBase> m = nbt.tagMap;
                StringBuilder keys = new StringBuilder();
                for (Object k : m.keySet()) {
                    keys.append(k)
                            .append('#')
                            .append(Integer.toHexString(k.hashCode()))
                            .append(' ');
                }
                Log.info(
                        "diag",
                        "nbt: "
                                + m.getClass().getName()
                                + " with "
                                + m.size()
                                + " keys: "
                                + keys
                                + "| map identity "
                                + System.identityHashCode(m));
                shown++;
            }
        } catch (RuntimeException e) {
            Log.info("diag", "failed reading NBT: " + e);
        }
    }

    /**
     * {@code -Dsteinbridge.diag.debugOff=true} (benchmark runs): keeps the F3 screen closed. It
     * draws text over everything and changes the frame cost; if someone presses F3 in the window
     * during the measurement, the run is no longer comparable.
     */
    private static boolean debugOffFailed;

    private static void keepDebugOff() {
        if (debugOffFailed) {
            return;
        }
        try {
            GameSettings settings = DiagGame.settings();
            if (settings != null && settings.showDebugInfo) {
                settings.showDebugInfo = false;
                Log.info("diag", "F3 screen closed (benchmark)");
            }
        } catch (RuntimeException e) {
            debugOffFailed = true;
            Log.info("diag", "could not keep F3 closed: " + e);
        }
    }

    /**
     * {@code -Dsteinbridge.diag.gearAtSec=N}: N seconds after startup, puts on the player — on the
     * client only, the server does not know — worn diamond armor (remaining durability 9, 25, 468
     * and 33: one color band per piece), a bow in hand with 87 arrows in the inventory and three
     * effects, one of them ending. HUD check without touching the server.
     */
    private static boolean gearDone;

    private static void gear() {
        try {
            EntityPlayerSP player = DiagGame.player();
            if (player == null) {
                Log.info("diag", "gear: no player");
                return;
            }
            InventoryPlayer inv = player.inventory;
            ItemStack[] main = inv.mainInventory;
            ItemStack[] armor = inv.armorInventory;
            int current = inv.currentItem;
            String[] pieces = {
                "diamond_boots", "diamond_leggings", "diamond_chestplate", "diamond_helmet"
            };
            int[] damage = {420, 470, 60, 330}; // maximums 429, 495, 528, 363
            // Protection at the level of -Dsteinbridge.diag.gearProtection (default 4): resource
            // packs choose the armor texture by enchantment and by worn durability, and without
            // an enchantment none of those rules matches. With 97%, 94%, 11% and 90% worn, the
            // pieces fall into different bands on purpose.
            int protectionLevel = DiagOptions.GEAR_PROTECTION;
            for (int i = 0; i < 4; i++) {
                ItemStack s = new ItemStack(Item.getByNameOrId(pieces[i]), 1);
                s.setItemDamage(damage[i]);
                if (protectionLevel > 0) {
                    s.addEnchantment(Enchantment.protection, protectionLevel);
                }
                armor[i] = s;
            }
            main[current] = new ItemStack(Item.getByNameOrId("bow"), 1);
            main[9] = new ItemStack(Item.getByNameOrId("arrow"), 64);
            main[10] = new ItemStack(Item.getByNameOrId("arrow"), 23);
            player.addPotionEffect(new PotionEffect(1, 90 * 20, 1)); // Speed II, 1:30
            // Strength, 0:15 (blinks in the last 10 s)
            player.addPotionEffect(new PotionEffect(5, 15 * 20, 0));
            player.addPotionEffect(new PotionEffect(10, 45 * 20, 2)); // Regeneration III, 0:45
            Log.info("diag", "test gear on the player (client only)");
        } catch (RuntimeException e) {
            Log.info("diag", "failed equipping the player: " + e);
        }
    }

    /**
     * {@code -Dsteinbridge.diag.reloadChunksEverySec=N}: every N seconds, reloads the whole terrain
     * (RenderGlobal.loadRenderers, like F3+A): all chunks are rebuilt at once, from the data
     * already on the client. With no server in the middle, the client is the bottleneck — and this
     * is where the number of build threads shows. In a profiling build, each reload is marked as
     * its own "jump".
     */
    private static long nextReload =
            DiagOptions.RELOAD_EVERY > 0
                    ? Scenes.START + DiagOptions.RELOAD_EVERY * 1_000_000_000L
                    : Long.MAX_VALUE;

    private static void reloadChunks() {
        try {
            if (DiagGame.world() == null) {
                return;
            }
            DiagGame.mc().renderGlobal.loadRenderers();
            Log.info("diag", "terrain reloaded");
        } catch (RuntimeException e) {
            Log.info("diag", "failed reloading the terrain: " + e);
            nextReload = Long.MAX_VALUE;
        }
    }

    /**
     * {@code -Dsteinbridge.diag.graphicsAtSec=S:1,S:0...}: at S seconds, Fancy (1) or Fast (0)
     * graphics, like the Video Settings button: changes GameSettings.fancyGraphics and reloads the
     * terrain (RenderGlobal.loadRenderers).
     */
    private static int graphicsNext;

    private static void graphics(long elapsed) {
        String[] p = DiagOptions.GRAPHICS[graphicsNext].split(":");
        if (elapsed < (long) (Double.parseDouble(p[0]) * 1_000_000_000L)) {
            return;
        }
        graphicsNext++;
        // loadRenderers reads fancyGraphics and switches the leaf level.
        DiagGame.settings().fancyGraphics = !p[1].equals("0");
        DiagGame.mc().renderGlobal.loadRenderers();
        Log.info(
                "diag",
                "graphics " + (p[1].equals("0") ? "Fast" : "Fancy") + " (terrain reloaded)");
    }

    /**
     * {@code -Dsteinbridge.diag.citAtSec=N}: N seconds after startup, puts on the player — on the
     * client only — a diamond sword with Sharpness 10 in hand, diamond armor with Protection 10
     * and, in the inventory, swords with Sharpness 6 to 9, one without an enchantment and pieces
     * with Protection 5 and 6: a check of a resource pack's textures by enchantment (CIT). With
     * inventoryAtSec afterwards, the inventory screen shows the icons and the dressed player.
     */
    private static boolean citDone;

    private static void cit() {
        try {
            EntityPlayerSP player = DiagGame.player();
            if (player == null) {
                Log.info("diag", "cit: no player");
                return;
            }
            InventoryPlayer inv = player.inventory;
            ItemStack[] main = inv.mainInventory;
            ItemStack[] armor = inv.armorInventory;
            int current = inv.currentItem;
            Enchantment sharpness = Enchantment.getEnchantmentById(16);
            Enchantment protection = Enchantment.getEnchantmentById(0);
            String[] pieces = {
                "diamond_boots", "diamond_leggings", "diamond_chestplate", "diamond_helmet"
            };
            for (int i = 0; i < 4; i++) {
                ItemStack s = new ItemStack(Item.getByNameOrId(pieces[i]), 1);
                s.addEnchantment(protection, 10);
                armor[i] = s;
                ItemStack p5 = new ItemStack(Item.getByNameOrId(pieces[i]), 1);
                p5.addEnchantment(protection, 5);
                main[18 + i] = p5;
                ItemStack p6 = new ItemStack(Item.getByNameOrId(pieces[i]), 1);
                p6.addEnchantment(protection, 6);
                main[27 + i] = p6;
            }
            ItemStack sword = new ItemStack(Item.getByNameOrId("diamond_sword"), 1);
            sword.addEnchantment(sharpness, 10);
            main[current] = sword;
            for (int lvl = 6; lvl <= 9; lvl++) {
                ItemStack s = new ItemStack(Item.getByNameOrId("diamond_sword"), 1);
                s.addEnchantment(sharpness, lvl);
                main[9 + lvl - 6] = s;
            }
            main[13] = new ItemStack(Item.getByNameOrId("diamond_sword"), 1);
            Log.info("diag", "cit: enchanted swords and armor on the player (client only)");
        } catch (RuntimeException e) {
            Log.info("diag", "failed in the cit scene: " + e);
        }
    }

    /**
     * {@code -Dsteinbridge.diag.thirdPerson=1|2} (visual check): third-person camera, from behind
     * (1) or from the front (2), kept every frame — the player's own shadow, the pose.
     */
    private static boolean thirdPersonFailed;

    private static void keepThirdPerson() {
        if (thirdPersonFailed) {
            return;
        }
        try {
            GameSettings settings = DiagGame.settings();
            if (settings != null && settings.thirdPersonView != DiagOptions.THIRD_PERSON) {
                settings.thirdPersonView = DiagOptions.THIRD_PERSON;
                Log.info("diag", "third person " + DiagOptions.THIRD_PERSON);
            }
        } catch (RuntimeException e) {
            thirdPersonFailed = true;
            Log.info("diag", "could not turn on third person: " + e);
        }
    }

    // ---- per-frame entry points, called by Scenes.onFrame() in that order

    static void keepDebugOffFrame() {
        if (DiagOptions.DEBUG_OFF) {
            keepDebugOff();
        }
    }

    static void thirdPersonFrame() {
        if (DiagOptions.THIRD_PERSON > 0) {
            keepThirdPerson();
        }
        if (DiagOptions.PITCH > -1000) {
            EntityPlayerSP p = net.minecraft.client.Minecraft.getMinecraft().thePlayer;
            if (p != null) {
                p.rotationPitch = DiagOptions.PITCH;
                p.prevRotationPitch = DiagOptions.PITCH;
                if (DiagOptions.YAW > -1000) {
                    p.rotationYaw = DiagOptions.YAW;
                    p.prevRotationYaw = DiagOptions.YAW;
                }
            }
        }
    }

    private static boolean showcaseOpened;

    /**
     * The showcase scenes ({@link DiagOptions#SHOWCASE_AT}, {@link DiagOptions#SHOWCASE_NIGHT_AT}).
     */
    static void showcaseFrame() {
        if (DiagOptions.SHOWCASE_AT < 0 && DiagOptions.SHOWCASE_NIGHT_AT < 0) {
            return;
        }
        long sec = Scenes.elapsed() / 1_000_000_000L;
        if (DiagOptions.SHOWCASE_AT >= 0 && !showcaseOpened && sec >= DiagOptions.SHOWCASE_AT) {
            Showcase.open();
            showcaseOpened = Showcase.active();
            if (showcaseOpened) {
                Log.info("diag", "showcase open");
            }
        }
        long n = DiagOptions.SHOWCASE_NIGHT_AT;
        // also with the showcase opened by the video settings (videoAtSec)
        if (Showcase.active() && n > 0 && sec >= n) {
            boolean night = (sec / n) % 2 == 1;
            if (night != Showcase.night()) {
                Showcase.setNight(night);
                Log.info("diag", "showcase " + (night ? "night" : "day"));
            }
        }
    }

    static void graphicsFrame() {
        if (graphicsNext < DiagOptions.GRAPHICS.length) {
            try {
                graphics(Scenes.elapsed());
            } catch (RuntimeException e) {
                graphicsNext++;
                Log.info("diag", "failed switching the graphics: " + e);
            }
        }
    }

    static void citFrame() {
        if (DiagOptions.CIT_AT >= 0
                && !citDone
                && Scenes.elapsed() >= DiagOptions.CIT_AT * 1_000_000_000L) {
            citDone = true;
            cit();
        }
    }

    static void reloadChunksFrame() {
        long now = System.nanoTime();
        if (now >= nextReload) {
            nextReload = now + DiagOptions.RELOAD_EVERY * 1_000_000_000L;
            reloadChunks();
        }
    }

    static void gearFrame() {
        if (DiagOptions.GEAR_AT >= 0
                && !gearDone
                && Scenes.elapsed() >= DiagOptions.GEAR_AT * 1_000_000_000L) {
            gearDone = true;
            gear();
        }
    }

    static void nbtFrame() {
        if (DiagOptions.NBT_AT >= 0
                && !nbtDone
                && Scenes.elapsed() >= DiagOptions.NBT_AT * 1_000_000_000L) {
            nbtDone = true;
            dumpArmorNbt();
        }
    }
}
