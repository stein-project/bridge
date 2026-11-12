/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.showcase;

import dev.xavier.stein.bridge.core.Log;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.particle.EffectRenderer;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The showcase's own client world: a WorldClient that no server knows about, its chunks filled from
 * {@link ShowcaseTerrain}, with its own RenderGlobal, particle renderer and a camera that never
 * moves. It is never ticked: nothing in it lives, nothing in it sends a packet.
 *
 * <p>Built once, the first time the video settings open, and then kept: a RenderGlobal starts its
 * own chunk-building threads, which 1.8.9 has no way to stop. The scene sits {@link #OFFSET} blocks
 * away from the origin, so its chunks never share a position with the real world's (the chunk
 * fade-in remembers chunks by position).
 *
 * <p>Pipeline: both.
 */
final class ShowcaseWorld {

    /** Where the scene's origin is, in blocks (a multiple of 16). */
    static final int OFFSET = 1 << 15;

    /** The camera's eye above the ground, and where it looks: west, a little up. */
    private static final double EYE_ABOVE = 3.4;

    /**
     * West, turned a little to the right: the video settings' panel covers the right of the screen,
     * so the lake, the sun and the moon sit in the part that shows.
     */
    private static final float YAW_WEST = 108f;

    private static final float PITCH_UP = -3f;

    final WorldClient world;
    final RenderGlobal renderGlobal;
    final EffectRenderer effects;
    final EntityArmorStand camera;

    private ShowcaseWorld(
            WorldClient world,
            RenderGlobal renderGlobal,
            EffectRenderer effects,
            EntityArmorStand camera) {
        this.world = world;
        this.renderGlobal = renderGlobal;
        this.effects = effects;
        this.camera = camera;
    }

    /** Builds the world, its chunks and their light, and the renderer that draws it. */
    static ShowcaseWorld build() {
        long t0 = System.nanoTime();
        Minecraft mc = Minecraft.getMinecraft();
        WorldSettings settings =
                new WorldSettings(
                        0L, WorldSettings.GameType.SPECTATOR, false, false, WorldType.FLAT);
        WorldClient w = new WorldClient(null, settings, 0, EnumDifficulty.PEACEFUL, mc.mcProfiler);
        int r = ShowcaseTerrain.RADIUS >> 4;
        for (int cx = -r; cx < r; cx++) {
            for (int cz = -r; cz < r; cz++) {
                w.doPreChunk((OFFSET >> 4) + cx, (OFFSET >> 4) + cz, true);
            }
        }
        Fill fill = new Fill(w);
        ShowcaseTerrain.build(fill);
        byte plains = (byte) BiomeGenBase.plains.biomeID;
        for (int cx = -r; cx < r; cx++) {
            for (int cz = -r; cz < r; cz++) {
                Chunk c = w.getChunkFromChunkCoords((OFFSET >> 4) + cx, (OFFSET >> 4) + cz);
                Arrays.fill(c.getBiomeArray(), plains);
                c.generateSkylightMap();
                c.setTerrainPopulated(true);
                c.setLightPopulated(true);
            }
        }
        for (BlockPos p : fill.lights) {
            w.checkLightFor(EnumSkyBlock.BLOCK, p);
        }
        w.setWorldTime(Showcase.DAY_TIME);
        EntityArmorStand camera = new EntityArmorStand(w);
        double cx = OFFSET + ShowcaseTerrain.CAMERA_X;
        double cz = OFFSET + ShowcaseTerrain.CAMERA_Z;
        int ground =
                ShowcaseTerrain.height(
                        (int) Math.floor(ShowcaseTerrain.CAMERA_X),
                        (int) Math.floor(ShowcaseTerrain.CAMERA_Z));
        double y = ground + 1 + EYE_ABOVE - camera.getEyeHeight();
        camera.setLocationAndAngles(cx, y, cz, YAW_WEST, PITCH_UP);
        hold(camera);
        RenderGlobal rg = new RenderGlobal(mc);
        rg.setWorldAndLoadRenderers(w);
        EffectRenderer fx = new EffectRenderer(w, mc.getTextureManager());
        Log.info(
                "showcase",
                String.format(
                        "scene built in %.0f ms (%d chunks, %d lights)",
                        (System.nanoTime() - t0) / 1e6, 4 * r * r, fill.lights.size()));
        return new ShowcaseWorld(w, rg, fx, camera);
    }

    /** Keeps the camera still under any partial tick: its previous position is its position. */
    static void hold(EntityArmorStand camera) {
        camera.prevPosX = camera.lastTickPosX = camera.posX;
        camera.prevPosY = camera.lastTickPosY = camera.posY;
        camera.prevPosZ = camera.lastTickPosZ = camera.posZ;
        camera.prevRotationYaw = camera.rotationYaw;
        camera.prevRotationPitch = camera.rotationPitch;
    }

    /**
     * Writes the scene's blocks straight into the chunks' storage (no light update or render update
     * per block: the light is computed once at the end) and remembers the light sources.
     */
    private static final class Fill implements ShowcaseTerrain.Sink {
        private final WorldClient world;
        private final boolean sky;
        final List<BlockPos> lights = new ArrayList<>();

        Fill(WorldClient world) {
            this.world = world;
            this.sky = !world.provider.getHasNoSky();
        }

        @Override
        public void set(int x, int y, int z, IBlockState state) {
            if (y < 0 || y > 255) {
                return;
            }
            int wx = OFFSET + x;
            int wz = OFFSET + z;
            Chunk c = world.getChunkFromChunkCoords(wx >> 4, wz >> 4);
            ExtendedBlockStorage[] storage = c.getBlockStorageArray();
            ExtendedBlockStorage s = storage[y >> 4];
            if (s == null) {
                s = new ExtendedBlockStorage(y >> 4 << 4, sky);
                storage[y >> 4] = s;
            }
            s.set(wx & 15, y & 15, wz & 15, state);
            Block b = state.getBlock();
            if (b.getLightValue() > 0) {
                lights.add(new BlockPos(wx, y, wz));
            }
        }

        @Override
        public IBlockState get(int x, int y, int z) {
            if (y < 0 || y > 255) {
                return Blocks.air.getDefaultState();
            }
            int wx = OFFSET + x;
            int wz = OFFSET + z;
            ExtendedBlockStorage s =
                    world.getChunkFromChunkCoords(wx >> 4, wz >> 4).getBlockStorageArray()[y >> 4];
            return s == null ? Blocks.air.getDefaultState() : s.get(wx & 15, y & 15, wz & 15);
        }
    }
}
