/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinPlayerPose.*;
import static dev.xavier.stein.gfx.SteinPlayerPoseEndState.*;
import static dev.xavier.stein.gfx.SteinPlayerPoseGame.*;
import static dev.xavier.stein.gfx.SteinPlayerPoseJobs.*;
import static dev.xavier.stein.gfx.SteinPlayerPoseModels.*;

import dev.xavier.stein.bridge.engine.GLMatrix;
import dev.xavier.stein.bridge.engine.Mat4;
import dev.xavier.stein.bridge.engine.OldAnimations;
import dev.xavier.stein.bridge.engine.Visuals;
import dev.xavier.stein.bridge.hooks.GameHooks;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.client.renderer.entity.layers.LayerBipedArmor;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.opengl.GL11;

import java.util.Arrays;

/**
 * The decision step of {@link SteinPlayerPose}, on the game thread: whether a player enters the new
 * path, and, if so, everything read from the game for it (skin, armor, glint, held item, draw
 * state) stored as one job of {@link SteinPlayerPoseJobs}.
 *
 * <p>{@link #collect} runs once per player per frame and is split into steps that share their
 * intermediate values through the private static fields below (the game thread is the only one that
 * calls it, so there is no allocation and no synchronisation); the order of the reads from the game
 * is the order of the steps. The class is longer than usual because the steps are one cohesive
 * thing: they share those fields, so they stay together.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinPlayerPoseCollect {
    private SteinPlayerPoseCollect() {}

    // ---- why a player is left to the game's path ----

    static final int R_STATE = 0;
    static final int R_ENTITY = 1;
    static final int R_LAYER = 2;
    static final int R_MODEL = 3;
    static final int R_TEXTURE = 4;
    static final int R_OLD_ANIMATIONS = 5;
    static final String[] REASONS = {
        "state", "mod ghost/baby", "foreign armor material", "model", "texture", "1.7 animations"
    };
    static final long[] rejects = new long[REASONS.length];

    private static final float[] TEX = new float[16];

    /** The held item's mode is off after an error while deciding it. */
    private static boolean heldOff;

    // ---- the player being collected (valid during one call of collect) ----

    private static String name;
    private static int deathTime;
    private static boolean spectator;
    private static boolean visible;
    private static boolean translucent;
    private static boolean drawBody;
    private static boolean renderSneak;
    private static boolean riding;
    private static Object mount;

    /** rotateCorpse kind (C_*), its numbers, and the position with the bed offset. */
    private static int corpse;

    private static float corpse0;
    private static float corpse1;
    private static float px;
    private static float py;
    private static float pz;

    /** L_* bits of the game layers to run, and whether anything is in the helmet slot. */
    private static int layers;

    private static boolean helmetAny;

    private static int infoIndex;
    private static Info playerInfo;
    private static Model playerCopy;
    private static ModelPlayer gameModel;
    private static int heldLeft;
    private static int heldRight;
    private static boolean sneak;
    private static boolean bow;
    private static boolean hurtNow;

    /** The draw state read from the game (see {@link #checkDrawState}). */
    private static float alphaRef;

    private static float cr;
    private static float cg;
    private static float cb;
    private static float ca;
    private static boolean depthTest;
    private static boolean lightmap;
    private static boolean fog;
    private static float lmU;
    private static float lmV;

    /** The job's index, its first state, and the flags of lit states. */
    private static int j;

    private static int s0;
    private static int lit;

    /**
     * The colour the last thing drawn leaves in the game's state (see {@link
     * SteinPlayerPoseEndState}).
     */
    private static float lastR;

    private static float lastG;
    private static float lastB;
    private static float lastA;

    /** Armor: bit per piece (s = 0 helmet .. 3 boots) worn, leather, and enchanted. */
    private static int present;

    private static int leather;
    private static int enchanted;
    private static boolean glint;

    private static int held;
    private static SteinHeldItem.Entry heldEntry;
    private static int itemFlags;

    /** The plan: where this player's entries start and end, and the models they need. */
    private static int planAt;

    private static int planEnd;
    private static int modelBits;

    // ---- deciding and reading, on the game thread ----

    /**
     * Does the player enter the new path? Reads what it needs from the game and stores it in the
     * list; false = the game's path (nothing stored). Left out: outline, mod ghost, child (never on
     * a player), extra layers on the RenderPlayer, armor of an outside material, unusual draw
     * state, texture outside the array, model not yet compiled. Held item, arrows, cape, ears and
     * block or skull head enter: they are marked in {@link SteinPlayerPoseJobs#jLayers} and come
     * out through the game's layers ({@link SteinPlayerPose#layers}). Invisible (translucent to the
     * team, or without the body), spectator (head only, no layers), sleeping, riding, dying and
     * Dinnerbone/Grumm enter with the game's computation for each one (jCorpse, jMount, jStates).
     */
    static boolean collect(Object render, Object e, double x, double y, double z, float pt)
            throws Throwable {
        try {
            if (!acceptGameState(render, e)) {
                return false;
            }
            readFlags(render, e);
            classifyCorpse(render, e, x, y, z, pt);
            layerBits(e);
            if (!readModel(render, e)) {
                return false;
            }
            if (!checkDrawState()) {
                return false;
            }
            if (jobs == jEntity.length) {
                grow();
            }
            j = jobs;
            s0 = j * STATES;
            lit = SteinEntityDeferred.poseFlags(true, lightmap, fog);
            lastR = cr;
            lastG = cg;
            lastB = cb;
            lastA = ca;
            if (!addBody(render, e)) {
                return false;
            }
            if (!addArmor(render, e)) {
                return false;
            }
            if (!addGlint(render, e, pt)) {
                return false;
            }
            if (lightmap) {
                SteinEntityDeferred.poseLightmap(SteinGameState.textureName(1));
            }
            decideHeldItem(e);
            buildPlan();
            publishJob(e, pt);
            return true;
        } finally {
            // (no reference to a player outlives the call)
            name = null;
            mount = null;
            playerInfo = null;
            playerCopy = null;
            gameModel = null;
            heldEntry = null;
        }
    }

    /** The engine is ready for a pose job: geometry resident, plain matrix stack, no outline. */
    private static boolean acceptGameState(Object render, Object e) {
        if (!SteinModelInstancer.geometryReady()
                || !GLMatrix.batchable()
                || GLMatrix.textureOrNull() != null
                || ((RendererLivingEntity) render).renderOutlines) {
            return reject(R_STATE);
        }
        // Mod ghost: out. Child: EntityPlayer never is one (EntityLivingBase's isChild returns
        // false and the player does not override it); the child scale of ModelBiped/ModelPlayer is
        // not done here.
        if (GameHooks.ghost(e) || ((EntityLivingBase) e).isChild()) {
            return reject(R_ENTITY);
        }
        return true;
    }

    /** The reads that decide how the body is drawn, and the ridden mount. */
    private static void readFlags(Object render, Object e) {
        AbstractClientPlayer p = (AbstractClientPlayer) e;
        name = p.getName();
        deathTime = p.deathTime;
        spectator = p.isSpectator();
        // renderModel: visible; otherwise translucent if the camera player sees it (team),
        // otherwise the body is not drawn.
        visible = !p.isInvisible();
        translucent = !visible && !p.isInvisibleToPlayer(Minecraft.getMinecraft().thePlayer);
        drawBody = visible || translucent;
        renderSneak = p.isSneaking();
        riding = p.isRiding();
        mount = riding ? p.ridingEntity : null;
        if (mount != null && !LIVING.isInstance(mount)) {
            mount = null; // only the living mount affects the body yaw
        }
    }

    /** rotateCorpse (RenderPlayer and RendererLivingEntity) and RenderPlayer's renderLivingAt. */
    private static void classifyCorpse(
            Object render, Object e, double x, double y, double z, float pt) {
        corpse = C_NORMAL;
        corpse0 = 0f;
        corpse1 = 0f;
        px = (float) x;
        py = (float) y;
        pz = (float) z;
        AbstractClientPlayer p = (AbstractClientPlayer) e;
        if (p.isEntityAlive() && p.isPlayerSleeping()) {
            corpse = C_SLEEP;
            corpse0 = p.getBedOrientationInDegrees();
            corpse1 = ((RendererLivingEntity) render).getDeathMaxRotation(p);
            px = (float) (x + (double) p.renderOffsetX);
            py = (float) (y + (double) p.renderOffsetY);
            pz = (float) (z + (double) p.renderOffsetZ);
        } else if (deathTime > 0) {
            corpse = C_DEATH;
            corpse0 =
                    deathFallProgress(deathTime, pt)
                            * ((RendererLivingEntity) render).getDeathMaxRotation(p);
        } else {
            String plain = EnumChatFormatting.getTextWithoutFormattingCodes(name);
            if (plain != null
                    && ("Dinnerbone".equals(plain) || "Grumm".equals(plain))
                    && p.isWearing(CAPE_PART)) {
                corpse = C_FLIP;
                corpse0 = p.height + 0.1F;
            }
        }
    }

    /** How far the death tilt has gone, 0 to 1: a square-root ease-out of the ticks since death. */
    private static float deathFallProgress(int deathTime, float pt) {
        float elapsed = (float) deathTime + pt - 1.0F;
        float progress = (float) Math.sqrt((double) (elapsed / 20.0F * 1.6F));
        return progress > 1.0F ? 1.0F : progress;
    }

    /** The game layers to run for this player (L_* bits), see the comment inside. */
    private static void layerBits(Object e) {
        // The game layers that have something to draw (the others would just return doing
        // nothing). The head one runs with anything in the helmet slot, armor included: it sets
        // the color to white even without drawing. The spectator has no layers (doRender skips
        // renderLayers).
        AbstractClientPlayer p = (AbstractClientPlayer) e;
        layers = 0;
        if (p.getHeldItem() != null) {
            layers |= L_HELD;
        }
        if (p.getArrowCountInEntity() > 0) {
            layers |= L_ARROW;
        }
        if ("deadmau5".equals(name)) {
            layers |= L_EARS;
        }
        if (p.getLocationCape() != null && p.isWearing(CAPE_PART)) {
            layers |= L_CAPE;
        }
        helmetAny = false;
        ItemStack helmet = p.getCurrentArmor(3);
        if (helmet != null) {
            Item item = helmet.getItem();
            if (item != null) {
                helmetAny = true;
                if (!ARMOR_ITEM.isInstance(item)) {
                    layers |= L_HEAD; // block or skull on the head (LayerCustomHead)
                }
            }
        }
        if (layers != 0 && helmetAny) {
            layers |= L_HEAD;
        }
        if (spectator) {
            layers = 0;
            helmetAny = false;
        }
    }

    /** The player's model copy and the model state the game set for it. */
    private static boolean readModel(Object render, Object e) {
        infoIndex = info(render);
        if (infoIndex < 0) {
            return reject(R_MODEL);
        }
        playerInfo = infos[infoIndex];
        if (!playerInfo.layers()) {
            return reject(R_MODEL);
        }
        playerCopy = playerInfo.models[M_PLAYER];
        if (!playerCopy.refresh()) {
            return reject(R_MODEL);
        }
        // The game's model already has what setModelVisibilities sets for this player (skin
        // layers, sneaking...).
        gameModel = playerInfo.mainModel;
        heldLeft = gameModel.heldItemLeft;
        heldRight = gameModel.heldItemRight;
        sneak = gameModel.isSneak;
        bow = gameModel.aimedBow;
        // 1.7 animations (OldAnimations): the 1.7 block and the red armor on damage are hooks in
        // the game's path (ModelBiped, LayerHeldItem, renderLayers), which the pose here does not
        // redo. A player blocking, or in armor and flashing from damage, goes through there for as
        // long as it lasts.
        hurtNow = ((EntityLivingBase) e).hurtTime > 0 || deathTime > 0;
        if (OldAnimations.gamePose(heldRight == 3, hurtNow && wearsArmor(e))) {
            return reject(R_OLD_ANIMATIONS);
        }
        return true;
    }

    /** The draw state that must be the plain one doRender expects; reads it for the states. */
    private static boolean checkDrawState() {
        // Current draw state: what doRender uses or leaves as it is.
        if (!SteinGameState.texture2D(0)
                || SteinGameState.activeUnit() != 0
                || !SteinGameState.colorMaskAll()
                || SteinGameState.polygonOffsetFill()
                || SteinGameState.logicOp()
                || SteinGameState.texture2D(2)
                || !SteinGameState.lighting()
                || !SteinGameState.colorMaterial()
                || SteinGameState.blend()
                || SteinGameState.depthFunc() != GL11.GL_LEQUAL
                || !SteinGameState.depthMask()) {
            return reject(R_STATE);
        }
        alphaRef = -1f; // doRender enables the alpha test; the function is the current one
        int alphaFunc = SteinGameState.alphaFunc();
        if (alphaFunc == GL11.GL_GREATER) {
            alphaRef = SteinGameState.alphaRef();
        } else if (alphaFunc != GL11.GL_ALWAYS) {
            return reject(R_STATE);
        }
        cr = SteinGameState.colorR();
        cg = SteinGameState.colorG();
        cb = SteinGameState.colorB();
        ca = SteinGameState.colorA();
        if (cr < 0f || cg < 0f || cb < 0f || ca < 0f) {
            return reject(R_STATE);
        }
        depthTest = SteinGameState.depthTest();
        lightmap = SteinGameState.texture2D(1);
        fog = SteinGameState.fog();
        lmU = SteinGameState.lightmapU();
        lmV = SteinGameState.lightmapV();
        return true;
    }

    /** The body: skin texture, state and key (the translucent invisible body included). */
    private static boolean addBody(Object render, Object e) {
        if (drawBody) {
            // The skin, bound the way renderModel binds it.
            if (!((Render) render).bindEntityTexture((Entity) e)) {
                return reject(R_TEXTURE);
            }
            int skin = SteinTextureArrays.layer(SteinGameState.textureName(0));
            if (skin < 0) {
                return reject(R_TEXTURE);
            }
            int keyBody;
            if (translucent) {
                // Invisible seen by the team: color 1,1,1,0.15, without writing depth, blend
                // 770/771 (the blendFunc(II) also applies to alpha), alpha > 1/255.
                keyBody =
                        SteinEntityDeferred.poseKey(
                                skin >>> 16,
                                true,
                                GL11.GL_SRC_ALPHA,
                                GL11.GL_ONE_MINUS_SRC_ALPHA,
                                GL11.GL_SRC_ALPHA,
                                GL11.GL_ONE_MINUS_SRC_ALPHA,
                                depthTest,
                                GL11.GL_LEQUAL,
                                false);
                lastR = 1.0F;
                lastG = 1.0F;
                lastB = 1.0F;
                lastA = 0.15F;
                state(
                        s0 + ST_BODY,
                        skin & 0xFFFF,
                        ALPHA_EPSILON,
                        lastR,
                        lastG,
                        lastB,
                        lastA,
                        lmU,
                        lmV,
                        lit,
                        hurtNow,
                        null);
            } else {
                keyBody = opaqueKey(skin, depthTest);
                state(
                        s0 + ST_BODY,
                        skin & 0xFFFF,
                        alphaRef,
                        cr,
                        cg,
                        cb,
                        ca,
                        lmU,
                        lmV,
                        lit,
                        hurtNow,
                        null);
            }
            if (keyBody < 0) {
                return reject(R_STATE);
            }
            jKey[s0 + ST_BODY] = keyBody;
        }
        // After the translucent body renderModel leaves alphaFunc(GREATER, 0.1): what comes next
        // uses that.
        if (translucent) {
            alphaRef = 0.1F;
        }
        return true;
    }

    /** The armor pieces, like LayerArmorBase.renderLayer; leather gets its overlay. */
    private static boolean addArmor(Object render, Object e) throws Throwable {
        // Armor: the pieces like LayerArmorBase.renderLayer (slot 4 helmet .. 1 boots). The
        // spectator has none.
        present = 0;
        leather = 0;
        enchanted = 0;
        LayerBipedArmor layer = playerInfo.armorLayer;
        if (!spectator && Visuals.armorVisible()) {
            boolean skipGlintLayer = layer.skipRenderGlint;
            float lr = layer.colorR;
            float lg = layer.colorG;
            float lb = layer.colorB;
            float la = layer.alpha;
            for (int s = 0; s < 4; s++) {
                int slot = 4 - s;
                ItemStack stack = (ItemStack) GameHooks.armorStack(layer, e, slot);
                if (stack == null) {
                    continue;
                }
                Item item = stack.getItem();
                if (!ARMOR_ITEM.isInstance(item)) {
                    continue;
                }
                int kind = slot == 2 ? M_LEGS : M_ARMOR;
                Model m = playerInfo.models[kind];
                if (!m.refresh()) {
                    // The piece has not been drawn by the game yet (display list to compile).
                    return reject(R_MODEL);
                }
                int ordinal = ((ItemArmor) item).getArmorMaterial().ordinal();
                int mat = ordinal < MATERIAL_CASE.length ? MATERIAL_CASE[ordinal] : 0;
                if (mat < 1 || mat > 5) {
                    // Outside material: the game does not draw the base, only the glint.
                    return reject(R_LAYER);
                }
                boolean legs = slot == 2;
                ((Render) render)
                        .bindTexture((ResourceLocation) GameHooks.armorTexture(layer, item, legs));
                int tex = SteinTextureArrays.layer(SteinGameState.textureName(0));
                if (tex < 0) {
                    return reject(R_TEXTURE);
                }
                int key = opaqueKey(tex, depthTest);
                if (key < 0) {
                    return reject(R_STATE);
                }
                int st = s0 + ST_ARMOR + 2 * s;
                jKey[st] = key;
                if (mat == 1) {
                    // Leather: the dyed base, then the overlay with the layer's color.
                    int c = ((ItemArmor) item).getColor(stack);
                    float r = (float) (c >> 16 & 255) / 255.0F;
                    float g = (float) (c >> 8 & 255) / 255.0F;
                    float b = (float) (c & 255) / 255.0F;
                    state(
                            st,
                            tex & 0xFFFF,
                            alphaRef,
                            lr * r,
                            lg * g,
                            lb * b,
                            la,
                            lmU,
                            lmV,
                            lit,
                            false,
                            null);
                    ((Render) render)
                            .bindTexture(
                                    (ResourceLocation)
                                            GameHooks.armorTextureOverlay(
                                                    layer, item, legs, "overlay"));
                    int over = SteinTextureArrays.layer(SteinGameState.textureName(0));
                    if (over < 0) {
                        return reject(R_TEXTURE);
                    }
                    int overlayKey = opaqueKey(over, depthTest);
                    if (overlayKey < 0) {
                        return reject(R_STATE);
                    }
                    jKey[st + 1] = overlayKey;
                    state(
                            st + 1,
                            over & 0xFFFF,
                            alphaRef,
                            lr,
                            lg,
                            lb,
                            la,
                            lmU,
                            lmV,
                            lit,
                            false,
                            null);
                    leather |= 1 << s;
                } else {
                    state(st, tex & 0xFFFF, alphaRef, lr, lg, lb, la, lmU, lmV, lit, false, null);
                }
                lastR = lr;
                lastG = lg;
                lastB = lb;
                lastA = la;
                present |= 1 << s;
                if (!skipGlintLayer && stack.isItemEnchanted()) {
                    enchanted |= 1 << s;
                }
            }
        }
        return true;
    }

    /** The enchantment glint of the armor: two passes with the rotating texture matrix. */
    private static boolean addGlint(Object render, Object e, float pt) {
        glint = enchanted != 0 && !Visuals.glintSkipped();
        if (glint) {
            // The glint (ArmorGlint / renderGlint): glint texture, no light, fixed color, blend
            // SRC_COLOR + ONE, depth EQUAL without writing; two passes with the texture matrix
            // rotating over time.
            ((Render) render).bindTexture(GLINT_RES);
            int gtex = SteinTextureArrays.layer(SteinGameState.textureName(0));
            if (gtex < 0) {
                return reject(R_TEXTURE);
            }
            // vanilla's blendFunc(768, 1) also applies to alpha: fixed alpha factors.
            int gkey =
                    SteinEntityDeferred.poseKey(
                            gtex >>> 16,
                            true,
                            GL11.GL_SRC_COLOR,
                            GL11.GL_ONE,
                            GL11.GL_SRC_COLOR,
                            GL11.GL_ONE,
                            depthTest,
                            GL11.GL_EQUAL,
                            false);
            if (gkey < 0) {
                return reject(R_STATE);
            }
            float time = (float) ((Entity) e).ticksExisted + pt;
            float k = GLINT_BRIGHTNESS;
            float gr = 0.5F * k;
            float gg = 0.25F * k;
            float gb = 0.8F * k;
            int unlit = SteinEntityDeferred.poseFlags(false, lightmap, fog);
            float[] t = TEX;
            for (int i = 0; i < 2; i++) {
                Mat4.identity(t);
                float third = GLINT_SCALE;
                Mat4.scale(t, third, third, third);
                Mat4.rotate(t, 30.0F - (float) i * 60.0F, 0.0F, 0.0F, 1.0F);
                Mat4.translate(t, 0.0F, time * (0.001F + (float) i * 0.003F) * 20.0F, 0.0F);
                state(
                        s0 + ST_GLINT + i,
                        gtex & 0xFFFF,
                        alphaRef,
                        gr,
                        gg,
                        gb,
                        1.0F,
                        lmU,
                        lmV,
                        unlit,
                        false,
                        t);
                jKey[s0 + ST_GLINT + i] = gkey;
            }
            lastR = gr;
            lastG = gg;
            lastB = gb;
            lastA = 1.0F;
        }
        return true;
    }

    /** The held item through the pose, if whitelisted and with a recorded template. */
    private static void decideHeldItem(Object e) {
        // The held item (SteinHeldItem): if whitelisted and with a ready template, it becomes
        // states (the template's draws, with this player's lightmap and the current glint texture
        // matrix) and a pose part, attached to the right arm; with no template yet, it goes through
        // the game's layer, which records the template.
        held = H_GAME;
        heldEntry = null;
        itemFlags = 0;
        if ((layers & L_HELD) != 0 && SteinHeldItem.ON && !CHECK && !heldOff) {
            try {
                heldEntry = SteinHeldItem.decide(e, ((EntityLivingBase) e).getHeldItem());
            } catch (Throwable ex) {
                heldOff = true;
                heldEntry = null;
                reportFailure("held item through the pose disabled (error while deciding)", ex);
            }
            if (heldEntry != null && heldEntry.state != SteinHeldItem.Entry.REJECTED) {
                held =
                        heldEntry.state == SteinHeldItem.Entry.READY && !SteinHeldItem.CHECK
                                ? H_OURS
                                : H_RECORD;
                itemFlags =
                        heldEntry.flags
                                | (playerCopy.parts[RARM].showModel && !playerCopy.hidden[RARM]
                                        ? SteinHeldItem.IF_ARM
                                        : 0)
                                | (gameModel.smallArms ? SteinHeldItem.IF_SMALL : 0)
                                | (renderSneak ? SteinHeldItem.IF_SNEAK : 0);
            } else {
                heldEntry = null;
            }
            if (held == H_OURS) {
                layers &= ~L_HELD;
                for (int d = 0; d < heldEntry.draws(); d++) {
                    int st = s0 + ST_ITEM + d;
                    System.arraycopy(heldEntry.rest, d * REST, jState, st * REST, REST);
                    jState[st * REST + 12] = lmU;
                    jState[st * REST + 13] = lmV;
                    if (d > 0) {
                        SteinHeldItem.glintTex(d - 1, jState, st * REST);
                    }
                    jKey[st] = heldEntry.key[d];
                }
            }
        }
    }

    /** The plan: each instance (part, state), in the game's order. */
    private static void buildPlan() {
        int at = planN;
        if ((at + MAX_PLAN) * 2 > plan.length) {
            plan = Arrays.copyOf(plan, plan.length * 2);
        }
        int pn = at;
        int models = 1 << M_PLAYER;
        for (int i = 0; i < PLAYER_PARTS && drawBody; i++) {
            if (playerCopy.parts[i].showModel && !playerCopy.hidden[i]) {
                plan[pn * 2] = M_PLAYER * 16 + i;
                plan[pn * 2 + 1] = ST_BODY;
                pn++;
            }
        }
        for (int s = 0; s < 4; s++) {
            if ((present & 1 << s) == 0) {
                continue;
            }
            int kind = s == 2 ? M_LEGS : M_ARMOR;
            models |= 1 << kind;
            pn = piece(pn, playerInfo.models[kind], kind, SLOT_MASK[s], ST_ARMOR + 2 * s);
            if ((leather & 1 << s) != 0) {
                pn = piece(pn, playerInfo.models[kind], kind, SLOT_MASK[s], ST_ARMOR + 2 * s + 1);
            }
        }
        if (glint) {
            for (int i = 0; i < 2; i++) {
                for (int s = 0; s < 4; s++) {
                    if ((enchanted & 1 << s) != 0) {
                        int kind = s == 2 ? M_LEGS : M_ARMOR;
                        pn = piece(pn, playerInfo.models[kind], kind, SLOT_MASK[s], ST_GLINT + i);
                    }
                }
            }
        }
        if (held == H_OURS) {
            for (int d = 0; d < heldEntry.draws(); d++) {
                plan[pn * 2] = ITEM_PART;
                plan[pn * 2 + 1] = ST_ITEM + d;
                pn++;
            }
        }
        planAt = at;
        planEnd = pn;
        modelBits = models;
    }

    /** All good: the player enters the list. */
    private static void publishJob(Object e, float pt) {
        jHeld[j] = held;
        jHeldEntry[j] = heldEntry;
        jItemFlags[j] = itemFlags;
        jEntity[j] = e;
        jInfo[j] = infoIndex;
        System.arraycopy(GLMatrix.current(), 0, jBase, j * 16, 16);
        jPos[j * 3] = px;
        jPos[j * 3 + 1] = py;
        jPos[j * 3 + 2] = pz;
        jCorpse[j] = corpse;
        jCorpseF[j * 2] = corpse0;
        jCorpseF[j * 2 + 1] = corpse1;
        jMount[j] = mount;
        jStates[j] =
                (renderSneak ? S_SNEAK : 0)
                        | (riding ? S_RIDING : 0)
                        | (spectator ? S_SPECTATOR : 0)
                        | (!visible ? (translucent ? S_TRANSLUCENT : S_HIDDEN) : 0)
                        | (corpse == C_SLEEP
                                ? S_SLEEP
                                : corpse == C_DEATH ? S_DEATH : corpse == C_FLIP ? S_FLIP : 0);
        jPt[j] = pt;
        jModel[j * 4] = sneak ? 1 : 0;
        jModel[j * 4 + 1] = heldLeft;
        jModel[j * 4 + 2] = heldRight;
        jModel[j * 4 + 3] = bow ? 1 : 0;
        jModels[j] = modelBits;
        jLayers[j] = layers;
        jPlanAt[j] = planAt;
        jPlanLen[j] = planEnd - planAt;
        planN = planEnd;
        jobs = j + 1;
        if (!CHECK) {
            endR = lastR;
            endG = lastG;
            endB = lastB;
            endA = lastA;
            endColor = present != 0 || glint || translucent;
            endWhite = helmetAny;
            // The blend that remains (off): the glint's, if there was one; otherwise the
            // translucent body's.
            endBlendSrc = glint ? GL11.GL_SRC_COLOR : translucent ? GL11.GL_SRC_ALPHA : 0;
            endBlendDst = glint ? GL11.GL_ONE : GL11.GL_ONE_MINUS_SRC_ALPHA;
            endAlpha01 = translucent;
        }
    }

    private static int piece(int pn, Model m, int kind, int mask, int st) {
        for (int i = 0; i < BIPED_PARTS; i++) {
            if ((mask & 1 << i) != 0 && !m.hidden[i]) {
                plan[pn * 2] = kind * 16 + i;
                plan[pn * 2 + 1] = st;
                pn++;
            }
        }
        return pn;
    }

    /**
     * An instance's "rest", like SteinEntityDeferred's putRest: texture, color, lightmap, damage.
     */
    private static void state(
            int st,
            int layer,
            float alphaRef,
            float r,
            float g,
            float b,
            float a,
            float lmU,
            float lmV,
            int flags,
            boolean hurt,
            float[] t) {
        float[] s = jState;
        int o = st * REST;
        s[o] = t == null ? 1f : t[0];
        s[o + 1] = t == null ? 0f : t[4];
        s[o + 2] = t == null ? 0f : t[12];
        s[o + 3] = layer;
        s[o + 4] = t == null ? 0f : t[1];
        s[o + 5] = t == null ? 1f : t[5];
        s[o + 6] = t == null ? 0f : t[13];
        s[o + 7] = alphaRef;
        s[o + 8] = r;
        s[o + 9] = g;
        s[o + 10] = b;
        s[o + 11] = a;
        s[o + 12] = lmU;
        s[o + 13] = lmV;
        s[o + 14] = flags;
        s[o + 15] = 0f;
        // The damage red (setDoRenderBrightness): only on the body; the player's layers do not
        // combine it.
        s[o + 16] = hurt ? 1f : 0f;
        s[o + 17] = 0f;
        s[o + 18] = 0f;
        s[o + 19] = hurt ? 0.3f : 0f;
    }

    /** The state key of an opaque, depth-tested draw of the texture layer in {@code packed}. */
    private static int opaqueKey(int packed, boolean depthTest) {
        return SteinEntityDeferred.poseKey(
                packed >>> 16, false, 0, 0, 0, 0, depthTest, GL11.GL_LEQUAL, true);
    }

    /** Something worn (armor, pumpkin, skull): the armor layer has something to paint red. */
    private static boolean wearsArmor(Object e) {
        for (int slot = 0; slot < 4; slot++) {
            if (((EntityLivingBase) e).getCurrentArmor(slot) != null) {
                return true;
            }
        }
        return false;
    }

    private static boolean reject(int why) {
        rejects[why]++;
        return false;
    }
}
