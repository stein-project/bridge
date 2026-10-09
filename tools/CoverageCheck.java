/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Checks that every reference to {@code org.lwjgl} or {@code dev.xavier.stein} in a patched jar is
 * served by someone: by the Bridge's own classes (and the class folders of other mods it calls), or
 * by LWJGL 3.
 *
 * <p>Runs against the patched jar on purpose: it is what will actually run. Checking against the
 * original game would flag the renamed methods as missing, which is exactly what the patch fixed.
 * Without starting the game, it separates "incomplete surface" from "GLFW bug".
 *
 * <p>Usage: {@code CoverageCheck <patched.jar> <lwjgl3-index.txt> <classes dir>...}. The LWJGL 3
 * index ({@code scan/lwjgl3-index.txt}) lists {@code owner.name:descriptor} for every method of the
 * GL classes. Exits 1 when something is missing.
 */
public final class CoverageCheck {

    private CoverageCheck() {}

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println(
                    "usage: CoverageCheck <patched.jar> <lwjgl3-index.txt> <classes dir>...");
            System.exit(2);
        }
        Set<String> lwjgl3 = new HashSet<>(Files.readAllLines(Path.of(args[1])));
        Set<String> shim = new HashSet<>();
        for (int i = 2; i < args.length; i++) {
            Path dir = Path.of(args[i]);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                    index(Files.readAllBytes(f), shim);
                }
            }
        }
        System.out.println("members in the shim: " + shim.size());

        Set<String> own = new HashSet<>();
        Set<String> methods = new TreeSet<>();
        Set<String> fields = new TreeSet<>();
        try (ZipFile jar = new ZipFile(args[0])) {
            List<? extends ZipEntry> classes =
                    jar.stream()
                            .filter(e -> e.getName().endsWith(".class"))
                            .filter(e -> !e.getName().startsWith("META-INF/"))
                            .toList();
            for (ZipEntry e : classes) {
                String name = e.getName();
                own.add(name.substring(0, name.length() - ".class".length()));
                try (InputStream in = jar.getInputStream(e)) {
                    references(in.readAllBytes(), methods, fields);
                }
            }
        }
        System.out.println(
                "references in the jar: "
                        + methods.size()
                        + " methods, "
                        + fields.size()
                        + " fields");

        boolean fail = false;
        for (String ref : methods) {
            String cls = ref.substring(0, ref.indexOf('.'));
            if (own.contains(cls)) {
                continue;
            }
            if (isGl(cls)) {
                if (!lwjgl3.contains(ref)) {
                    System.out.println("  MISSING (LWJGL3) " + strip(ref));
                    fail = true;
                }
                continue;
            }
            // Methods every enum inherits from java.lang.Enum (a switch over an API enum calls
            // ordinal): they are not declared in the class, but exist if it is an enum.
            String member = ref.substring(ref.indexOf('.') + 1);
            if (ENUM_METHODS.stream().anyMatch(member::startsWith)
                    && shim.contains(cls + "#$VALUES")) {
                continue;
            }
            if (!shim.contains(ref)) {
                System.out.println("  MISSING (shim)  " + strip(ref));
                fail = true;
            }
        }
        for (String f : fields) {
            String cls = f.substring(0, f.lastIndexOf('.'));
            if (isGl(cls) || own.contains(cls)) {
                continue;
            }
            if (!shim.contains(cls + "#" + f.substring(f.lastIndexOf('.') + 1))) {
                System.out.println("  MISSING field   " + strip(f));
                fail = true;
            }
        }
        System.out.println();
        System.out.println(
                fail ? "gaps listed above" : "FULL COVERAGE — every surface has a provider");
        System.exit(fail ? 1 : 0);
    }

    private static final List<String> ENUM_METHODS =
            List.of(
                    "ordinal:",
                    "name:",
                    "compareTo:",
                    "getDeclaringClass:",
                    "equals:",
                    "hashCode:",
                    "toString:");

    /** Every declared method ({@code owner.name:desc}) and field ({@code owner#name}). */
    private static void index(byte[] bytes, Set<String> out) {
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            String owner;

                            @Override
                            public void visit(
                                    int version,
                                    int access,
                                    String name,
                                    String signature,
                                    String superName,
                                    String[] interfaces) {
                                owner = name;
                            }

                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String desc,
                                    String signature,
                                    String[] exceptions) {
                                out.add(owner + "." + name + ":" + desc);
                                return null;
                            }

                            @Override
                            public FieldVisitor visitField(
                                    int access,
                                    String name,
                                    String desc,
                                    String signature,
                                    Object value) {
                                out.add(owner + "#" + name);
                                return null;
                            }
                        },
                        ClassReader.SKIP_CODE);
    }

    /** The calls and field accesses of the code that point at LWJGL or Stein. */
    private static void references(byte[] bytes, Set<String> methods, Set<String> fields) {
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String desc,
                                    String signature,
                                    String[] exceptions) {
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String mname,
                                            String mdesc,
                                            boolean itf) {
                                        if (watched(owner)) {
                                            methods.add(owner + "." + mname + ":" + mdesc);
                                        }
                                    }

                                    @Override
                                    public void visitFieldInsn(
                                            int opcode, String owner, String fname, String fdesc) {
                                        if (watched(owner)) {
                                            fields.add(owner + "." + fname);
                                        }
                                    }
                                };
                            }
                        },
                        0);
    }

    private static boolean watched(String owner) {
        return owner.startsWith("org/lwjgl/") || owner.startsWith("dev/xavier/stein/");
    }

    private static boolean isGl(String cls) {
        return cls.matches("org/lwjgl/opengl/(GL[0-9].*|ARB.*|EXT.*)")
                || cls.equals("org/lwjgl/BufferUtils");
    }

    private static String strip(String ref) {
        return ref.startsWith("org/lwjgl/") ? ref.substring("org/lwjgl/".length()) : ref;
    }
}
