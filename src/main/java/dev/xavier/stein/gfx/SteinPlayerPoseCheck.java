/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinPlayerPose.*;
import static dev.xavier.stein.gfx.SteinPlayerPoseCompute.*;
import static dev.xavier.stein.gfx.SteinPlayerPoseGame.*;
import static dev.xavier.stein.gfx.SteinPlayerPoseJobs.*;
import static dev.xavier.stein.gfx.SteinPlayerPoseModels.*;

import dev.xavier.stein.bridge.core.Log;
import dev.xavier.stein.bridge.engine.GLMatrix;
import dev.xavier.stein.bridge.engine.Mat4;

import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * The diagnostics of {@link SteinPlayerPose}'s CPU path: {@code -Dsteinbridge.gfx.poseCheck=true}
 * runs the game's own path for every eligible player and compares its matrices, part by part, with
 * the pose's, and, for the layers (held item, arrows, head, cape, ears), with the matrices the pose
 * predicts beforehand. The counters are printed (and reset) every 5 s by {@link #printChecks}.
 * Nothing here runs without that flag.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinPlayerPoseCheck {
    private SteinPlayerPoseCheck() {}

    private static final float CHECK_TOLERANCE = 1e-4f;

    private static final String[] STATE_NAMES = {
        "crouching",
        "riding",
        "spectator",
        "translucent invisible",
        "invisible without body",
        "sleeping",
        "dying",
        "upside down"
    };

    // ---- the game's path next to ours (poseCheck) ----

    /**
     * poseCheck for the player just collected (index {@code jobs - 1}): predicts the layers, then
     * runs the game's path, which is what appears on screen, and compares its matrices with ours.
     */
    static void run(
            Object render,
            Object entity,
            double x,
            double y,
            double z,
            float yaw,
            float partialTicks)
            throws Throwable {
        int j = jobs - 1;
        // The game's path draws (it is what appears) and its matrices are compared with ours.
        boolean predicted = false;
        try {
            predict(j, render, entity);
            predicted = true;
        } catch (Throwable e) {
            fail("predicting the layers", e);
        }
        // The player leaves the list before the game's path: an immediate draw inside it (item
        // glint, fire) empties the list (SteinEntityDeferred.flushInOrder -> drain), and it is
        // not to be drawn by ours. Its data (index j) stay intact for the comparison.
        jobs = j;
        planN = jPlanAt[j];
        SteinEntityInstancer.captureBegin();
        layerRecording = predicted;
        recN = 0;
        try {
            SUPER_DO_RENDER.invokeExact(render, entity, x, y, z, yaw, partialTicks);
        } finally {
            layerRecording = false;
            int n = SteinEntityInstancer.captureEnd();
            try {
                compare(j, n);
                if (predicted) {
                    compareLayers(j, n);
                }
            } finally {
                jEntity[j] = null;
                jMount[j] = null;
            }
        }
    }

    // ---- check of the part matrices ----

    private static long checkPlayers;
    private static long checkParts;
    private static long checkExact;
    private static long checkSequence;
    private static final long[] checkDiverge = new long[3 * 16];
    private static float checkWorst;
    private static int checkWorstPart = -1;

    /** Compares the game path's matrices (annotated by SteinEntityInstancer) with ours. */
    private static void compare(int j, int captured) {
        Scratch w = scratch0();
        pose(j, w);
        Info info = infos[jInfo[j]];
        float[] game = SteinEntityInstancer.capturedMatrices();
        int at = jPlanAt[j];
        int len = jPlanLen[j];
        int c = 0;
        boolean sequence = true;
        int partsHere = 0;
        int exactHere = 0;
        for (int k = 0; k < len; k++) {
            int part = plan[(at + k) * 2];
            int st = plan[(at + k) * 2 + 1];
            if (st >= ST_GLINT) {
                // The glint re-emits the base parts; the game does not go through ModelRenderer.
                continue;
            }
            if (c >= captured
                    || SteinEntityInstancer.capturedList(c)
                            != info.models[part >>> 4].list[part & 15]) {
                sequence = false;
                break;
            }
            float err = error(w.mats, part * 16, game, c * 16);
            SteinPlayerPoseStateCheck.compare(st, c);
            checkParts++;
            partsHere++;
            if (err == 0f) {
                checkExact++;
                exactHere++;
            } else if (err > CHECK_TOLERANCE) {
                checkDiverge[part]++;
            }
            if (err > checkWorst) {
                checkWorst = err;
                checkWorstPart = part;
            }
            c++;
        }
        // What comes after the plan's parts and the game's layers (cape, ears, item and skull
        // models): checked in compareLayers.
        planCaptured = c;
        if (!sequence) {
            checkSequence++;
            planCaptured = captured; // cannot tell where the layers begin
        }
        checkPlayers++;
        // Per state (riding, sleeping, invisible...): players, parts, bit-for-bit equal,
        // different sequence.
        int states = jStates[j];
        for (int b = 0; b < STATE_NAMES.length; b++) {
            if ((states & 1 << b) != 0) {
                stateCheck[b * 4]++;
                stateCheck[b * 4 + 1] += partsHere;
                stateCheck[b * 4 + 2] += exactHere;
                if (!sequence) {
                    stateCheck[b * 4 + 3]++;
                }
            }
        }
    }

    private static final long[] stateCheck = new long[8 * 4];

    private static int planCaptured;

    // ---- layer check ----
    // The game's path runs the layers with its own pose; the hooks (patcher/redirects-opt.tsv)
    // record the matrix right after LayerHeldItem's postRenderArm and after each postRender of
    // LayerArrow and LayerCustomHead, and SteinEntityInstancer's capture records the cape and ears
    // (ModelRenderer.render). Beforehand, our pose predicts each one: the root without the sneak,
    // the part's postRender with our angles (the slim arm with the rotationPointX ++/--), the
    // arrows' Random with the entity id seed, the cape and ear computations. On our path the
    // layers run through the game's code over the same root and the same setRotationAngles: if the
    // prediction matches, so does their matrix.

    static final int K_ARM = 0;
    static final int K_ARROW = 1;
    static final int K_HEAD = 2;
    static final int K_CAPE = 3;
    static final int K_EARS = 4;
    private static final String[] KIND_NAMES = {"item arm", "arrow", "head", "cape", "ears"};

    static boolean layerRecording;
    private static int recN;
    private static int[] recKind = new int[16];
    private static float[] recMat = new float[16 * 16];
    private static int predN;
    private static int[] predKind = new int[16];
    private static float[] predMat = new float[16 * 16];
    private static boolean predCape;
    private static final float[] predCapeMat = new float[16];
    private static boolean predEars;
    private static final float[] predEarsMat = new float[32];

    /** Layers that may draw models we do not predict (block-entity item, skull). */
    private static boolean predOthers;

    private static final float[] PM = new float[16];
    private static final float[] PT = new float[16];
    private static final float[] PANG = new float[3];
    private static final float[] PRP = new float[PLAYER_PARTS * 3];

    static void layerRecord(int kind) {
        if (recN == recKind.length) {
            recKind = Arrays.copyOf(recKind, recN * 2);
            recMat = Arrays.copyOf(recMat, recN * 2 * 16);
        }
        recKind[recN] = kind;
        System.arraycopy(GLMatrix.current(), 0, recMat, recN * 16, 16);
        recN++;
    }

    /** One more prediction, with the root without the sneak; returns its offset in predMat. */
    private static int predSlot(int kind, float[] root0) {
        if (predN == predKind.length) {
            predKind = Arrays.copyOf(predKind, predN * 2);
            predMat = Arrays.copyOf(predMat, predN * 2 * 16);
        }
        predKind[predN] = kind;
        System.arraycopy(root0, 0, predMat, predN * 16, 16);
        return predN++ * 16;
    }

    /**
     * The matrices that player {@code j}'s layers should have, before the game's path runs (what it
     * changes in the shared model has not changed yet). Game thread.
     */
    private static void predict(int j, Object render, Object e) {
        Scratch w = scratch0();
        pose(j, w);
        float pt = jPt[j];
        float[] root0 = w.root0;
        float[] rp = PRP;
        float[] ang = w.mainAng;
        System.arraycopy(w.mainRp, 0, rp, 0, rp.length);
        Info info = infos[jInfo[j]];
        Model main = info.models[M_PLAYER];
        ModelPlayer mm = info.mainModel;
        AbstractClientPlayer p = (AbstractClientPlayer) e;
        boolean sneak = (jStates[j] & S_SNEAK) != 0; // the layers read the entity's isSneaking
        predN = 0;
        predCape = false;
        predEars = false;
        predOthers = false;
        if ((jStates[j] & S_SPECTATOR) != 0) {
            return; // doRender does not call renderLayers for the spectator
        }

        // LayerHeldItem: postRenderArm (on the slim arm, rotationPointX + 1 only during postRender;
        // the -1 afterwards stays).
        if (p.getHeldItem() != null) {
            predOthers = true;
            int o = predSlot(K_ARM, root0);
            float x0 = rp[RARM * 3];
            boolean small = mm.smallArms;
            float xs = small ? x0 + 1.0F : x0;
            postRender(
                    predMat,
                    o,
                    main.parts[RARM],
                    xs,
                    rp[RARM * 3 + 1],
                    rp[RARM * 3 + 2],
                    ang,
                    RARM * 3);
            if (small) {
                rp[RARM * 3] = xs - 1.0F;
            }
        }

        // LayerArrow: the Random with the id seed, the part and box drawn, three floats per arrow.
        int arrows = p.getArrowCountInEntity();
        if (arrows > 0) {
            Random random = new Random((long) p.getEntityId());
            List<?> boxes = mm.boxList;
            ModelRenderer cape = mm.bipedCape;
            for (int k = 0; k < arrows; k++) {
                ModelRenderer part = (ModelRenderer) boxes.get(random.nextInt(boxes.size()));
                List<?> cubes = part.cubeList;
                random.nextInt(cubes.size());
                int o = predSlot(K_ARROW, root0);
                int i = indexOf(main.parts, part);
                if (i >= 0) {
                    postRender(
                            predMat, o, part, rp[i * 3], rp[i * 3 + 1], rp[i * 3 + 2], ang, i * 3);
                } else {
                    // Outside the 12 parts: the current fields (setRotationAngles only touches the
                    // cape's height).
                    float ry = part == cape ? (sneak ? 2.0F : 0.0F) : part.rotationPointY;
                    float[] pa = PANG;
                    pa[0] = part.rotateAngleX;
                    pa[1] = part.rotateAngleY;
                    pa[2] = part.rotateAngleZ;
                    postRender(
                            predMat, o, part, part.rotationPointX, ry, part.rotationPointZ, pa, 0);
                }
                random.nextFloat();
                random.nextFloat();
                random.nextFloat();
            }
        }

        // LayerDeadmau5Head: two ears, the part with the head's angles and the rotation point
        // zeroed in x and y.
        if ("deadmau5".equals(p.getName()) && p.hasSkin() && !p.isInvisible()) {
            predEars = true;
            float prevYaw = p.prevRotationYaw;
            float prevBody = p.prevRenderYawOffset;
            float f =
                    prevYaw
                            + (p.rotationYaw - prevYaw) * pt
                            - (prevBody + (p.renderYawOffset - prevBody) * pt);
            float prevPitch = p.prevRotationPitch;
            float f1 = prevPitch + (p.rotationPitch - prevPitch) * pt;
            ModelRenderer ears = mm.bipedDeadmau5Head;
            for (int i = 0; i < 2; i++) {
                float[] m = predEarsMat;
                int o = i * 16;
                System.arraycopy(root0, 0, m, o, 16);
                System.arraycopy(m, o, PM, 0, 16);
                Mat4.rotate(PM, f, 0.0F, 1.0F, 0.0F);
                Mat4.rotate(PM, f1, 1.0F, 0.0F, 0.0F);
                Mat4.translate(PM, 0.375F * (float) (i * 2 - 1), 0.0F, 0.0F);
                Mat4.translate(PM, 0.0F, -0.375F, 0.0F);
                Mat4.rotate(PM, -f1, 1.0F, 0.0F, 0.0F);
                Mat4.rotate(PM, -f, 0.0F, 1.0F, 0.0F);
                float s = 1.3333334F;
                Mat4.scale(PM, s, s, s);
                System.arraycopy(PM, 0, m, o, 16);
                render(
                        m,
                        o,
                        ears,
                        0.0F,
                        0.0F,
                        rp[HEAD * 3 + 2],
                        ang[HEAD * 3],
                        ang[HEAD * 3 + 1],
                        ang[HEAD * 3 + 2]);
            }
        }

        // LayerCape
        if (p.hasPlayerInfo()
                && !p.isInvisible()
                && p.isWearing(CAPE_PART)
                && p.getLocationCape() != null) {
            predCape = true;
            float[] m = PM;
            System.arraycopy(root0, 0, m, 0, 16);
            Mat4.translate(m, 0.0F, 0.0F, 0.125F);
            double d0 = chase(p, 0, pt) - lerp(p.prevPosX, p.posX, pt);
            double d1 = chase(p, 1, pt) - lerp(p.prevPosY, p.posY, pt);
            double d2 = chase(p, 2, pt) - lerp(p.prevPosZ, p.posZ, pt);
            float prevBody = p.prevRenderYawOffset;
            float f = prevBody + (p.renderYawOffset - prevBody) * pt;
            double d3 = (double) sin(f * PI / 180.0F);
            double d4 = (double) -cos(f * PI / 180.0F);
            float f1 = (float) d1 * 10.0F;
            f1 = f1 < -6.0F ? -6.0F : (f1 > 32.0F ? 32.0F : f1);
            float f2 = (float) (d0 * d3 + d2 * d4) * 100.0F;
            float f3 = (float) (d0 * d4 - d2 * d3) * 100.0F;
            if (f2 < 0.0F) {
                f2 = 0.0F;
            }
            float prevCam = p.prevCameraYaw;
            float f4 = prevCam + (p.cameraYaw - prevCam) * pt;
            float prevWalked = p.prevDistanceWalkedModified;
            f1 =
                    f1
                            + sin(
                                            (prevWalked
                                                            + (p.distanceWalkedModified
                                                                            - prevWalked)
                                                                    * pt)
                                                    * 6.0F)
                                    * 32.0F
                                    * f4;
            if (sneak) {
                f1 += 25.0F;
            }
            Mat4.rotate(m, 6.0F + f2 / 2.0F + f1, 1.0F, 0.0F, 0.0F);
            Mat4.rotate(m, f3 / 2.0F, 0.0F, 0.0F, 1.0F);
            Mat4.rotate(m, -f3 / 2.0F, 0.0F, 1.0F, 0.0F);
            Mat4.rotate(m, 180.0F, 0.0F, 1.0F, 0.0F);
            System.arraycopy(m, 0, predCapeMat, 0, 16);
            ModelRenderer cape = mm.bipedCape;
            render(
                    predCapeMat,
                    0,
                    cape,
                    cape.rotationPointX,
                    sneak ? 2.0F : 0.0F,
                    cape.rotationPointZ,
                    cape.rotateAngleX,
                    cape.rotateAngleY,
                    cape.rotateAngleZ);
        }

        // LayerCustomHead: with anything in the helmet slot, the head's postRender
        // (sneaking: + 0.2 before).
        ItemStack helmet = p.getCurrentArmor(3);
        if (helmet != null && helmet.getItem() != null) {
            predOthers |= !ARMOR_ITEM.isInstance(helmet.getItem());
            int o = predSlot(K_HEAD, root0);
            if (sneak) {
                System.arraycopy(predMat, o, PM, 0, 16);
                Mat4.translate(PM, 0.0F, 0.2F, 0.0F);
                System.arraycopy(PM, 0, predMat, o, 16);
            }
            postRender(
                    predMat,
                    o,
                    main.parts[HEAD],
                    rp[HEAD * 3],
                    rp[HEAD * 3 + 1],
                    rp[HEAD * 3 + 2],
                    ang,
                    HEAD * 3);
        }
    }

    /** The cape's chasing position on one axis, interpolated (prevChasingPos, chasingPos). */
    private static double chase(EntityPlayer p, int axis, float pt) {
        double prev =
                axis == 0 ? p.prevChasingPosX : axis == 1 ? p.prevChasingPosY : p.prevChasingPosZ;
        double now = axis == 0 ? p.chasingPosX : axis == 1 ? p.chasingPosY : p.chasingPosZ;
        return prev + (now - prev) * (double) pt;
    }

    private static double lerp(double prev, double now, float pt) {
        return prev + (now - prev) * (double) pt;
    }

    private static int indexOf(Object[] parts, Object part) {
        for (int i = 0; i < parts.length; i++) {
            if (parts[i] == part) {
                return i;
            }
        }
        return -1;
    }

    /**
     * ModelRenderer.postRender(0.0625) over m[o..o+15], with the given rotation point and the
     * angles in ang[a..a+2]: GlStateManager's operations (degrees), as the game does.
     */
    private static void postRender(
            float[] m,
            int o,
            ModelRenderer part,
            float rx,
            float ry,
            float rz,
            float[] ang,
            int a) {
        if (part.isHidden || !part.showModel) {
            return;
        }
        float ax = ang[a];
        float ay = ang[a + 1];
        float az = ang[a + 2];
        float s = MODEL_SCALE;
        float[] t = PT;
        System.arraycopy(m, o, t, 0, 16);
        if (ax == 0.0F && ay == 0.0F && az == 0.0F) {
            if (rx != 0.0F || ry != 0.0F || rz != 0.0F) {
                Mat4.translate(t, rx * s, ry * s, rz * s);
            }
        } else {
            Mat4.translate(t, rx * s, ry * s, rz * s);
            if (az != 0.0F) {
                Mat4.rotate(t, az * RAD_TO_DEG, 0.0F, 0.0F, 1.0F);
            }
            if (ay != 0.0F) {
                Mat4.rotate(t, ay * RAD_TO_DEG, 0.0F, 1.0F, 0.0F);
            }
            if (ax != 0.0F) {
                Mat4.rotate(t, ax * RAD_TO_DEG, 1.0F, 0.0F, 0.0F);
            }
        }
        System.arraycopy(t, 0, m, o, 16);
    }

    /**
     * ModelRenderer.render of a part without children like SteinEntityInstancer.draw (radians),
     * over m[o..o+15].
     */
    private static void render(
            float[] m,
            int o,
            ModelRenderer part,
            float rx,
            float ry,
            float rz,
            float ax,
            float ay,
            float az) {
        SteinEntityInstancer.translate(m, o, part.offsetX, part.offsetY, part.offsetZ);
        float scale = MODEL_SCALE;
        float x = rx * scale;
        float y = ry * scale;
        float z = rz * scale;
        if (x != 0f || y != 0f || z != 0f) {
            SteinEntityInstancer.translate(m, o, x, y, z);
        }
        if (az != 0f) {
            SteinEntityInstancer.rotateZ(m, o, az);
        }
        if (ay != 0f) {
            SteinEntityInstancer.rotateY(m, o, ay);
        }
        if (ax != 0f) {
            SteinEntityInstancer.rotateX(m, o, ax);
        }
    }

    private static long layerMats;
    private static long layerExact;
    private static long layerSequence;
    private static long layerOthers;
    private static long layerMissing;
    private static final long[] layerDiverge = new long[KIND_NAMES.length];
    private static float layerWorst;
    private static int layerWorstKind = -1;

    private static void layerError(int kind, float err) {
        layerMats++;
        if (err == 0f) {
            layerExact++;
        } else if (err > CHECK_TOLERANCE) {
            layerDiverge[kind]++;
        }
        if (err > layerWorst) {
            layerWorst = err;
            layerWorstKind = kind;
        }
    }

    /**
     * Compares the matrices annotated on the game's path with the predicted ones (after compare).
     */
    private static void compareLayers(int j, int captured) {
        boolean sequence = recN == predN;
        for (int k = 0; k < Math.min(recN, predN); k++) {
            if (recKind[k] != predKind[k]) {
                sequence = false;
                break;
            }
            layerError(predKind[k], error(predMat, k * 16, recMat, k * 16));
        }
        // Cape and ears: model parts annotated after the plan's.
        ModelPlayer mm = infos[jInfo[j]].mainModel;
        int capeList = predCape ? mm.bipedCape.displayList : -1;
        int earsList = predEars ? mm.bipedDeadmau5Head.displayList : -1;
        float[] game = SteinEntityInstancer.capturedMatrices();
        boolean capeSeen = false;
        int ear = 0;
        for (int c = planCaptured; c < captured; c++) {
            int list = SteinEntityInstancer.capturedList(c);
            if (list == capeList && !capeSeen) {
                capeSeen = true;
                layerError(K_CAPE, error(predCapeMat, 0, game, c * 16));
            } else if (list == earsList && ear < 2) {
                layerError(K_EARS, error(predEarsMat, ear * 16, game, c * 16));
                ear++;
            } else if (predOthers) {
                layerOthers++; // block-entity item model, skull: not predicted
            } else {
                sequence = false;
            }
        }
        if (predCape && !capeSeen) {
            // The cape went through the game's render (part with no geometry on the GPU yet): no
            // annotation.
            layerMissing++;
        }
        if (!sequence) {
            layerSequence++;
        }
    }

    /**
     * The largest relative error between two matrices, column by column (each column at its own
     * scale).
     */
    private static float error(float[] a, int ao, float[] b, int bo) {
        float worst = 0f;
        for (int col = 0; col < 4; col++) {
            float scale = 0f;
            float diff = 0f;
            for (int r = 0; r < 4; r++) {
                float x = a[ao + col * 4 + r];
                float y = b[bo + col * 4 + r];
                scale = Math.max(scale, Math.max(Math.abs(x), Math.abs(y)));
                diff = Math.max(diff, Math.abs(x - y));
            }
            if (diff > 0f) {
                float e = scale > 1e-6f ? diff / scale : diff;
                if (Float.isNaN(e)) {
                    e = Float.POSITIVE_INFINITY;
                }
                worst = Math.max(worst, e);
            }
        }
        return worst;
    }

    // ---- the report (called from SteinPlayerPose.stats, every 5 s) ----

    /** The three poseCheck reports (parts, layers, per state), and resets their counters. */
    static void printChecks() {
        printPoseCheck();
        printLayerCheck();
        printStateCheck();
        SteinPlayerPoseStateCheck.print();
    }

    private static void printPoseCheck() {
        StringBuilder c = new StringBuilder("pose check: ");
        c.append(checkPlayers)
                .append(" players, ")
                .append(checkParts)
                .append(" parts, ")
                .append(checkParts > 0 ? checkExact * 100 / checkParts : 0)
                .append("% bit-identical");
        boolean div = false;
        for (int i = 0; i < checkDiverge.length; i++) {
            if (checkDiverge[i] > 0) {
                c.append(div ? ", " : "; above 1e-4: ")
                        .append(MODEL_NAMES[i >>> 4])
                        .append(PART_NAMES[i & 15])
                        .append(' ')
                        .append(checkDiverge[i]);
                div = true;
            }
        }
        if (!div) {
            c.append("; no part above 1e-4");
        }
        if (checkWorstPart >= 0) {
            c.append("; worst error ")
                    .append(checkWorst)
                    .append(" (")
                    .append(MODEL_NAMES[checkWorstPart >>> 4])
                    .append(PART_NAMES[checkWorstPart & 15])
                    .append(')');
        }
        if (checkSequence > 0) {
            c.append("; different part sequence in ").append(checkSequence).append(" players");
        }
        Log.info("gfx", c.toString());
        checkPlayers = 0;
        checkParts = 0;
        checkExact = 0;
        checkSequence = 0;
        Arrays.fill(checkDiverge, 0);
        checkWorst = 0f;
        checkWorstPart = -1;
    }

    private static void printLayerCheck() {
        StringBuilder d = new StringBuilder("layer check: ");
        d.append(layerMats)
                .append(" matrices, ")
                .append(layerMats > 0 ? layerExact * 100 / layerMats : 0)
                .append("% bit-identical");
        boolean ldiv = false;
        for (int i = 0; i < layerDiverge.length; i++) {
            if (layerDiverge[i] > 0) {
                d.append(ldiv ? ", " : "; above 1e-4: ")
                        .append(KIND_NAMES[i])
                        .append(' ')
                        .append(layerDiverge[i]);
                ldiv = true;
            }
        }
        if (!ldiv) {
            d.append("; none above 1e-4");
        }
        if (layerWorstKind >= 0) {
            d.append("; worst error ")
                    .append(layerWorst)
                    .append(" (")
                    .append(KIND_NAMES[layerWorstKind])
                    .append(')');
        }
        if (layerSequence > 0) {
            d.append("; different sequence in ").append(layerSequence).append(" players");
        }
        if (layerOthers > 0) {
            d.append("; ").append(layerOthers).append(" item/skull models not predicted");
        }
        if (layerMissing > 0) {
            d.append("; cape without annotation in ").append(layerMissing);
        }
        Log.info("gfx", d.toString());
        layerMats = 0;
        layerExact = 0;
        layerSequence = 0;
        layerOthers = 0;
        layerMissing = 0;
        Arrays.fill(layerDiverge, 0);
        layerWorst = 0f;
        layerWorstKind = -1;
    }

    private static void printStateCheck() {
        StringBuilder s = new StringBuilder("check per state:");
        boolean anyState = false;
        for (int i = 0; i < STATE_NAMES.length; i++) {
            long p = stateCheck[i * 4];
            if (p == 0) {
                continue;
            }
            long parts = stateCheck[i * 4 + 1];
            s.append(anyState ? "; " : " ")
                    .append(STATE_NAMES[i])
                    .append(' ')
                    .append(p)
                    .append(" players, ")
                    .append(parts > 0 ? stateCheck[i * 4 + 2] * 100 / parts : 100)
                    .append("% bit-identical");
            if (stateCheck[i * 4 + 3] > 0) {
                s.append(", different sequence in ").append(stateCheck[i * 4 + 3]);
            }
            anyState = true;
        }
        if (anyState) {
            Log.info("gfx", s.toString());
        }
        Arrays.fill(stateCheck, 0);
    }
}
