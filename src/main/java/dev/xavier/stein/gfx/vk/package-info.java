/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

/**
 * Vulkan backend of Stein GFX. <b>Experimental.</b>
 *
 * <p>It implements the same device API as the OpenGL backend ({@code dev.xavier.stein.gfx.gl}) and
 * runs the Stein GFX self-tests, but the game does not run on it yet: releases ship with OpenGL
 * only, and nothing outside this package selects it. It lives here so the renderer is designed
 * against two backends from the start. Expect it to change.
 */
package dev.xavier.stein.gfx.vk;
