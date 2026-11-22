/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import dev.xavier.stein.bridge.engine.FontBatch;
import dev.xavier.stein.bridge.engine.NameStyle;
import dev.xavier.stein.bridge.engine.Visuals;
import dev.xavier.stein.bridge.hooks.GameHooks;
import dev.xavier.stein.gfx.SteinLabelSlots.Slot;
import dev.xavier.stein.gfx.SteinLabelSlots.Text;
import dev.xavier.stein.gfx.SteinLabelTemplates.Template;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.Render;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

/**
 * The whole label without the surrounding game code: in the name drain ({@link
 * SteinEntityDeferred#drainNames}), the name of a living entity in the common case (the game's
 * RendererLivingEntity.renderName and Render.renderLivingLabel, with no renderer overriding them)
 * comes out directly as the label record plus the commands of the resident runs ({@link
 * SteinTextGpu}) — without GlStateManager's state changes, the background Tessellator, the two
 * FontRenderer calls and the text lookup in the cache.
 *
 * <p><b>What still runs every frame, as in the game:</b> canRenderName (through {@code Visuals}),
 * the distance (32 sneaking, 64 standing), getDisplayName with the Loader's and the mods' name
 * hooks ({@code GameHooks.nametag}) and the scoreboard line below RenderPlayer's name (with the
 * game's getValueFromObjective). Nothing is stored per tick.
 *
 * <p><b>What comes out directly:</b> the matrix is built by the same game calls to {@code GLMatrix}
 * (translate, the two camera rotates, scale; sneaking, the 9.374999 translate), with the same
 * floats; the background is the quad the Tessellator would give (text width, deadmau5); the text is
 * the cache assembly at the same position and input color as the two passes (see-through 553648127,
 * normal -1; sneaking, a single pass).
 *
 * <p><b>The template.</b> The group keys of each pass (blending, depth, options) and the
 * matrix-less record come from the game's own path: the first name of each kind (standing,
 * sneaking) after the start of the drain, or after a name that went through the game (a mod
 * renderer may change any state), is drawn by the game with the commitTexts recorded ({@link
 * SteinEntityDeferred#probeBegin}), and the template comes out of that. The following ones of the
 * same kind use the template. The state the label leaves in GlStateManager does not depend on the
 * input (except depth, which sneaking does not touch and standing always re-enables), so the final
 * state of the drain is the same; the bound texture is the last run's and the font is left as the
 * last renderString would leave it ({@link SteinTextCache#leaveFont}).
 *
 * <p>Anything outside the common case (text not in the cache or not yet resident, §k, bidi, Stein's
 * font, non-resident background, WorldRenderer with an offset) goes through the game's path from
 * that point on, with the name already computed (the hooks do not run twice).
 *
 * <p><b>A style</b> ({@link NameStyle}, set by a mod): the same direct label, with the style's
 * background shape ({@link SteinLabelShape}), text color and shadow, size and height; the game path
 * it learns from is {@link SteinLabelTail}'s styled copy of the game's calls. Where the direct
 * label cannot be used (Stein's font, bidi, no resident text on this driver) and outside the
 * deferred pass (the fixed-function pipeline), the styled game path draws the label ({@link
 * #drawImmediate}), so the style looks the same everywhere. Only renderers that change the name
 * themselves (kind 0) keep the game's label.
 *
 * <p>{@code -Dsteinbridge.gfx.gpuLabel=false} turns it off (the whole name through the game, as
 * before; a style still goes through the styled game path). Check: {@code
 * -Dsteinbridge.gfx.gpuLabelCheck=true} generates the direct label without recording it and
 * compares it with what the game path records (keys, runs, records byte by byte), drawing through
 * the game; the result is output every 5 s.
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinNameLabels {

    public static final boolean REQUESTED = SteinGfxOptions.GPU_LABEL;

    /** See {@link SteinLabelCheck}. */
    static final boolean CHECK = SteinGfxOptions.GPU_LABEL_CHECK;

    static final int LABEL = 0;
    static final int SNEAK = 1;

    /** The two passes of the name text: see-through (alpha 0x20) and the normal one. */
    static final int COLOR_THROUGH = 553648127;

    static final float ALPHA_THROUGH = (COLOR_THROUGH >> 24 & 255) / 255.0F;
    static final float LABEL_SCALE = 0.016666668F * 1.6F;
    static final float SNEAK_SCALE = 0.02666667F;

    /** The label's vertical offset when sneaking (game constant, same float). */
    static final float SNEAK_OFFSET_Y = 9.374999F;

    /**
     * A label ready to come out directly: the text, the position, the background, the assemblies
     * and the run keys.
     */
    static final class Label {
        int kind;
        float tx;
        float ty;
        float tz;

        /** Null: no background (a style without one). */
        SteinTextGpu.Alloc background;

        /** [0] see-through, [1] normal, [2] the normal pass's shadow (null without one). */
        final Object[] entry = new Object[3];

        long[][] keys = {new long[4], new long[4], new long[4]};
    }

    static final Label[] labels = {new Label(), new Label()};

    /**
     * The font and the last assembly drawn directly: the font is left as the last renderString
     * would leave it.
     */
    private static FontRenderer pendingFont;

    private static Object pendingEntry;

    private SteinNameLabels() {}

    /** Start of the drain: the templates are valid from the current state onward. */
    static void begin() {
        SteinLabelTemplates.invalidate();
        SteinLabelSlots.nextDrain();
    }

    /**
     * A name went through the whole game path (another renderer): it may have changed any state.
     */
    static void slow() {
        leave();
        SteinLabelTemplates.invalidate();
    }

    /** End of the drain. */
    static void end() {
        leave();
        SteinLabelContext.clear();
        SteinLabelStats.report();
    }

    private static void leave() {
        if (pendingEntry != null) {
            SteinTextCache.leaveFont(pendingFont, pendingEntry);
            pendingEntry = null;
            pendingFont = null;
        }
    }

    /**
     * The name of {@code entity} through renderer {@code render} at position (x, y, z) relative to
     * the camera. False = not the common case: the caller draws through the game (and calls {@link
     * #slow}). True = done (directly, through the game path from the computed name onward, or
     * nothing to draw).
     */
    static boolean draw(Object render, Object entity, double x, double y, double z)
            throws Throwable {
        boolean styled = NameStyle.custom();
        boolean gpu = REQUESTED && SteinTextGpu.REQUESTED && SteinTextGpu.active();
        if (!gpu && !styled) {
            return false;
        }
        int kind = SteinLabelKinds.of(render.getClass());
        if (kind == 0) {
            return false;
        }
        FontRenderer font = ((Render) render).getFontRendererFromRenderManager();
        boolean direct = gpu && !SteinOwnText.active() && !SteinTextCache.bidi(font);
        if (!direct && !styled) {
            return false;
        }
        if (!name(kind, render, entity, font, true, x, y, z)) {
            return true;
        }
        Slot slot = SteinLabelContext.slot;
        // From here on the name has already been computed (the mods' hooks ran): the game, if
        // needed, continues with it.
        boolean sneak = SteinLabelContext.sneak;
        if (!direct) {
            // a style the direct label cannot draw here: the styled game path
            leave();
            SteinLabelTail.tail();
            SteinLabelStats.viaGame++;
            return true;
        }
        int n = prepare(slot);
        Template t = SteinLabelTemplates.templates[sneak ? SNEAK : LABEL];
        if (CHECK) {
            SteinLabelCheck.check(n, t);
            return true;
        }
        if (n > 0 && t.valid && t.style == NameStyle.version()) {
            for (int i = 0; i < n; i++) {
                SteinLabelEmit.emit(labels[i], t, null);
            }
            pendingFont = font;
            pendingEntry =
                    labels[n - 1].entry[sneak ? 0 : 1]; // the one from the name's last renderString
            SteinLabelStats.direct++;
            return true;
        }
        leave();
        SteinEntityDeferred.probeBegin();
        try {
            SteinLabelTail.tail();
        } finally {
            int c = SteinEntityDeferred.probeEnd();
            if (c > 0) {
                SteinLabelTemplates.learn(sneak ? SNEAK : LABEL, c);
            }
        }
        SteinLabelStats.viaGame++;
        return true;
    }

    /**
     * RendererLivingEntity.renderName, as in the game up to the name: whether it shows (the game's
     * canRenderName, with the Loader's hooks and the style's own label), the distance (32 sneaking,
     * 64 standing) and the name with the mods' hooks, without formatting codes when the style asks.
     * True = there is a label, in {@link SteinLabelContext}; false = nothing to draw. Without
     * {@code keep} (outside the drain) the name is not kept per entity.
     */
    private static boolean name(
            int kind,
            Object render,
            Object entity,
            FontRenderer font,
            boolean keep,
            double x,
            double y,
            double z)
            throws Throwable {
        if (!Visuals.canRenderName(render, entity)) {
            return false;
        }
        Entity ent = (Entity) entity;
        RenderManager rm = ((Render) render).renderManager;
        double d = ent.getDistanceSqToEntity(rm.livingPlayer);
        boolean sneak = ent.isSneaking();
        float f = sneak ? 32.0F : 64.0F;
        if (d >= (double) (f * f)) {
            return false;
        }
        Object component = GameHooks.nametag(entity);
        Slot slot = keep ? SteinLabelSlots.slot(entity) : null;
        String name =
                slot != null
                        ? SteinLabelSlots.formatted(slot, component)
                        : ((IChatComponent) component).getFormattedText();
        if (NameStyle.custom() && NameStyle.plainText()) {
            name =
                    slot != null
                            ? SteinLabelSlots.plainName(slot, name)
                            : EnumChatFormatting.getTextWithoutFormattingCodes(name);
        }
        boolean unicode = SteinTextCache.unicode(font);
        SteinLabelContext.set(kind, sneak, render, entity, rm, font, unicode, name, x, y, z, d);
        SteinLabelContext.slot = slot;
        return true;
    }

    /**
     * Render.renderName outside the deferred pass (the fixed-function pipeline, or a name drawn
     * outside the world's entities) with a style: the styled game path. False = the game's own
     * renderName (no style, or a renderer that changes the name itself).
     */
    static boolean drawImmediate(Object render, Object entity, double x, double y, double z)
            throws Throwable {
        if (!NameStyle.custom()) {
            return false;
        }
        int kind = SteinLabelKinds.of(render.getClass());
        if (kind == 0) {
            return false;
        }
        FontRenderer font = ((Render) render).getFontRendererFromRenderManager();
        if (name(kind, render, entity, font, false, x, y, z)) {
            SteinLabelTail.tail();
        }
        SteinLabelContext.clear();
        return true;
    }

    /**
     * Prepares the name's labels (scoreboard line and name, or just the name; sneaking, the
     * sneaking label): texts, resident backgrounds and the runs' textures (bound in the game's
     * order). 0 = something outside the common case. Reads the name in progress from {@link
     * SteinLabelContext}.
     */
    private static int prepare(Slot slot) {
        int kind = SteinLabelContext.kind;
        boolean sneak = SteinLabelContext.sneak;
        Entity entity = (Entity) SteinLabelContext.entity;
        FontRenderer font = (FontRenderer) SteinLabelContext.font;
        boolean unicode = SteinLabelContext.unicode;
        String name = SteinLabelContext.name;
        double x = SteinLabelContext.x;
        double y = SteinLabelContext.y;
        double z = SteinLabelContext.z;
        double d = SteinLabelContext.d;
        float h = entity.height;
        boolean child = ((EntityLivingBase) entity).isChild();
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        if (wr.noColor || wr.xOffset != 0.0 || wr.yOffset != 0.0 || wr.zOffset != 0.0) {
            return 0; // the background Tessellator would not give the usual quad
        }
        TextureManager tm = SteinTextCache.textureManager(font);
        boolean bg = !NameStyle.custom() || NameStyle.background();
        if (sneak) {
            Text t = SteinLabelSlots.text(slot.name, name, font, unicode);
            Label l = labels[0];
            l.kind = SNEAK;
            l.tx = (float) x;
            l.ty = SteinLabelGeometry.sneakTop(y, h, child);
            l.tz = (float) z;
            int i = t.width / 2;
            l.background = bg ? SteinTextBackgrounds.labelBackground(i, -1, 8) : null;
            l.entry[0] = SteinLabelSlots.entry(t, 2, (float) (-t.width / 2), 0f);
            l.entry[1] = null;
            l.entry[2] = null;
            return (!bg || l.background != null) && l.entry[0] != null && bind(tm, l, 0) ? 1 : 0;
        }
        double yy = y - (child ? (double) (h / 2.0F) : 0.0);
        int n = 0;
        if (kind == SteinLabelKinds.PLAYER) {
            // RenderPlayer.renderOffsetLivingLabel: the scoreboard line below the name, through the
            // game's scoreboard
            String s = SteinLabelGeometry.scoreLine(entity, d, slot);
            if (s != null) {
                if (!label(
                        labels[n++],
                        SteinLabelSlots.text(slot.score, s, font, unicode),
                        tm,
                        bg,
                        h,
                        x,
                        yy,
                        z)) {
                    return 0;
                }
                yy += SteinLabelGeometry.scoreStep(font);
            }
        }
        if (!label(
                labels[n++],
                SteinLabelSlots.text(slot.name, name, font, unicode),
                tm,
                bg,
                h,
                x,
                yy,
                z)) {
            return 0;
        }
        return n;
    }

    /**
     * Render.renderLivingLabel (always within the 64 range: renderName already cut off the greater
     * distance).
     */
    private static boolean label(
            Label l, Text t, TextureManager tm, boolean bg, float h, double x, double y, double z) {
        l.kind = LABEL;
        l.tx = (float) x + 0.0F;
        l.ty = SteinLabelGeometry.labelTop(y, h);
        l.tz = (float) z;
        int i = t.s.equals("deadmau5") ? -10 : 0;
        int j = t.width / 2;
        l.background = bg ? SteinTextBackgrounds.labelBackground(j, -1 + i, 8 + i) : null;
        float tx = (float) (-t.width / 2);
        l.entry[0] = SteinLabelSlots.entry(t, 0, tx, (float) i);
        l.entry[1] = SteinLabelSlots.entry(t, 1, tx, (float) i);
        // FontRenderer.drawString with the shadow: renderString at (x + 1, y + 1) first
        boolean shadow = NameStyle.custom() && NameStyle.shadow();
        l.entry[2] = shadow ? SteinLabelSlots.entry(t, 3, tx + 1.0F, (float) i + 1.0F) : null;
        return (!bg || l.background != null)
                && l.entry[0] != null
                && l.entry[1] != null
                && (!shadow || l.entry[2] != null)
                && bind(tm, l, 0)
                && (!shadow || bind(tm, l, 2))
                && bind(tm, l, 1);
    }

    /**
     * Binds the texture of each run of the pass (as the FontRenderer would) and stores each one's
     * texture.
     */
    private static boolean bind(TextureManager tm, Label l, int pass) {
        Object e = l.entry[pass];
        int runs = SteinTextCache.runs(e);
        if (l.keys[pass].length < runs) {
            l.keys[pass] = new long[runs * 2];
        }
        for (int r = 0; r < runs; r++) {
            if (SteinTextCache.runLocation(e, r) instanceof SteinOwnText.Texture) {
                return false; // Stein font batch (does not happen with names): through the game
            }
            FontBatch.bindTexture(tm, SteinTextCache.runLocation(e, r));
            int tex = SteinGameState.textureName(0);
            if (tex <= 0) {
                return false;
            }
            l.keys[pass][r] = tex;
        }
        return true;
    }
}
