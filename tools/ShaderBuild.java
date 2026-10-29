/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

import static org.lwjgl.util.shaderc.Shaderc.shaderc_compilation_status_success;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_into_spv;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_add_macro_definition;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_initialize;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_release;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_generate_debug_info;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_optimization_level;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_source_language;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_target_env;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_initialize;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_release;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_env_version_vulkan_1_2;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_compute_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_fragment_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_glsl_vertex_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_optimization_level_zero;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_bytes;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_compilation_status;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_error_message;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_release;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_source_language_glsl;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_target_env_vulkan;
import static org.lwjgl.util.spvc.Spvc.SPVC_BACKEND_GLSL;
import static org.lwjgl.util.spvc.Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP;
import static org.lwjgl.util.spvc.Spvc.SPVC_COMPILER_OPTION_GLSL_ENABLE_420PACK_EXTENSION;
import static org.lwjgl.util.spvc.Spvc.SPVC_COMPILER_OPTION_GLSL_ES;
import static org.lwjgl.util.spvc.Spvc.SPVC_COMPILER_OPTION_GLSL_VERSION;
import static org.lwjgl.util.spvc.Spvc.SPVC_COMPILER_OPTION_GLSL_VULKAN_SEMANTICS;
import static org.lwjgl.util.spvc.Spvc.SPVC_RESOURCE_TYPE_ACCELERATION_STRUCTURE;
import static org.lwjgl.util.spvc.Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE;
import static org.lwjgl.util.spvc.Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER;
import static org.lwjgl.util.spvc.Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER;
import static org.lwjgl.util.spvc.Spvc.SPVC_SUCCESS;
import static org.lwjgl.util.spvc.Spvc.spvc_compiler_compile;
import static org.lwjgl.util.spvc.Spvc.spvc_compiler_create_compiler_options;
import static org.lwjgl.util.spvc.Spvc.spvc_compiler_create_shader_resources;
import static org.lwjgl.util.spvc.Spvc.spvc_compiler_get_decoration;
import static org.lwjgl.util.spvc.Spvc.spvc_compiler_get_name;
import static org.lwjgl.util.spvc.Spvc.spvc_compiler_get_type_handle;
import static org.lwjgl.util.spvc.Spvc.spvc_compiler_install_compiler_options;
import static org.lwjgl.util.spvc.Spvc.spvc_compiler_options_set_bool;
import static org.lwjgl.util.spvc.Spvc.spvc_compiler_options_set_uint;
import static org.lwjgl.util.spvc.Spvc.spvc_context_create;
import static org.lwjgl.util.spvc.Spvc.spvc_context_create_compiler;
import static org.lwjgl.util.spvc.Spvc.spvc_context_destroy;
import static org.lwjgl.util.spvc.Spvc.spvc_context_get_last_error_string;
import static org.lwjgl.util.spvc.Spvc.spvc_context_parse_spirv;
import static org.lwjgl.util.spvc.Spvc.spvc_resources_get_resource_list_for_type;
import static org.lwjgl.util.spvc.Spvc.spvc_type_get_image_arrayed;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shaders of the Stein graphics API: a single source (Vulkan-style GLSL, in src/main/shaders) ->
 * SPIR-V (shaderc) -> OpenGL GLSL (SPIRV-Cross). Runs in the build (./gradlew shaders, part of
 * ./gradlew build), with shaderc and SPIRV-Cross from LWJGL (Gradle downloads them); the game only
 * reads the result: {@code <output>.glsl} (OpenGL) and {@code <output>.spv} (the Vulkan backend,
 * later), in {@code stein/shaders/} inside the jar.
 *
 * <p>The generated GLSL is 410 core, without {@code layout(binding)}: the OpenGL backend binds
 * textures and uniform blocks by name at every level (SteinGlPipeline.bindSlots), and the same text
 * serves both the 4.6 path and the Mac one (4.1).
 *
 * <p>Usage (the shaders task passes them): {@code ShaderBuild <src/main/shaders> <output>}. Reads
 * {@code shaders.list} (output, stage, source, definitions); {@code #include "x"} is resolved as
 * text, relative to the sources folder.
 */
public final class ShaderBuild {

    /** GLSL version of the generated compute shaders (storage buffers need 430). */
    private static final int GLSL_VERSION_COMPUTE = 430;

    /** GLSL version of the other generated shaders (what GL 4.1 compiles). */
    private static final int GLSL_VERSION_DEFAULT = 410;

    private static final int MAX_INCLUDE_DEPTH = 8;

    /** The generated GLSL and the resource reflection text. */
    private record CrossResult(String glsl, String reflection) {}

    private static final Pattern INCLUDE =
            Pattern.compile("^\\s*#include\\s+\"([^\"]+)\"\\s*$", Pattern.MULTILINE);

    public static void main(String[] args) throws IOException {
        Path src = Path.of(args[0]);
        Path out = Path.of(args[1]);
        Files.createDirectories(out);
        long compiler = shaderc_compiler_initialize();
        if (compiler == 0L) {
            throw new IllegalStateException("shaderc failed to initialize");
        }
        int ok = 0;
        int failed = 0;
        try {
            for (String line :
                    Files.readAllLines(src.resolve("shaders.list"), StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                if (buildOne(compiler, src, out, t.split("\\s+"))) {
                    ok++;
                } else {
                    failed++;
                }
            }
        } finally {
            shaderc_compiler_release(compiler);
        }
        System.out.println(
                "shaders: " + ok + " compiled" + (failed > 0 ? ", " + failed + " failed" : ""));
        if (failed > 0) {
            System.exit(1);
        }
    }

    /** One line of shaders.list ({output, stage, source, definitions...}); false if it failed. */
    private static boolean buildOne(long compiler, Path src, Path out, String[] f)
            throws IOException {
        String name = f[0];
        int kind = parseStage(name, f[1]);
        List<String> defines = new ArrayList<>();
        for (int i = 3; i < f.length; i++) {
            defines.add(f[i]);
        }
        String source = expand(src, src.resolve(f[2]), 0);
        if (defines.contains("RT") && source.startsWith("#version 450")) {
            // ray queries need GLSL 4.60; these variants are Vulkan only
            source = "#version 460" + source.substring("#version 450".length());
        }
        ByteBuffer spirv = compile(compiler, name, source, kind, defines);
        if (spirv == null) {
            return false;
        }
        try {
            byte[] spv = new byte[spirv.remaining()];
            spirv.get(spirv.position(), spv);
            Files.write(out.resolve(name + ".spv"), spv);
            // compute only exists with a storage buffer, which in GLSL below 430 is an
            // extension: it goes in 430 (the device only uses compute with GL 4.3); the
            // rest stays in 410, which level 4.1 compiles
            // RT: ray queries, Vulkan only; OpenGL never loads these (a placeholder GLSL)
            boolean vulkanOnly = defines.contains("RT");
            CrossResult cross =
                    crossGl(
                            spirv,
                            name,
                            kind == shaderc_glsl_compute_shader
                                    ? GLSL_VERSION_COMPUTE
                                    : GLSL_VERSION_DEFAULT,
                            !vulkanOnly);
            if (cross == null) {
                return false;
            }
            Files.writeString(out.resolve(name + ".glsl"), cross.glsl(), StandardCharsets.UTF_8);
            Files.writeString(
                    out.resolve(name + ".res"), cross.reflection(), StandardCharsets.UTF_8);
            return true;
        } finally {
            MemoryUtil.memFree(spirv);
        }
    }

    private static int parseStage(String name, String stage) {
        return switch (stage) {
            case "vert" -> shaderc_glsl_vertex_shader;
            case "frag" -> shaderc_glsl_fragment_shader;
            case "comp" -> shaderc_glsl_compute_shader;
            default ->
                    throw new IllegalArgumentException("unknown stage in " + name + ": " + stage);
        };
    }

    /**
     * The source text with the {@code #include}s replaced by their content (relative to the sources
     * folder).
     */
    private static String expand(Path root, Path file, int depth) throws IOException {
        if (depth > MAX_INCLUDE_DEPTH) {
            throw new IllegalStateException("include nested too deep: " + file);
        }
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Matcher m = INCLUDE.matcher(text);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(
                    b, Matcher.quoteReplacement(expand(root, root.resolve(m.group(1)), depth + 1)));
        }
        m.appendTail(b);
        return b.toString();
    }

    /**
     * GLSL -> SPIR-V (Vulkan 1.2 target, no optimization: the names stay, OpenGL binds by name).
     * Our own code.
     */
    private static ByteBuffer compile(
            long compiler, String name, String source, int kind, List<String> defines) {
        long options = shaderc_compile_options_initialize();
        try {
            shaderc_compile_options_set_source_language(options, shaderc_source_language_glsl);
            shaderc_compile_options_set_target_env(
                    options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_2);
            shaderc_compile_options_set_optimization_level(
                    options, shaderc_optimization_level_zero);
            shaderc_compile_options_set_generate_debug_info(options);
            for (String d : defines) {
                shaderc_compile_options_add_macro_definition(options, d, "1");
            }
            long result = shaderc_compile_into_spv(compiler, source, kind, name, "main", options);
            try {
                if (shaderc_result_get_compilation_status(result)
                        != shaderc_compilation_status_success) {
                    System.err.println(
                            "shader " + name + ": " + shaderc_result_get_error_message(result));
                    return null;
                }
                ByteBuffer bytes = shaderc_result_get_bytes(result);
                ByteBuffer copy = MemoryUtil.memAlloc(bytes.remaining());
                copy.put(bytes).flip();
                return copy;
            } finally {
                shaderc_result_release(result);
            }
        } finally {
            shaderc_compile_options_release(options);
        }
    }

    /**
     * SPIR-V -> GLSL {@code version} core (410 without layout(binding); 430 for compute) through
     * SPIRV-Cross, plus the resource reflection: one line per resource, {@code type name set
     * binding [dimension]} (type uniform, storage or texture; for samplers, 2d or 2darray; the
     * block's or sampler's name, the same one the renderer uses to bind the slot). The Vulkan
     * backend finds each slot's binding through it; OpenGL binds the slots by name (on top of the
     * 430 binding).
     */
    private static CrossResult crossGl(ByteBuffer spirv, String name, int version, boolean toGlsl) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pp = stack.mallocPointer(1);
            check(spvc_context_create(pp), "context", name);
            long ctx = pp.get(0);
            try {
                IntBuffer words = spirv.asIntBuffer();
                PointerBuffer ir = stack.mallocPointer(1);
                check(
                        spvc_context_parse_spirv(ctx, words, words.remaining(), ir),
                        "read the SPIR-V",
                        name);
                PointerBuffer comp = stack.mallocPointer(1);
                check(
                        spvc_context_create_compiler(
                                ctx,
                                SPVC_BACKEND_GLSL,
                                ir.get(0),
                                SPVC_CAPTURE_MODE_TAKE_OWNERSHIP,
                                comp),
                        "compiler",
                        name);
                long compiler = comp.get(0);
                PointerBuffer opt = stack.mallocPointer(1);
                check(spvc_compiler_create_compiler_options(compiler, opt), "options", name);
                long options = opt.get(0);
                spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_GLSL_VERSION, version);
                spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_GLSL_ES, false);
                spvc_compiler_options_set_bool(
                        options, SPVC_COMPILER_OPTION_GLSL_VULKAN_SEMANTICS, false);
                spvc_compiler_options_set_bool(
                        options, SPVC_COMPILER_OPTION_GLSL_ENABLE_420PACK_EXTENSION, false);
                spvc_compiler_install_compiler_options(compiler, options);
                String res = reflect(compiler, name);
                if (!toGlsl) {
                    return new CrossResult("// Vulkan only (ray queries)\n", res);
                }
                PointerBuffer text = stack.mallocPointer(1);
                if (spvc_compiler_compile(compiler, text) != SPVC_SUCCESS) {
                    System.err.println(
                            "shader "
                                    + name
                                    + ": SPIRV-Cross: "
                                    + spvc_context_get_last_error_string(ctx));
                    return null;
                }
                return new CrossResult(MemoryUtil.memUTF8(text.get(0)), res);
            } finally {
                spvc_context_destroy(ctx);
            }
        }
    }

    private static String reflect(long compiler, String name) {
        StringBuilder b = new StringBuilder();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer res = stack.mallocPointer(1);
            check(spvc_compiler_create_shader_resources(compiler, res), "resources", name);
            int[] types = {
                SPVC_RESOURCE_TYPE_UNIFORM_BUFFER,
                SPVC_RESOURCE_TYPE_STORAGE_BUFFER,
                SPVC_RESOURCE_TYPE_SAMPLED_IMAGE,
                SPVC_RESOURCE_TYPE_ACCELERATION_STRUCTURE
            };
            String[] kinds = {"uniform", "storage", "texture", "acceleration"};
            for (int t = 0; t < types.length; t++) {
                PointerBuffer list = stack.mallocPointer(1);
                PointerBuffer count = stack.mallocPointer(1);
                check(
                        spvc_resources_get_resource_list_for_type(
                                res.get(0), types[t], list, count),
                        "list",
                        name);
                SpvcReflectedResource.Buffer r =
                        SpvcReflectedResource.create(list.get(0), (int) count.get(0));
                for (SpvcReflectedResource x : r) {
                    // block: the type name (the GLSL one, "uniform Frame {...}"); sampler: the
                    // variable's
                    String n =
                            t < 2
                                    ? spvc_compiler_get_name(compiler, x.base_type_id())
                                    : x.nameString();
                    int set =
                            spvc_compiler_get_decoration(
                                    compiler, x.id(), Spv.SpvDecorationDescriptorSet);
                    int binding =
                            spvc_compiler_get_decoration(
                                    compiler, x.id(), Spv.SpvDecorationBinding);
                    b.append(kinds[t])
                            .append(' ')
                            .append(n)
                            .append(' ')
                            .append(set)
                            .append(' ')
                            .append(binding);
                    if (t == 2) {
                        // the sampler's dimension: Vulkan binds an empty texture of the same type
                        // in the slot with no resource
                        long type = spvc_compiler_get_type_handle(compiler, x.type_id());
                        b.append(spvc_type_get_image_arrayed(type) ? " 2darray" : " 2d");
                    }
                    b.append('\n');
                }
            }
        }
        return b.toString();
    }

    private static void check(int r, String what, String name) {
        if (r != SPVC_SUCCESS) {
            throw new IllegalStateException(
                    "shader " + name + ": SPIRV-Cross (" + what + "): " + r);
        }
    }
}
