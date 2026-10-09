/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinPlayerPoseCollect.REASONS;
import static dev.xavier.stein.gfx.SteinPlayerPoseCollect.rejects;
import static dev.xavier.stein.gfx.SteinPlayerPoseCompute.*;
import static dev.xavier.stein.gfx.SteinPlayerPoseEndState.*;
import static dev.xavier.stein.gfx.SteinPlayerPoseGame.*;
import static dev.xavier.stein.gfx.SteinPlayerPoseJobs.*;
import static dev.xavier.stein.gfx.SteinPlayerPoseModels.*;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.engine.ArmorGlint;
import dev.xavier.stein.bridge.engine.GLMatrix;

import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.client.renderer.entity.layers.LayerRenderer;

import java.util.Arrays;
import java.util.List;

/**
 * Step 2 of the entity renderer: the players' pose computed by us, on several threads, instead of
 * the game's doRender.
 *
 * <p>With the deferred renderer ({@link SteinEntityDeferred}), RenderPlayer.doRender calls
 * RendererLivingEntity's doRender through here (patcher rule). For a player with nothing out of the
 * ordinary (see {@link SteinPlayerPoseCollect#collect}), the game thread only decides and reads
 * what requires calling the game — the texture of the skin and of each piece (bound the way the
 * game binds them, so the same texture comes out, including one swapped by a mod), the worn pieces,
 * the enchantment, the draw state — and the player goes into a list. At the end of the entity loop
 * (before the block entities), the list goes out at once: the game thread reserves the instances
 * and puts the commands in the groups, and the thread pool reads each player's fields (position,
 * rotations, limbs, swing), redoes the pose with the game's own computation and writes the matrices
 * straight into mapped memory. The game's model, shared among players, is not used on the threads:
 * the part tree is copied once (display lists, offsets, rotation points that the game never
 * changes).
 *
 * <p>The computation is the game's, redone from its behavior, in the same order of float
 * operations: RendererLivingEntity.doRender (interpolated body and head yaw, translate,
 * rotateCorpse, scale -1,-1,1, RenderPlayer's 0.9375 scale, -1.5078125),
 * ModelBiped.setRotationAngles (legs, arms, swing, sneak, the idle sway), ModelPlayer's "wear"
 * layers and ModelRenderer.render (the same functions as {@link SteinEntityInstancer}). Physics,
 * tick and logic untouched: read-only.
 *
 * <p>Body, armor (base and leather overlay) and glint come out of here; the name comes out the
 * usual way (kept for the end of the pass). The other RenderPlayer layers — held item, arrows in
 * the body, deadmau5 ears, cape, block or skull head — come out through the code of the game's own
 * layers, on the game thread, in the hook (see {@link #layers}): the matrix stack receives the
 * doRender root through the same operations as the game's, the game's model receives this player's
 * pose through its own setRotationAngles (as doRender does before drawing) and each layer runs as
 * in renderLayers. This way the arm matrix (postRenderArm), the per-item-type transforms, the
 * arrows' Random, the cape and the rest come out the same by construction; their drawing goes to
 * the capture (items, Tessellator, model parts).
 *
 * <p>{@code -Dsteinbridge.gfx.ownPose=true} (with {@code -Dsteinbridge.gfx.deferredEntities=true})
 * turns it on. {@code -Dsteinbridge.gfx.poseCheck=true}: for each eligible player it also runs the
 * game's path, which is what appears on screen, and compares its matrices with ours part by part
 * (log every 5 s); in the layers, it compares the matrix after postRenderArm (item), of each
 * postRender of the arrows and of the head, and the parts of the cape and ears, with the ones our
 * pose predicts. {@code -Dsteinbridge.gfx.poseThreads=N}: threads besides the game's.
 *
 * <p>Pose on the GPU ({@link SteinPoseGpu}, the default with compute and indirect drawing): in the
 * dump the game thread only reads the entity, computes the root and writes short records (player,
 * states, one word per part); the part matrices, the instances and the indirect commands come out
 * of a compute shader at the start of the flush, with the same computation and the same sine table
 * as the game. {@code -Dsteinbridge.gfx.gpuPose=false} goes back to the CPU threads (the 4.1
 * fallback and the reference); {@code -Dsteinbridge.gfx.gpuPoseCheck=true} reads back what the
 * compute shader wrote and compares it with the CPU.
 *
 * <p>Held item ({@link SteinHeldItem}): if whitelisted and with a recorded template, it leaves the
 * game's layer and becomes states (ST_ITEM: the model and the two glint passes) and a part attached
 * to the right arm (ITEM_PART; on the GPU, kind 3), on both paths; the state the layer would leave
 * behind is set by {@link SteinPlayerPoseEndState#heldState}.
 *
 * <p>The code is split by job: {@link SteinPlayerPoseCollect} (the decision, on the game thread),
 * {@link SteinPlayerPoseJobs} (the frame's player list), {@link SteinPlayerPoseModels} (the copied
 * part trees), {@link SteinPlayerPoseCompute} (the pose itself, on any thread), {@link
 * SteinPlayerPoseEndState} (the state left behind), {@link SteinPlayerPoseGame} (the game's
 * members) and the diagnostics {@link SteinPlayerPoseCheck} and {@link SteinPlayerPoseGpuCheck}.
 * This class keeps the patcher hooks, the layers through the game's code, the dump ({@link #drain})
 * and the records for the GPU.
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinPlayerPose {

    static final boolean CHECK = SteinEntityDeferred.REQUESTED && SteinGfxOptions.POSE_CHECK;
    static final boolean OWN = SteinEntityDeferred.REQUESTED && (SteinGfxOptions.OWN_POSE || CHECK);
    static final boolean STATS =
            CHECK
                    || SteinGfxOptions.DEFERRED_STATS
                    || SteinGfxOptions.POSE_STATS
                    || SteinGfxOptions.GPU_POSE_CHECK
                    || SteinGfxOptions.GPU_HELD_ITEM_CHECK;
    static final int THREADS = Math.max(0, SteinGfxOptions.POSE_THREADS);

    /**
     * Pose on the GPU ({@link SteinPoseGpu}): matrices, instances and commands in the compute
     * shader; the CPU only reads the entity and writes one short record per player (the root, the
     * setRotationAngles numbers, the rest of each state). Without compute/indirect (4.1), or with
     * {@code -Dsteinbridge.gfx.gpuPose=false}, the pose stays on the CPU threads, which remain the
     * fallback and the reference. The check against the game (poseCheck) uses only the CPU.
     */
    static final boolean GPU = OWN && !CHECK && SteinGfxOptions.GPU_POSE;

    /**
     * {@code -Dsteinbridge.gfx.gpuPoseCheck=true}: after each flush, reads back the instances and
     * commands that the compute shader wrote and compares them with the CPU computation (log every
     * 5 s). Diagnostics only: waits for the GPU on every flush.
     */
    static final boolean GPU_CHECK = GPU && SteinGfxOptions.GPU_POSE_CHECK;

    private static boolean disabled;

    // ---- numbers (bit-exact with the game) ----

    /** Degrees per radian, as the game computes it (kept bit-exact with the vanilla constant). */
    static final float RAD_TO_DEG = 57.295776F;

    static final float PI = 3.1415927F;

    /** Scale of a model part in blocks per model unit (1 / 16). */
    static final float MODEL_SCALE = 0.0625F;

    /** RenderPlayer.preRenderCallback: the player model scale and its vertical offset. */
    static final float PLAYER_SCALE = 0.9375F;

    static final float PLAYER_Y_OFFSET = -1.5078125F;

    /** Alpha-test reference of the translucent body: 1 / 255. */
    static final float ALPHA_EPSILON = 0.003921569F;

    /** The enchantment glint: texture scale (1 / 3) and brightness of its tint. */
    static final float GLINT_SCALE = 0.33333334F;

    static final float GLINT_BRIGHTNESS = 0.76F;

    static {
        // The lookups of the game's members (see the class): at the same point as ever.
        SteinPlayerPoseGame.load();
    }

    private SteinPlayerPose() {}

    // ---- hooks (patcher/redirects-opt.tsv) ----

    /**
     * RenderPlayer.doRender -> RendererLivingEntity.doRender (the super): the player becomes data,
     * or follows the game's path.
     */
    public static void doRender(
            Object render,
            Object entity,
            double x,
            double y,
            double z,
            float yaw,
            float partialTicks)
            throws Throwable {
        // Only in the entity pass: outside it (the inventory doll, mod screens) the game rotates
        // the player only during drawing and restores it afterwards, and the pose, computed in the
        // dump, would read the already restored rotation.
        if (!OWN || disabled || !SteinEntityDeferred.inEntityPass()) {
            SUPER_DO_RENDER.invokeExact(render, entity, x, y, z, yaw, partialTicks);
            return;
        }
        boolean mine;
        try {
            mine = SteinPlayerPoseCollect.collect(render, entity, x, y, z, partialTicks);
        } catch (Throwable e) {
            fail("deciding the player", e);
            mine = false;
        }
        if (!mine) {
            gamePath++;
            SUPER_DO_RENDER.invokeExact(render, entity, x, y, z, yaw, partialTicks);
            return;
        }
        int j = jobs - 1;
        if (CHECK) {
            SteinPlayerPoseCheck.run(render, entity, x, y, z, yaw, partialTicks);
            return;
        }
        ownPath++;
        int layers = jLayers[j];
        // (read before the layers: an immediate draw inside them empties the list)
        boolean held = jHeld[j] == H_OURS;
        if (held) {
            SteinHeldItem.ours++;
        } else if (jHeld[j] == H_RECORD) {
            SteinHeldItem.game++;
        }
        if (layers != 0) {
            layerPath++;
            try {
                layers(render, entity, x, y, z, partialTicks, j, layers);
            } catch (Throwable e) {
                fail("drawing the layers", e);
            }
        }
        // The name, like Render.doRender at the end of doRender (kept for the end of the pass).
        SteinEntityDeferred.renderName(render, entity, x, y, z);
        leaveState(layers == 0, held, render);
    }

    /**
     * Before each profiler section change in renderEntities: the stored players go out (the last
     * one is before the block entities, which draw immediately).
     */
    public static void drain() {
        int n = jobs;
        if (n == 0) {
            return;
        }
        if (!SteinEntityDeferred.capturing()) {
            clearJobs();
            return;
        }
        try {
            // On the GPU, the game thread only reads the entity and writes the records; the
            // compute shader runs at the flush.
            boolean gpu = GPU && SteinEntityDeferred.poseGpuAllowed() && SteinPoseGpu.ready();
            // With the pose on the GPU the threads just sit idle.
            scratch0();
            int start = 0;
            while (start < n) {
                int end = start;
                while (end < n) {
                    int count = jPlanLen[end];
                    long at =
                            gpu && !SteinPoseGpu.room(STATES, count)
                                    ? -2
                                    : SteinEntityDeferred.poseReserve(count);
                    if (at == -2 && end == start) {
                        SteinEntityDeferred.poseNextRegion();
                        at = SteinEntityDeferred.poseReserve(count);
                    }
                    if (at == -2) {
                        // The region filled up: this piece is written and drawn before continuing.
                        break;
                    }
                    if (at < 0) {
                        jPlanLen[end] = 0;
                        end++;
                        continue;
                    }
                    int first = (int) (at / SteinEntityDeferred.POSE_INSTANCE_BYTES);
                    if (gpu) {
                        try {
                            gpuPlayer(end, first);
                        } catch (Throwable e) {
                            // Reads the entity before reserving commands: the player just does
                            // not appear in this frame, and the own pose turns off (the game takes
                            // over, as on the CPU path's error).
                            fail("reading the pose for the GPU", e);
                        }
                    } else {
                        jAddr[end] = SteinEntityDeferred.poseAddress(at);
                        commands(end, first);
                    }
                    end++;
                }
                Throwable e =
                        gpu
                                ? null
                                : end - start >= 8 && SteinPoseWorkers.count() > 0
                                        ? SteinPoseWorkers.run(WRITE, start, end)
                                        : writeHere(start, end);
                if (e != null) {
                    fail("computing the pose", e);
                }
                if (end < n) {
                    SteinEntityDeferred.poseNextRegion();
                }
                start = end;
            }
        } finally {
            clearJobs();
        }
    }

    private static Throwable writeHere(int from, int to) {
        Throwable first = null;
        for (int i = from; i < to; i++) {
            try {
                write(i, 0);
            } catch (Throwable e) {
                if (first == null) {
                    first = e;
                }
            }
        }
        return first;
    }

    /**
     * Before renderEntities: empty list (leftovers of an interrupted pass do not enter the next
     * one).
     */
    public static void passBegin() {
        if (jobs > 0) {
            clearJobs();
        }
        stats();
    }

    static void fail(String what, Throwable e) {
        if (!disabled) {
            disabled = true;
            reportFailure("player pose disabled (error while " + what + ")", e);
        }
    }

    static void reportFailure(String message, Throwable e) {
        Log.error("gfx", message, e);
    }

    // ---- the game's layers (held item, arrows, ears, cape, head), on the game thread ----

    /** The doRender arguments on the game thread (see {@link SteinPlayerPoseCompute#args}). */
    private static final float[] HERE = new float[ARGS];

    /**
     * The layers that player {@code j} has, through their own code, like the game's
     * RendererLivingEntity.doRender and renderLayers: this player's pose on the game's model
     * (swingProgress, isRiding, isChild, setLivingAnimations and setRotationAngles — what doRender
     * sets before renderModel, and what every layer reads: arm, head, cape, the arrows' Random over
     * the parts), the state that doRender, renderModel and the armor leave behind, the root of the
     * matrix stack through the same operations, and each layer between its setBrightness and
     * unsetBrightness. Their drawing (items, Tessellator, model parts) falls into the deferred
     * renderer's capture like that of any entity.
     */
    private static void layers(
            Object render, Object e, double x, double y, double z, float pt, int j, int bits) {
        ModelPlayer mm = infos[jInfo[j]].mainModel;
        RendererLivingEntity renderer = (RendererLivingEntity) render;
        AbstractClientPlayer player = (AbstractClientPlayer) e;
        float[] a = HERE;
        args(e, jMount[j], pt, a);
        mm.swingProgress = renderer.getSwingProgress(player, pt);
        mm.isRiding = player.isRiding();
        mm.isChild = player.isChild();
        mm.setLivingAnimations(player, a[A_LIMB], a[A_AMOUNT], pt);
        mm.setRotationAngles(
                a[A_LIMB], a[A_AMOUNT], a[A_AGE], a[A_YAW], a[A_PITCH], MODEL_SCALE, player);

        GlStateManager.disableCull();
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableAlpha();
        GlStateManager.depthMask(true);
        bodyState(true);
        GLMatrix.pushMatrix();
        try {
            // renderLivingAt (with the bed offset, already in jPos) and rotateCorpse
            GLMatrix.translate(jPos[j * 3], jPos[j * 3 + 1], jPos[j * 3 + 2]);
            int corpse = jCorpse[j];
            float c0 = jCorpseF[j * 2];
            if (corpse == C_SLEEP) {
                GLMatrix.rotate(c0, 0.0F, 1.0F, 0.0F);
                GLMatrix.rotate(jCorpseF[j * 2 + 1], 0.0F, 0.0F, 1.0F);
                GLMatrix.rotate(270.0F, 0.0F, 1.0F, 0.0F);
            } else {
                GLMatrix.rotate(180.0F - a[A_BODY], 0.0F, 1.0F, 0.0F);
                if (corpse == C_DEATH) {
                    GLMatrix.rotate(c0, 0.0F, 0.0F, 1.0F);
                } else if (corpse == C_FLIP) {
                    GLMatrix.translate(0.0F, c0, 0.0F);
                    GLMatrix.rotate(180.0F, 0.0F, 0.0F, 1.0F);
                }
            }
            GLMatrix.scale(-1.0F, -1.0F, 1.0F);
            // RenderPlayer.preRenderCallback
            GLMatrix.scale(PLAYER_SCALE, PLAYER_SCALE, PLAYER_SCALE);
            GLMatrix.translate(0.0F, PLAYER_Y_OFFSET, 0.0F);
            List<?> list = renderer.layerRenderers;
            int held = jHeld[j];
            SteinHeldItem.Entry heldEntry = jHeldEntry[j];
            float[] predicted = null;
            float lmU = SteinGameState.lightmapU();
            float lmV = SteinGameState.lightmapV();
            if (held == H_OURS) {
                // Layer 1 went through the pose: the state it would leave, before the following
                // ones.
                heldState(render);
            } else if (held == H_RECORD
                    && SteinHeldItem.CHECK
                    && heldEntry.state == SteinHeldItem.Entry.READY) {
                // the prediction before any layer (an immediate draw inside them empties the list)
                Scratch w = scratch0();
                pose(j, w);
                predicted = w.item;
            }
            for (int i = 1; i < PLAYER_LAYERS.length; i++) {
                if ((bits & 1 << i) == 0) {
                    continue;
                }
                LayerRenderer layer = (LayerRenderer) list.get(i);
                boolean bright = renderer.setBrightness(player, pt, layer.shouldCombineTextures());
                boolean record = i == 1 && held == H_RECORD;
                try {
                    if (record) {
                        // The item's template (or its check) through the game's path.
                        SteinHeldItem.recordBegin();
                    }
                    try {
                        layer.doRenderLayer(
                                player,
                                a[A_LIMB],
                                a[A_AMOUNT],
                                pt,
                                a[A_AGE],
                                a[A_YAW],
                                a[A_PITCH],
                                MODEL_SCALE);
                    } finally {
                        if (record) {
                            SteinHeldItem.recordEnd(heldEntry, predicted, lmU, lmV);
                        }
                    }
                    // What the patcher puts after each doRenderLayer of renderLayers.
                    ArmorGlint.flushDeferred();
                } finally {
                    if (bright) {
                        renderer.unsetBrightness(); // otherwise the damage red stays on
                    }
                }
            }
        } finally {
            GLMatrix.popMatrix();
        }
    }

    /** A player's commands in the groups (game thread), from instance {@code first}. */
    private static void commands(int j, int first) {
        Info info = infos[jInfo[j]];
        int at = jPlanAt[j];
        int len = jPlanLen[j];
        int k0 = j * STATES;
        for (int k = 0; k < len; k++) {
            int part = plan[(at + k) * 2];
            int st = plan[(at + k) * 2 + 1];
            if (part == ITEM_PART) {
                SteinHeldItem.Entry en = jHeldEntry[j];
                int d = st - ST_ITEM;
                SteinEntityDeferred.poseStatic(jKey[k0 + st], en.first[d], en.verts[d], first + k);
            } else {
                SteinEntityDeferred.posePart(
                        jKey[k0 + st], info.models[part >>> 4].list[part & 15], first + k);
            }
        }
    }

    // ---- pose on the GPU (SteinPoseGpu): the records, on the game thread ----

    private static final float[] GPU_ARGS = new float[ARGS];
    private static final float[] GPU_ROOT = new float[16];
    private static final float[] GPU_ROOT0 = new float[16];

    /**
     * Player {@code j} for the compute shader, with the instances starting at {@code first}: reads
     * the entity (args, as the threads would read it) and computes the root here — few operations
     * per player, with Mat4's cosines in double, so the root comes out bit-for-bit equal —, and
     * writes its record, one record per state (with the commands reserved in the state's group, in
     * plan order) and one word per part. The part matrices, the instances and the commands come out
     * of the compute shader. Nothing is reserved before the read: an error in it only removes the
     * player.
     */
    private static void gpuPlayer(int j, int first) {
        float[] a = GPU_ARGS;
        args(jEntity[j], jMount[j], jPt[j], a);
        float[] r = GPU_ROOT;
        boolean held = jHeld[j] == H_OURS;
        root(j, a[A_BODY], r, held ? GPU_ROOT0 : null);
        int flags =
                (jModel[j * 4] != 0 ? 1 : 0)
                        | ((jStates[j] & S_RIDING) != 0 ? 2 : 0)
                        | (jModel[j * 4 + 3] != 0 ? 4 : 0)
                        | (jModel[j * 4 + 1] & 255) << 8
                        | (jModel[j * 4 + 2] & 255) << 16;
        int player =
                SteinPoseGpu.player(
                        r,
                        a[A_YAW],
                        a[A_PITCH],
                        a[A_AGE],
                        a[A_AMOUNT],
                        a[A_LIMB],
                        a[A_SWING],
                        flags,
                        jInfo[j] * 3,
                        held ? GPU_ROOT0 : null,
                        held ? jHeldEntry[j].xform : null,
                        held ? jItemFlags[j] : 0);
        if (GPU_CHECK) {
            pose(j, scratch[0]); // the reference: the CPU computation, to compare after the flush
        }
        int at = jPlanAt[j];
        int len = jPlanLen[j];
        int s0 = j * STATES;
        int k = 0;
        while (k < len) {
            // the states come in sequences in the plan (body, each piece, overlay, each glint pass)
            int st = plan[(at + k) * 2 + 1];
            int end = k + 1;
            while (end < len && plan[(at + end) * 2 + 1] == st) {
                end++;
            }
            long slot = SteinEntityDeferred.poseSlots(jKey[s0 + st], end - k);
            // the held item: the fixed geometry range goes in the state (the compute shader does
            // not have its list table)
            boolean item = st >= ST_ITEM;
            int itemFirst = item ? jHeldEntry[j].first[st - ST_ITEM] : -1;
            int itemVerts = item ? jHeldEntry[j].verts[st - ST_ITEM] : 0;
            int state =
                    SteinPoseGpu.state(
                            player,
                            slot,
                            first + k,
                            jState,
                            (s0 + st) * REST,
                            itemFirst,
                            itemVerts);
            for (int q = k; q < end; q++) {
                int part = plan[(at + q) * 2];
                if (GPU_CHECK) {
                    int list = item ? 0 : infos[jInfo[j]].models[part >>> 4].list[part & 15];
                    SteinPlayerPoseGpuCheck.checkExpect(
                            scratch[0].mats,
                            part,
                            first + q,
                            slot,
                            q - k,
                            (s0 + st) * REST,
                            item ? itemVerts : SteinModelInstancer.vertexCount(list),
                            item ? itemFirst : SteinModelInstancer.firstVertex(list));
                }
                SteinPoseGpu.entry(state, part);
            }
            k = end;
        }
    }

    /** Models in the compute shader's input table: 3 per RenderPlayer (player, armor, leggings). */
    static int gpuModelSlots() {
        return SteinPlayerPoseModels.gpuModelSlots();
    }

    /** The model table at {@code p} (mapped memory), see {@link SteinPlayerPoseModels}. */
    static void gpuWriteModels(long p) {
        SteinPlayerPoseModels.gpuWriteModels(p);
    }

    /**
     * After the flush with pose on the GPU ({@link SteinPoseGpu#afterFlush}): reads back and
     * compares.
     */
    static void gpuCheck(SteinBuffer instances, SteinBuffer commands, boolean ran) {
        SteinPlayerPoseGpuCheck.gpuCheck(instances, commands, ran);
    }

    // ---- hooks of the layer check (patcher/redirects-opt.tsv; inert without poseCheck) ----

    /** After postRenderArm in LayerHeldItem.doRenderLayer (patcher rule, after mode). */
    public static void afterArm() {
        if (CHECK && SteinPlayerPoseCheck.layerRecording) {
            SteinPlayerPoseCheck.layerRecord(SteinPlayerPoseCheck.K_ARM);
        }
    }

    /** After each ModelRenderer.postRender in LayerArrow.doRenderLayer. */
    public static void afterArrowPart() {
        if (CHECK && SteinPlayerPoseCheck.layerRecording) {
            SteinPlayerPoseCheck.layerRecord(SteinPlayerPoseCheck.K_ARROW);
        }
    }

    /** After ModelRenderer.postRender in LayerCustomHead.doRenderLayer. */
    public static void afterHeadPart() {
        if (CHECK && SteinPlayerPoseCheck.layerRecording) {
            SteinPlayerPoseCheck.layerRecord(SteinPlayerPoseCheck.K_HEAD);
        }
    }

    // ---- numbers ----

    private static long ownPath;
    private static long layerPath;
    private static long gamePath;
    private static long frames;
    private static long statsAt = System.nanoTime();

    private static void stats() {
        if (!OWN) {
            return;
        }
        frames++;
        if (!STATS) {
            return;
        }
        long now = System.nanoTime();
        if (now - statsAt < 5_000_000_000L) {
            return;
        }
        statsAt = now;
        StringBuilder b = new StringBuilder("pose per frame: ");
        long fr = Math.max(1, frames);
        b.append(ownPath / fr)
                .append(" players through ours (")
                .append(layerPath / fr)
                .append(" with game layers), ")
                .append(gamePath / fr)
                .append(" through the game");
        boolean any = false;
        for (int i = 0; i < REASONS.length; i++) {
            if (rejects[i] > 0) {
                b.append(any ? ", " : " (").append(REASONS[i]).append(' ').append(rejects[i] / fr);
                any = true;
            }
        }
        if (any) {
            b.append(')');
        }
        if (GPU && SteinPoseGpu.ready()) {
            b.append(", pose on the GPU (").append(SteinPoseGpu.takeStats()).append(" in 5 s)");
        } else {
            b.append(", ").append(SteinPoseWorkers.count()).append(" threads besides the game's");
        }
        Log.info("gfx", b.toString());
        if (SteinHeldItem.ON) {
            SteinHeldItem.report(fr);
        }
        if (GPU_CHECK) {
            SteinPlayerPoseGpuCheck.printGpuCheck();
        }
        if (CHECK) {
            SteinPlayerPoseCheck.printChecks();
        }
        ownPath = 0;
        layerPath = 0;
        gamePath = 0;
        frames = 0;
        Arrays.fill(rejects, 0);
    }
}
