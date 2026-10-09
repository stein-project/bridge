/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import com.mojang.authlib.GameProfile;

import dev.xavier.stein.bridge.engine.SignText;

import net.minecraft.block.Block;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.tileentity.TileEntityEnderChest;
import net.minecraft.tileentity.TileEntitySign;
import net.minecraft.tileentity.TileEntitySkull;
import net.minecraft.util.IChatComponent;

/**
 * The key of the tile entity being rendered: everything its (allow-listed) game renderer reads,
 * built into reusable buffers, plus the classification of which blocks are retained at all.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinTileKey {
    private SteinTileKey() {}

    static final int CHEST = 1;
    static final int ENDER = 2;
    static final int SIGN = 3;
    static final int SKULL = 4;
    static final String[] KIND_NAMES = {"?", "chest", "ender chest", "sign", "skull"};

    static final long[] keyBuf = new long[16];
    static final Object[] refBuf = new Object[8];
    static int keyN;
    static int refN;

    static void putKey(long v) {
        keyBuf[keyN++] = v;
    }

    static void putRef(Object o) {
        refBuf[refN++] = o;
    }

    /**
     * Builds the key of block {@code te} (type {@code kind}) with everything its renderer reads;
     * false = this block is not retained right now (left to the game). The calls are the same ones
     * the renderer makes, in the same order (metadata cache, chest neighbours): repeating them
     * afterwards changes nothing.
     */
    static boolean key(int kind, Object te, float partial, int light) {
        keyN = 0;
        refN = 0;
        putKey(kind);
        putKey(light);
        TileEntity tile = (TileEntity) te;
        switch (kind) {
            case CHEST -> {
                if (!tile.hasWorldObj()) {
                    return false;
                }
                Block block = tile.getBlockType();
                int meta = tile.getBlockMetadata();
                if (meta == 0 && SteinTileGame.BLOCK_CHEST.isInstance(block)) {
                    // The renderer fixes the orientation in the world (checkForSurroundingChests)
                    return false;
                }
                TileEntityChest chest = (TileEntityChest) tile;
                chest.checkForAdjacentChests();
                putKey(meta);
                putKey(chest.getChestType());
                putKey(Float.floatToRawIntBits(chestLid(chest, partial)));
                putRef(block);
                for (int i = 0; i < 4; i++) {
                    TileEntityChest nb =
                            switch (i) {
                                case 0 -> chest.adjacentChestZNeg;
                                case 1 -> chest.adjacentChestXPos;
                                case 2 -> chest.adjacentChestXNeg;
                                default -> chest.adjacentChestZPos;
                            };
                    putRef(nb);
                    putKey(
                            nb == null
                                    ? 0L
                                    : 1L << 32
                                            | Float.floatToRawIntBits(chestLid(nb, partial))
                                                    & 0xFFFFFFFFL);
                }
            }
            case ENDER -> {
                if (!tile.hasWorldObj()) {
                    return false;
                }
                putKey(tile.getBlockMetadata());
                TileEntityEnderChest ender = (TileEntityEnderChest) tile;
                float prev = ender.prevLidAngle;
                float lid = ender.lidAngle;
                putKey(Float.floatToRawIntBits(prev + (lid - prev) * partial));
            }
            case SIGN -> {
                TileEntitySign sign = (TileEntitySign) tile;
                putRef(sign.getBlockType());
                putKey(sign.getBlockMetadata());
                putKey(sign.lineBeingEdited);
                IChatComponent[] text = sign.signText;
                if (text.length > 4) {
                    return false;
                }
                // The scrambling (§k) is checked only when the key does not match the recorded one
                // ({@link #signObfuscated}): the components do not change inside, so what was
                // checked when recording stays valid.
                for (Object c : text) {
                    putRef(c);
                }
            }
            case SKULL -> {
                TileEntitySkull skull = (TileEntitySkull) tile;
                int type = skull.getSkullType();
                GameProfile profile = skull.getPlayerProfile();
                if (type == 3 && profile != null) {
                    // Player head with an owner: the skin arrives later, without changing the
                    // block.
                    return false;
                }
                putKey(skull.getBlockMetadata());
                putKey(type);
                putKey(skull.getSkullRotation());
                putRef(profile);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * Does the sign of the current key have scrambled text? (the components are in {@link #refBuf},
     * after the block)
     */
    static boolean signObfuscated() {
        for (int i = 1; i < refN; i++) {
            Object c = refBuf[i];
            if (c != null && obfuscated((String) SignText.formatted(c))) {
                return true;
            }
        }
        return false;
    }

    /** The lid angle as the chest renderer computes it (float). */
    static float chestLid(TileEntityChest te, float partial) {
        float prev = te.prevLidAngle;
        float lid = te.lidAngle;
        return prev + (lid - prev) * partial;
    }

    /** Does the text have a scrambled format code (§k or §K)? */
    static boolean obfuscated(String s) {
        for (int i = s.indexOf('§'); i >= 0 && i + 1 < s.length(); i = s.indexOf('§', i + 1)) {
            char c = s.charAt(i + 1);
            if (c == 'k' || c == 'K') {
                return true;
            }
        }
        return false;
    }

    /**
     * The block's retained type (0 = not retained): the game's exact class and the game's exact
     * renderer registered for it.
     */
    static int kindOf(Object dispatcher, Object te) {
        Class<?> c = te.getClass();
        int kind;
        Class<?> want;
        if (c == SteinTileGame.CHEST_TE) {
            kind = CHEST;
            want = SteinTileGame.CHEST_R;
        } else if (c == SteinTileGame.SIGN_TE) {
            kind = SIGN;
            want = SteinTileGame.SIGN_R;
        } else if (c == SteinTileGame.SKULL_TE) {
            kind = SKULL;
            want = SteinTileGame.SKULL_R;
        } else if (c == SteinTileGame.ENDER_TE) {
            kind = ENDER;
            want = SteinTileGame.ENDER_R;
        } else {
            return 0;
        }
        Object r = ((TileEntityRendererDispatcher) dispatcher).getSpecialRenderer((TileEntity) te);
        return r != null && r.getClass() == want ? kind : 0;
    }
}
