/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import static dev.xavier.stein.gfx.SteinNameLabels.SNEAK_OFFSET_Y;

import dev.xavier.stein.bridge.engine.GLMatrix;
import dev.xavier.stein.gfx.SteinLabelTemplates.Template;
import dev.xavier.stein.gfx.SteinNameLabels.Label;

import net.minecraft.client.renderer.entity.RenderManager;

/**
 * Writes the direct label: the record (template + matrix and lightmap) and the commands of the
 * background and the passes — or, in the check, only describes them.
 *
 * <p>Pipeline: Stein GFX only.
 */
final class SteinLabelEmit {

    private SteinLabelEmit() {}

    /**
     * The label's matrix, through the same game calls, on top of the stack (the caller pops it).
     */
    private static void matrix(Label l, RenderManager rm) {
        GLMatrix.pushMatrix();
        GLMatrix.translate(l.tx, l.ty, l.tz);
        GLMatrix.rotate(-rm.playerViewY, 0.0F, 1.0F, 0.0F);
        GLMatrix.rotate(rm.playerViewX, 1.0F, 0.0F, 0.0F);
        if (l.kind == SteinNameLabels.SNEAK) {
            float s = SteinLabelGeometry.sneakScale();
            GLMatrix.scale(-s, -s, s);
            GLMatrix.translate(0.0F, SNEAK_OFFSET_Y, 0.0F);
        } else {
            float s = SteinLabelGeometry.labelScale();
            GLMatrix.scale(-s, -s, s);
        }
    }

    /**
     * The direct label: the record (template + matrix and lightmap) and the commands of the
     * background and the passes. With {@code dry} (check), nothing is recorded: each command goes
     * to {@code dry} (key, first, vertices; the record in {@link SteinLabelCheck#dryRecord}).
     */
    static void emit(Label l, Template t, long[] dry) {
        matrix(l, (RenderManager) SteinLabelContext.manager);
        int inst;
        try {
            if (dry != null) {
                SteinEntityDeferred.labelFill(
                        t.record,
                        SteinLabelCheck.dryRecord
                                + (long) SteinLabelCheck.dryLabels
                                        * SteinEntityDeferred.RECORD_BYTES);
                inst = SteinLabelCheck.dryLabels++;
            } else {
                inst = SteinEntityDeferred.labelRecord(t.record);
            }
        } finally {
            GLMatrix.popMatrix();
        }
        if (inst < 0) {
            return;
        }
        if (l.background != null) {
            command(dry, t.bgKey, l.background.firstVertex(), l.background.vertices(), inst);
        }
        int passes = l.kind == SteinNameLabels.SNEAK ? 1 : 2;
        for (int p = 0; p < passes; p++) {
            if (p == 1 && l.entry[2] != null) {
                // a style's shadow: drawn by the normal pass's drawString, first, in its state
                runs(l, 2, t.textBase[1], inst, dry);
            }
            runs(l, p, t.textBase[p], inst, dry);
        }
        if (dry == null && l.background != null) {
            SteinTextGpu.backgroundDrawn(l.background);
        }
    }

    /** The commands of assembly {@code which} of the label, one per run, with the pass's key. */
    private static void runs(Label l, int which, long base, int inst, long[] dry) {
        Object e = l.entry[which];
        int runs = SteinTextCache.runs(e);
        for (int r = 0; r < runs; r++) {
            long key = SteinEntityDeferred.textKeyWith(base, (int) l.keys[which][r]);
            command(dry, key, SteinTextCache.runFirst(e, r), SteinTextCache.runCount(e, r), inst);
        }
        if (dry == null) {
            SteinTextCache.residentDrawn(e);
        }
    }

    private static void command(long[] dry, long key, int first, int verts, int inst) {
        if (dry == null) {
            SteinEntityDeferred.labelCommand(key, first, verts, inst);
            return;
        }
        if (SteinLabelCheck.dryN * 4 + 4 > dry.length) {
            return;
        }
        dry[SteinLabelCheck.dryN * 4] = key;
        dry[SteinLabelCheck.dryN * 4 + 1] = first;
        dry[SteinLabelCheck.dryN * 4 + 2] = verts;
        dry[SteinLabelCheck.dryN * 4 + 3] = inst;
        SteinLabelCheck.dryN++;
    }
}
