/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.gfx;

import java.util.HashMap;
import java.util.Map;

/**
 * Pipelines by description: the same state requested twice is the same pipeline (compiled only
 * once). On Vulkan, this is where the on-disk pipeline cache comes in.
 *
 * <p>Pipeline: Stein GFX only.
 */
public final class SteinPipelineCache {

    private final SteinDevice device;
    private final Map<SteinPipeline.Desc, SteinPipeline> pipelines = new HashMap<>();

    public SteinPipelineCache(SteinDevice device) {
        this.device = device;
    }

    public SteinPipeline get(SteinPipeline.Desc desc) {
        SteinPipeline p = pipelines.get(desc);
        if (p == null) {
            p = device.createPipeline(desc);
            pipelines.put(desc, p);
        }
        return p;
    }

    public void clear() {
        for (SteinPipeline p : pipelines.values()) {
            p.destroy();
        }
        pipelines.clear();
    }
}
