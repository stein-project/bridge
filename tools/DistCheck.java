/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Checks the obfuscated jars ({@code ./gradlew dist}): every reference from one of our classes to
 * one of our classes (dev/xavier/stein/...) must exist in the jars, class and member, and every
 * ServiceLoader file must name classes that exist.
 *
 * <p>ProGuard rewrites names everywhere but one place: the descriptor of a signature-polymorphic
 * call ({@code MethodHandle.invokeExact}, {@code VarHandle.get}). If one of those names one of our
 * renamed classes, the old name stays and the game dies at the first frame with
 * NoClassDefFoundError (it happened with a VarHandle returning ChunkArena.Region). The Loader is
 * obfuscated in a run of its own, on top of the Bridge's mapping: a member the two runs named
 * differently would show up here as a missing member.
 *
 * <p>Usage: {@code DistCheck jar...}. A member is looked up in its class and the class's
 * supertypes; when the chain leaves our classes (the game, the JDK), it is taken as resolved.
 */
public final class DistCheck {

    private static final class Info {
        String superName;
        String[] interfaces = new String[0];
        final Set<String> members = new HashSet<>();
    }

    private final Map<String, Info> classes = new HashMap<>();
    private final Set<String> problems = new TreeSet<>();
    private final List<byte[]> bodies = new ArrayList<>();
    private final List<String> services = new ArrayList<>();

    private static boolean ours(String internalName) {
        return internalName.startsWith("dev/xavier/stein/");
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: DistCheck jar...");
            System.exit(2);
        }
        DistCheck check = new DistCheck();
        for (String a : args) {
            check.load(a);
        }
        for (byte[] b : check.bodies) {
            check.references(b);
        }
        check.services();
        if (!check.problems.isEmpty()) {
            System.err.println("references the obfuscated jars do not serve:");
            check.problems.stream().limit(200).forEach(p -> System.err.println("  " + p));
            System.exit(1);
        }
        System.out.println(
                "dist check: "
                        + check.bodies.size()
                        + " classes, every reference to our classes and members resolves");
    }

    private void load(String path) throws IOException {
        try (JarFile jar = new JarFile(Paths.get(path).toFile())) {
            for (Enumeration<JarEntry> e = jar.entries(); e.hasMoreElements(); ) {
                JarEntry entry = e.nextElement();
                String name = entry.getName();
                if (name.endsWith(".class") && !name.endsWith("module-info.class")) {
                    byte[] body;
                    try (InputStream in = jar.getInputStream(entry)) {
                        body = in.readAllBytes();
                    }
                    ClassReader r = new ClassReader(body);
                    Info info = new Info();
                    info.superName = r.getSuperName();
                    info.interfaces = r.getInterfaces();
                    r.accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public FieldVisitor visitField(
                                        int access, String n, String d, String s, Object v) {
                                    info.members.add(n + ":" + d);
                                    return null;
                                }

                                @Override
                                public MethodVisitor visitMethod(
                                        int access, String n, String d, String s, String[] x) {
                                    info.members.add(n + d);
                                    return null;
                                }
                            },
                            ClassReader.SKIP_CODE);
                    classes.put(r.getClassName(), info);
                    bodies.add(body);
                } else if (name.startsWith("META-INF/services/") && !entry.isDirectory()) {
                    String service = name.substring("META-INF/services/".length());
                    services.add(service.replace('.', '/') + " (service file " + name + ")");
                    try (InputStream in = jar.getInputStream(entry)) {
                        for (String line :
                                new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                            String l = line.strip();
                            if (!l.isEmpty() && !l.startsWith("#")) {
                                services.add(l.replace('.', '/') + " (listed in " + name + ")");
                            }
                        }
                    }
                }
            }
        }
    }

    private void services() {
        for (String s : services) {
            String cls = s.substring(0, s.indexOf(' '));
            if (ours(cls) && !classes.containsKey(cls)) {
                problems.add("class " + s);
            }
        }
    }

    private void type(String from, Type t) {
        switch (t.getSort()) {
            case Type.ARRAY -> type(from, t.getElementType());
            case Type.OBJECT -> cls(from, t.getInternalName());
            case Type.METHOD -> {
                for (Type a : t.getArgumentTypes()) {
                    type(from, a);
                }
                type(from, t.getReturnType());
            }
            default -> {}
        }
    }

    private void cls(String from, String internalName) {
        if (internalName.startsWith("[")) {
            type(from, Type.getType(internalName));
        } else if (ours(internalName) && !classes.containsKey(internalName)) {
            problems.add(from + " -> class " + internalName);
        }
    }

    private void member(String from, String owner, String key) {
        if (owner.startsWith("[")) {
            return;
        }
        cls(from, owner);
        if (!ours(owner) || !classes.containsKey(owner)) {
            return;
        }
        if (!resolves(owner, key, new HashSet<>())) {
            problems.add(from + " -> " + owner + "." + key);
        }
    }

    /** True when the member is in the class, a supertype, or the chain leaves our classes. */
    private boolean resolves(String owner, String key, Set<String> seen) {
        if (!seen.add(owner)) {
            return false;
        }
        Info info = classes.get(owner);
        if (info == null) {
            return true; // the game, the JDK, a library (or a missing class, reported apart)
        }
        if (info.members.contains(key)) {
            return true;
        }
        if (info.superName != null && resolves(info.superName, key, seen)) {
            return true;
        }
        for (String i : info.interfaces) {
            if (resolves(i, key, seen)) {
                return true;
            }
        }
        return false;
    }

    private void handle(String from, Handle h) {
        if (h.getTag() <= Opcodes.H_PUTSTATIC) {
            member(from, h.getOwner(), h.getName() + ":" + h.getDesc());
            type(from, Type.getType(h.getDesc()));
        } else {
            member(from, h.getOwner(), h.getName() + h.getDesc());
            type(from, Type.getMethodType(h.getDesc()));
        }
    }

    private void constant(String from, Object o) {
        if (o instanceof Type t) {
            type(from, t);
        } else if (o instanceof Handle h) {
            handle(from, h);
        }
    }

    private void references(byte[] body) {
        ClassReader r = new ClassReader(body);
        String self = r.getClassName();
        if (r.getSuperName() != null) {
            cls(self, r.getSuperName());
        }
        for (String i : r.getInterfaces()) {
            cls(self, i);
        }
        r.accept(
                new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public FieldVisitor visitField(
                            int access, String n, String d, String s, Object v) {
                        type(self, Type.getType(d));
                        return null;
                    }

                    @Override
                    public MethodVisitor visitMethod(
                            int access, String n, String d, String s, String[] x) {
                        String from = self + "." + n;
                        type(from, Type.getMethodType(d));
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override
                            public void visitTypeInsn(int opcode, String type) {
                                cls(from, type);
                            }

                            @Override
                            public void visitFieldInsn(
                                    int opcode, String owner, String name, String desc) {
                                member(from, owner, name + ":" + desc);
                                type(from, Type.getType(desc));
                            }

                            @Override
                            public void visitMethodInsn(
                                    int opcode,
                                    String owner,
                                    String name,
                                    String desc,
                                    boolean itf) {
                                member(from, owner, name + desc);
                                // The descriptor of a signature-polymorphic call is checked too:
                                // that is where an old name survives.
                                type(from, Type.getMethodType(desc));
                            }

                            @Override
                            public void visitInvokeDynamicInsn(
                                    String name, String desc, Handle bsm, Object... args) {
                                type(from, Type.getMethodType(desc));
                                handle(from, bsm);
                                for (Object a : args) {
                                    constant(from, a);
                                }
                            }

                            @Override
                            public void visitLdcInsn(Object value) {
                                constant(from, value);
                            }

                            @Override
                            public void visitMultiANewArrayInsn(String desc, int dims) {
                                type(from, Type.getType(desc));
                            }

                            @Override
                            public void visitTryCatchBlock(
                                    org.objectweb.asm.Label a,
                                    org.objectweb.asm.Label b,
                                    org.objectweb.asm.Label c,
                                    String type) {
                                if (type != null) {
                                    cls(from, type);
                                }
                            }
                        };
                    }
                },
                0);
    }

    private DistCheck() {}
}
