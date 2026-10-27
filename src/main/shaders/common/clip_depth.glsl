// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.
//
// Copyright (c) 2026 Breno Xavier

// The game's projections give OpenGL's clip depth (-w to w). Vulkan keeps 0 to w unless the device has
// VK_EXT_depth_clip_control; without it (older drivers, Intel's among them) the near half was clipped away, and the
// GUI's items and the player in the inventory, drawn there, disappeared. The pipeline then sets this constant and the
// depth is moved into Vulkan's range in the vertex shader (SteinVkPipeline). OpenGL never sets it.
layout(constant_id = 0) const bool DEPTH_ZERO_TO_ONE = false;

vec4 clipDepth(vec4 p) {
    if (DEPTH_ZERO_TO_ONE) {
        p.z = (p.z + p.w) * 0.5;
    }
    return p;
}
