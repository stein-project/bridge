/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinPlayerPose.*;
import static dev.xavier.stein.gfx.SteinPlayerPoseGame.*;
import static dev.xavier.stein.gfx.SteinPlayerPoseJobs.*;
import static dev.xavier.stein.gfx.SteinPlayerPoseModels.*;

import dev.xavier.stein.bridge.engine.AngleTable;
import dev.xavier.stein.bridge.engine.Mat4;

import net.minecraft.entity.EntityLivingBase;

import org.lwjgl.system.MemoryUtil;

/**
 * The player pose itself, safe to run on any thread (it only reads fields and computes): the
 * RendererLivingEntity.doRender numbers, the root matrix, ModelBiped.setRotationAngles, and the
 * matrix of every part, in the same order of float operations as the game. {@link #write} runs it
 * for one stored player and writes the instances into the reserved memory.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinPlayerPoseCompute {
    private SteinPlayerPoseCompute() {}

    // ---- the pose, on any thread (only field reads and computation) ----

    /** A thread's scratch. */
    static final class Scratch {
        final float[] root = new float[16];
        final float[] rp = new float[PLAYER_PARTS * 3];
        final float[] ang = new float[PLAYER_PARTS * 3];

        /**
         * Matrix of each part of each model: (model * 16 + part) * 16; model 3 is the held item.
         */
        final float[] mats = new float[4 * 16 * 16];

        final float[] args = new float[ARGS];
        // The root without the sneak (the layers and the held item) and, only in the check, the
        // player model's pose.
        final float[] root0 = new float[16];
        final float[] mainRp = new float[PLAYER_PARTS * 3];
        final float[] mainAng = new float[PLAYER_PARTS * 3];

        /** The held item's matrix. */
        final float[] item = new float[16];
    }

    static Scratch[] scratch;

    /** The game thread's scratch (creates the thread pool the first time, like the dump). */
    static Scratch scratch0() {
        if (scratch == null) {
            int t = SteinPoseWorkers.start(THREADS);
            scratch = new Scratch[t + 1];
            for (int i = 0; i <= t; i++) {
                scratch[i] = new Scratch();
            }
        }
        return scratch[0];
    }

    static final SteinPoseWorkers.Task WRITE = SteinPlayerPoseCompute::write;

    /** Computes player {@code j}'s pose and writes its instances into the reserved memory. */
    static void write(int j, int worker) throws Throwable {
        int len = jPlanLen[j];
        if (len == 0) {
            return;
        }
        long p = jAddr[j];
        Scratch w = scratch[worker];
        try {
            pose(j, w);
        } catch (Throwable e) {
            // Zero matrix: the player vanishes in this frame, instead of garbage on screen.
            MemoryUtil.memSet(p, 0, (long) len * SteinEntityDeferred.POSE_INSTANCE_BYTES);
            throw e;
        }
        float[] mats = w.mats;
        float[] st = jState;
        int at = jPlanAt[j];
        int s0 = j * STATES;
        for (int k = 0; k < len; k++, p += SteinEntityDeferred.POSE_INSTANCE_BYTES) {
            int m = plan[(at + k) * 2] * 16;
            long q = p;
            for (int r = 0; r < 3; r++) {
                MemoryUtil.memPutFloat(q, mats[m + r]);
                MemoryUtil.memPutFloat(q + 4, mats[m + 4 + r]);
                MemoryUtil.memPutFloat(q + 8, mats[m + 8 + r]);
                MemoryUtil.memPutFloat(q + 12, mats[m + 12 + r]);
                q += 16;
            }
            int o = (s0 + plan[(at + k) * 2 + 1]) * REST;
            for (int i = 0; i < REST; i++) {
                MemoryUtil.memPutFloat(q + i * 4L, st[o + i]);
            }
        }
    }

    // Indices of args().
    static final int A_BODY = 0;
    static final int A_YAW = 1;
    static final int A_PITCH = 2;
    static final int A_AGE = 3;
    static final int A_AMOUNT = 4;
    static final int A_LIMB = 5;
    static final int A_SWING = 6;
    static final int ARGS = 7;

    /**
     * What the pose needs from entity {@code e}: body yaw, head yaw relative to the body, pitch,
     * age, limb swing and step, swing progress. {@code mount} is the living mount or null. Any
     * thread (only field reads).
     */
    static void args(Object entity, Object mountEntity, float pt, float[] out) {
        EntityLivingBase e = (EntityLivingBase) entity;
        EntityLivingBase mount = (EntityLivingBase) mountEntity;
        float headYaw = interpolateRotation(e.prevRotationYawHead, e.rotationYawHead, pt);
        float bodyYaw;
        float netHeadYaw;
        if (mount == null) {
            bodyYaw = interpolateRotation(e.prevRenderYawOffset, e.renderYawOffset, pt);
            netHeadYaw = headYaw - bodyYaw;
        } else {
            // The body follows the mount and the head may turn at most 85 degrees from it; the
            // relative head yaw reported is still the unclamped one.
            float mountYaw =
                    interpolateRotation(mount.prevRenderYawOffset, mount.renderYawOffset, pt);
            netHeadYaw = headYaw - mountYaw;
            bodyYaw = bodyYawUnderHeadLimit(headYaw, netHeadYaw);
        }
        float prevAmount = e.prevLimbSwingAmount;
        float amountNow = e.limbSwingAmount;
        float amount = prevAmount + (amountNow - prevAmount) * pt;
        if (amount > 1.0F) {
            amount = 1.0F;
        }
        float prevPitch = e.prevRotationPitch;
        float prevSwing = e.prevSwingProgress;
        float swingDelta = e.swingProgress - prevSwing;
        if (swingDelta < 0.0F) {
            swingDelta += 1.0F;
        }
        out[A_BODY] = bodyYaw;
        out[A_YAW] = netHeadYaw;
        out[A_PITCH] = prevPitch + (e.rotationPitch - prevPitch) * pt;
        out[A_AGE] = (float) e.ticksExisted + pt;
        out[A_AMOUNT] = amount;
        out[A_LIMB] = e.limbSwing - amountNow * (1.0F - pt);
        out[A_SWING] = prevSwing + swingDelta * pt;
    }

    private static final float HEAD_TURN_LIMIT = 85.0F;
    private static final float HEAD_TURN_DRAG_THRESHOLD = 2500.0F;
    private static final float HEAD_TURN_DRAG = 0.2F;

    /**
     * Body yaw of a rider: it stays within {@link #HEAD_TURN_LIMIT} degrees of the head, and is
     * dragged along by a fraction of the offset when the head is turned beyond 50 degrees.
     */
    private static float bodyYawUnderHeadLimit(float headYaw, float relativeHeadYaw) {
        float turn = wrapDegrees(relativeHeadYaw);
        if (turn < -HEAD_TURN_LIMIT) {
            turn = -HEAD_TURN_LIMIT;
        }
        if (turn >= HEAD_TURN_LIMIT) {
            turn = HEAD_TURN_LIMIT;
        }
        float bodyYaw = headYaw - turn;
        if (turn * turn > HEAD_TURN_DRAG_THRESHOLD) {
            bodyYaw += turn * HEAD_TURN_DRAG;
        }
        return bodyYaw;
    }

    /** The matrix of each part of the models that player {@code j} uses, in {@code w.mats}. */
    static void pose(int j, Scratch w) throws Throwable {
        Object e = jEntity[j];
        float pt = jPt[j];
        // RendererLivingEntity.doRender
        float[] a = w.args;
        args(e, jMount[j], pt, a);
        float bodyYaw = a[A_BODY];
        float netHeadYaw = a[A_YAW];
        float pitch = a[A_PITCH];
        float age = a[A_AGE];
        float amount = a[A_AMOUNT];
        float limbSwing = a[A_LIMB];
        float swing = a[A_SWING];

        root(j, bodyYaw, w.root, CHECK || jHeld[j] != H_GAME ? w.root0 : null);
        // ModelBiped.render translates by the entity's isSneaking (at the root); setRotationAngles
        // uses the model's isSneak (the same, except on the spectator, where setModelVisibilities
        // does not update the model).
        boolean sneak = jModel[j * 4] != 0;
        boolean riding = (jStates[j] & S_RIDING) != 0;
        Info info = infos[jInfo[j]];
        int models = jModels[j];
        for (int kind = 0; kind < 3; kind++) {
            if ((models & 1 << kind) == 0) {
                continue;
            }
            Model m = info.models[kind];
            System.arraycopy(m.rp, 0, w.rp, 0, m.n * 3);
            System.arraycopy(m.ang, 0, w.ang, 0, m.n * 3);
            setRotationAngles(
                    w.rp,
                    w.ang,
                    limbSwing,
                    amount,
                    age,
                    netHeadYaw,
                    pitch,
                    swing,
                    sneak,
                    riding,
                    jModel[j * 4 + 1],
                    jModel[j * 4 + 2],
                    jModel[j * 4 + 3] != 0);
            if (kind == M_PLAYER) {
                // ModelPlayer.setRotationAngles: the skin layers follow the lower part.
                copy(w.rp, w.ang, LLEG, LLEGW);
                copy(w.rp, w.ang, RLEG, RLEGW);
                copy(w.rp, w.ang, LARM, LARMW);
                copy(w.rp, w.ang, RARM, RARMW);
                copy(w.rp, w.ang, BODY, BODYW);
                if (CHECK) {
                    System.arraycopy(w.rp, 0, w.mainRp, 0, PLAYER_PARTS * 3);
                    System.arraycopy(w.ang, 0, w.mainAng, 0, PLAYER_PARTS * 3);
                }
                if (jHeld[j] != H_GAME) {
                    // the held item, attached to this pose's right arm (the root without the sneak,
                    // as the layer sees it)
                    SteinHeldItem.matrix(
                            w.item,
                            w.root0,
                            w.rp,
                            w.ang,
                            RARM * 3,
                            jItemFlags[j],
                            jHeldEntry[j].xform);
                    System.arraycopy(w.item, 0, w.mats, ITEM_PART * 16, 16);
                }
            }
            for (int i = 0; i < m.n; i++) {
                partMatrix(w, m, i, (kind * 16 + i) * 16);
            }
        }
    }

    /**
     * The root of player {@code j} in {@code r}: the game's matrix in doRender, renderLivingAt,
     * rotateCorpse, RenderPlayer's scales and, when sneaking, ModelBiped.render's +0.2. {@code
     * root0} (or null) receives the root before the sneak (the one the layers see).
     */
    static void root(int j, float bodyYaw, float[] r, float[] root0) {
        System.arraycopy(jBase, j * 16, r, 0, 16);
        Mat4.translate(r, jPos[j * 3], jPos[j * 3 + 1], jPos[j * 3 + 2]); // renderLivingAt
        int corpse = jCorpse[j]; // rotateCorpse
        float c0 = jCorpseF[j * 2];
        if (corpse == C_SLEEP) {
            // RenderPlayer: lying in bed
            Mat4.rotate(r, c0, 0.0F, 1.0F, 0.0F);
            Mat4.rotate(r, jCorpseF[j * 2 + 1], 0.0F, 0.0F, 1.0F);
            Mat4.rotate(r, 270.0F, 0.0F, 1.0F, 0.0F);
        } else {
            Mat4.rotate(r, 180.0F - bodyYaw, 0.0F, 1.0F, 0.0F);
            if (corpse == C_DEATH) {
                Mat4.rotate(r, c0, 0.0F, 0.0F, 1.0F); // the fall to the side
            } else if (corpse == C_FLIP) {
                Mat4.translate(r, 0.0F, c0, 0.0F); // Dinnerbone/Grumm
                Mat4.rotate(r, 180.0F, 0.0F, 0.0F, 1.0F);
            }
        }
        Mat4.scale(r, -1.0F, -1.0F, 1.0F);
        // RenderPlayer.preRenderCallback
        Mat4.scale(r, PLAYER_SCALE, PLAYER_SCALE, PLAYER_SCALE);
        Mat4.translate(r, 0.0F, PLAYER_Y_OFFSET, 0.0F);
        if (root0 != null) {
            System.arraycopy(r, 0, root0, 0, 16);
        }
        if ((jStates[j] & S_SNEAK) != 0) {
            Mat4.translate(r, 0.0F, 0.2F, 0.0F); // ModelBiped.render, sneaking
        }
    }

    /** MathHelper.wrapAngleTo180_float. */
    private static float wrapDegrees(float v) {
        v = v % 360.0F;
        if (v >= 180.0F) {
            v -= 360.0F;
        }
        if (v < -180.0F) {
            v += 360.0F;
        }
        return v;
    }

    /** RendererLivingEntity.interpolateRotation. */
    private static float interpolateRotation(float prev, float now, float pt) {
        float d = now - prev;
        while (d < -180.0F) {
            d += 360.0F;
        }
        while (d >= 180.0F) {
            d -= 360.0F;
        }
        return prev + pt * d;
    }

    static float sin(float x) {
        return AngleTable.sin(x);
    }

    static float cos(float x) {
        return AngleTable.cos(x);
    }

    // Resting angles of the limbs under each pose modifier, in radians.
    private static final float SEATED_ARM_TILT = -0.62831855F;
    private static final float SEATED_LEG_PITCH = -1.2566371F;
    private static final float SEATED_LEG_SPLAY = 0.31415927F;
    private static final float HELD_ITEM_STEP = 0.31415927F;
    private static final float BLOCKING_ARM_TURN = -0.5235988F;
    private static final float BOW_AIM_PITCH = -1.5707964F;
    private static final float BOW_AIM_SPLIT = 0.1F;
    private static final float BOW_AIM_LEFT_TURN = 0.4F;
    private static final float SNEAK_ARM_LIFT = 0.4F;
    private static final float SNEAK_BODY_PITCH = 0.5F;

    // Rotation-point depth and height of the legs and height of the head, standing and sneaking.
    private static final float STAND_LEG_DEPTH = 0.1F;
    private static final float STAND_LEG_HEIGHT = 12.0F;
    private static final float SNEAK_LEG_DEPTH = 4.0F;
    private static final float SNEAK_LEG_HEIGHT = 9.0F;
    private static final float SNEAK_HEAD_HEIGHT = 1.0F;

    /** Walk cycle: radians of limb phase per unit of limb swing. */
    private static final float WALK_FREQUENCY = 0.6662F;

    private static final float ARM_SWING_AMPLITUDE = 2.0F;
    private static final float ARM_SWING_DAMPING = 0.5F;
    private static final float LEG_SWING_AMPLITUDE = 1.4F;

    // Offsets of each part's first component (x) in the rp/ang arrays; +1 is y, +2 is z.
    private static final int HEAD_AT = HEAD * 3;
    private static final int BODY_AT = BODY * 3;
    private static final int RARM_AT = RARM * 3;
    private static final int LARM_AT = LARM * 3;
    private static final int RLEG_AT = RLEG * 3;
    private static final int LLEG_AT = LLEG * 3;

    /**
     * The biped pose over the rotation points ({@code rp}) and angles ({@code ang}) of the 7 lower
     * parts (3 floats per part), as a sequence of modifiers applied in a fixed order. {@code
     * riding}: the model's isRiding (seated).
     */
    private static void setRotationAngles(
            float[] rp,
            float[] ang,
            float limbSwing,
            float amount,
            float age,
            float yaw,
            float pitch,
            float swing,
            boolean sneak,
            boolean riding,
            int heldLeft,
            int heldRight,
            boolean bow) {
        aimHead(ang, yaw, pitch);
        walkCycle(ang, limbSwing, amount);
        if (riding) {
            sitOnMount(ang);
        }
        holdItems(ang, heldLeft, heldRight);
        if (swing > -9990.0F) {
            swingArm(rp, ang, swing);
        }
        setStance(rp, ang, sneak);
        // Both idle terms depend only on the age, so the bow reuses the same values.
        float swayZ = cos(age * 0.09F) * 0.05F + 0.05F;
        float swayX = sin(age * 0.067F) * 0.05F;
        idleSway(ang, swayX, swayZ);
        if (bow) {
            aimBow(ang);
            idleSway(ang, swayX, swayZ);
        }
        copy(rp, ang, HEAD, HEADWEAR);
    }

    /** The head looks where the entity does (degrees to radians). */
    private static void aimHead(float[] ang, float yaw, float pitch) {
        ang[HEAD_AT + 1] = yaw / RAD_TO_DEG;
        ang[HEAD_AT] = pitch / RAD_TO_DEG;
    }

    /**
     * Arms and legs swing in opposite phase: each limb is one cosine, with a phase of 0 or pi and
     * an amplitude that differs between arms and legs. Also clears the roll of both arms, the turn
     * of both arms and the turn of both legs.
     */
    private static void walkCycle(float[] ang, float limbSwing, float amount) {
        float phase = limbSwing * WALK_FREQUENCY;
        float oppositePhase = phase + PI;
        ang[RARM_AT] = cos(oppositePhase) * ARM_SWING_AMPLITUDE * amount * ARM_SWING_DAMPING;
        ang[LARM_AT] = cos(phase) * ARM_SWING_AMPLITUDE * amount * ARM_SWING_DAMPING;
        ang[RLEG_AT] = cos(phase) * LEG_SWING_AMPLITUDE * amount;
        ang[LLEG_AT] = cos(oppositePhase) * LEG_SWING_AMPLITUDE * amount;
        ang[RARM_AT + 1] = 0.0F;
        ang[LARM_AT + 1] = 0.0F;
        ang[RARM_AT + 2] = 0.0F;
        ang[LARM_AT + 2] = 0.0F;
        ang[RLEG_AT + 1] = 0.0F;
        ang[LLEG_AT + 1] = 0.0F;
    }

    /** Seated: arms forward, legs stretched out and apart. */
    private static void sitOnMount(float[] ang) {
        ang[RARM_AT] += SEATED_ARM_TILT;
        ang[LARM_AT] += SEATED_ARM_TILT;
        ang[RLEG_AT] = SEATED_LEG_PITCH;
        ang[LLEG_AT] = SEATED_LEG_PITCH;
        ang[RLEG_AT + 1] = SEATED_LEG_SPLAY;
        ang[LLEG_AT + 1] = -SEATED_LEG_SPLAY;
    }

    /**
     * Item poses: the left arm is raised by {@code heldLeft} steps; the right one for state 1
     * (item) and 3 (blocking, which also turns the arm inward).
     */
    private static void holdItems(float[] ang, int heldLeft, int heldRight) {
        if (heldLeft != 0) {
            ang[LARM_AT] = ang[LARM_AT] * 0.5F - HELD_ITEM_STEP * (float) heldLeft;
        }
        if (heldRight == 1 || heldRight == 3) {
            ang[RARM_AT] = ang[RARM_AT] * 0.5F - HELD_ITEM_STEP * (float) heldRight;
            if (heldRight == 3) {
                ang[RARM_AT + 1] = BLOCKING_ARM_TURN;
            }
        }
    }

    /**
     * Attack swing ({@code swing} in 0..1): the body twists, the arms follow it and the right arm
     * strikes along an ease-out curve.
     */
    private static void swingArm(float[] rp, float[] ang, float swing) {
        float twist = sin((float) Math.sqrt((double) swing) * PI * 2.0F) * 0.2F;
        ang[BODY_AT + 1] = twist;
        rp[RARM_AT + 2] = sin(twist) * 5.0F;
        rp[RARM_AT] = -cos(twist) * 5.0F;
        rp[LARM_AT + 2] = -sin(twist) * 5.0F;
        rp[LARM_AT] = cos(twist) * 5.0F;
        ang[RARM_AT + 1] += twist;
        ang[LARM_AT + 1] += twist;
        ang[LARM_AT] += twist;
        float remaining = 1.0F - swing;
        float remainingSquared = remaining * remaining;
        float easeOut = 1.0F - remainingSquared * remainingSquared;
        float strike = sin(easeOut * PI);
        float reach = sin(swing * PI) * -(ang[HEAD_AT] - 0.7F) * 0.75F;
        ang[RARM_AT] = (float) ((double) ang[RARM_AT] - ((double) strike * 1.2D + (double) reach));
        ang[RARM_AT + 1] += twist * 2.0F;
        ang[RARM_AT + 2] += sin(swing * PI) * -0.4F;
    }

    /** Standing or sneaking: body lean, arm lift and the rotation points of legs and head. */
    private static void setStance(float[] rp, float[] ang, boolean sneak) {
        float legDepth;
        float legHeight;
        float headHeight;
        if (sneak) {
            ang[BODY_AT] = SNEAK_BODY_PITCH;
            ang[RARM_AT] += SNEAK_ARM_LIFT;
            ang[LARM_AT] += SNEAK_ARM_LIFT;
            legDepth = SNEAK_LEG_DEPTH;
            legHeight = SNEAK_LEG_HEIGHT;
            headHeight = SNEAK_HEAD_HEIGHT;
        } else {
            ang[BODY_AT] = 0.0F;
            legDepth = STAND_LEG_DEPTH;
            legHeight = STAND_LEG_HEIGHT;
            headHeight = 0.0F;
        }
        rp[RLEG_AT + 2] = legDepth;
        rp[LLEG_AT + 2] = legDepth;
        rp[RLEG_AT + 1] = legHeight;
        rp[LLEG_AT + 1] = legHeight;
        rp[HEAD_AT + 1] = headHeight;
    }

    /** Drawn bow: both arms point where the head looks. */
    private static void aimBow(float[] ang) {
        ang[RARM_AT + 2] = 0.0F;
        ang[LARM_AT + 2] = 0.0F;
        ang[RARM_AT + 1] = -BOW_AIM_SPLIT + ang[HEAD_AT + 1];
        ang[LARM_AT + 1] = BOW_AIM_SPLIT + ang[HEAD_AT + 1] + BOW_AIM_LEFT_TURN;
        ang[RARM_AT] = BOW_AIM_PITCH + ang[HEAD_AT];
        ang[LARM_AT] = BOW_AIM_PITCH + ang[HEAD_AT];
    }

    /** The arms' idle sway, added to the angles already set (x and z of the right and left arm). */
    private static void idleSway(float[] ang, float swayX, float swayZ) {
        ang[RARM_AT + 2] += swayZ;
        ang[LARM_AT + 2] -= swayZ;
        ang[RARM_AT] += swayX;
        ang[LARM_AT] -= swayX;
    }

    /** ModelBase.copyModelAngles: angles and rotation point. */
    private static void copy(float[] rp, float[] ang, int from, int to) {
        System.arraycopy(ang, from * 3, ang, to * 3, 3);
        System.arraycopy(rp, from * 3, rp, to * 3, 3);
    }

    /** ModelRenderer.render of a part (SteinEntityInstancer.draw's computations), from the root. */
    private static void partMatrix(Scratch w, Model m, int i, int out) {
        float[] s = w.mats;
        System.arraycopy(w.root, 0, s, out, 16);
        SteinEntityInstancer.translate(s, out, m.off[i * 3], m.off[i * 3 + 1], m.off[i * 3 + 2]);
        float scale = MODEL_SCALE;
        float rx = w.rp[i * 3] * scale;
        float ry = w.rp[i * 3 + 1] * scale;
        float rz = w.rp[i * 3 + 2] * scale;
        if (rx != 0f || ry != 0f || rz != 0f) {
            SteinEntityInstancer.translate(s, out, rx, ry, rz);
        }
        float ax = w.ang[i * 3];
        float ay = w.ang[i * 3 + 1];
        float az = w.ang[i * 3 + 2];
        if (az != 0f) {
            SteinEntityInstancer.rotateZ(s, out, az);
        }
        if (ay != 0f) {
            SteinEntityInstancer.rotateY(s, out, ay);
        }
        if (ax != 0f) {
            SteinEntityInstancer.rotateX(s, out, ax);
        }
    }
}
