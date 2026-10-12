/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Build step of the Bridge: references to game members the real game does not let the Bridge touch
 * (private, package-private, protected outside the hierarchy) become invokedynamic bound once to
 * the member by {@code dev.xavier.stein.bridge.core.GameAccess}.
 *
 * <p>The Bridge is compiled against the game with every member opened and the readable names; by
 * the time this tool runs the classes already carry the obfuscated names. The decision is the JVM's
 * own access rule, read from the real game jar, and only for classes of that jar (the Bridge's own
 * classes, LWJGL and the JDK stay as they are). The same semantics as the Loader's {@code ModPatch}
 * for mods.
 *
 * <p>An instruction that would be refused and cannot be bound (invokespecial, such as a closed
 * constructor) fails the build instead of the game.
 *
 * <p>Usage: AccessRewrite &lt;game.jar&gt; &lt;input classes dir&gt; &lt;output classes dir&gt;
 */
public final class AccessRewrite {

    // Same numbering as GameAccess.
    private static final int GET = 0;
    private static final int PUT = 1;
    private static final int GET_STATIC = 2;
    private static final int PUT_STATIC = 3;
    private static final int VIRTUAL = 4;
    private static final int STATIC = 5;

    private static final Handle ACCESS =
            new Handle(
                    Opcodes.H_INVOKESTATIC,
                    "dev/xavier/stein/bridge/core/GameAccess",
                    "bootstrap",
                    "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                            + "Ljava/lang/invoke/MethodType;"
                            + "ILjava/lang/String;Ljava/lang/String;)Ljava/lang/invoke/CallSite;",
                    false);

    /**
     * The final instance fields of the game the Bridge writes on purpose, as declaring class and
     * name (obfuscated); they go through GameAccess, which can set an instance final. Any other
     * write to a final game field fails the build.
     */
    private static final Set<String> FINAL_WRITES =
            Set.of(
                    "ave.ae" // Minecraft.session, swapped by GameHooks.setSession for the Loader
                    );

    private final Map<String, byte[]> own = new HashMap<>();
    private final Map<String, Info> cache = new HashMap<>();
    private final ZipFile game;
    private final TreeSet<String> errors = new TreeSet<>();
    private int rewritten;

    private record Member(String owner, int access) {}

    private record Info(
            String name,
            int access,
            String sup,
            String[] itfs,
            Map<String, Integer> fields,
            Map<String, Integer> methods) {}

    private AccessRewrite(ZipFile game) {
        this.game = game;
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.err.println("usage: AccessRewrite <game.jar> <input dir> <output dir>");
            System.exit(2);
        }
        Path in = Paths.get(args[1]);
        Path out = Paths.get(args[2]);
        try (ZipFile game = new ZipFile(args[0])) {
            AccessRewrite r = new AccessRewrite(game);
            List<Path> files;
            try (Stream<Path> s = Files.walk(in)) {
                files = s.filter(Files::isRegularFile).toList();
            }
            for (Path p : files) {
                String n = in.relativize(p).toString().replace('\\', '/');
                if (n.endsWith(".class")) {
                    r.own.put(n.substring(0, n.length() - 6), Files.readAllBytes(p));
                }
            }
            int classes = 0;
            for (Path p : files) {
                String n = in.relativize(p).toString().replace('\\', '/');
                Path target = out.resolve(n);
                Files.createDirectories(target.getParent());
                if (n.endsWith(".class")) {
                    Files.write(target, r.transform(r.own.get(n.substring(0, n.length() - 6))));
                    classes++;
                } else {
                    Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            System.out.println(
                    "access: "
                            + r.rewritten
                            + " game reference(s) bound by invokedynamic in "
                            + classes
                            + " classes");
            if (!r.errors.isEmpty()) {
                System.err.println(
                        "access: references the game would refuse and that cannot be bound:");
                r.errors.forEach(e -> System.err.println("  " + e));
                System.exit(1);
            }
        }
    }

    private byte[] transform(byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        // No frame or max recomputation: the invokedynamic consumes and leaves on the stack the
        // same as the instruction it replaces.
        ClassWriter cw = new ClassWriter(0);
        cr.accept(
                new ClassVisitor(Opcodes.ASM9, cw) {
                    private String className;

                    @Override
                    public void visit(
                            int version,
                            int access,
                            String name,
                            String sig,
                            String sup,
                            String[] itf) {
                        className = name;
                        super.visit(version, access, name, sig, sup, itf);
                    }

                    @Override
                    public MethodVisitor visitMethod(
                            int access, String name, String desc, String sig, String[] ex) {
                        MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                        return mv == null ? null : new Rewriter(mv, className, name + desc);
                    }
                },
                0);
        return cw.toByteArray();
    }

    /** Rewrites the field and method instructions of one method. */
    private final class Rewriter extends MethodVisitor {
        private final String className;
        private final String where;

        Rewriter(MethodVisitor next, String className, String where) {
            super(Opcodes.ASM9, next);
            this.className = className;
            this.where = where;
        }

        @Override
        public void visitFieldInsn(int op, String owner, String name, String desc) {
            int kind =
                    switch (op) {
                        case Opcodes.GETFIELD -> GET;
                        case Opcodes.PUTFIELD -> PUT;
                        case Opcodes.GETSTATIC -> GET_STATIC;
                        default -> PUT_STATIC;
                    };
            boolean statik = kind == GET_STATIC || kind == PUT_STATIC;
            // Resolved with the instruction's own kind: the obfuscated game has static and instance
            // fields that share name and type along one hierarchy (BlockPos.c is a static constant,
            // Vec3i.c the inherited y), and the JVM, resolving by name and type only, would stop at
            // the first one.
            Member m = field(owner, name, desc, statik);
            Member jvm = field(owner, name, desc, null);
            String ref = className + "." + where + " -> " + owner + "." + name + ":" + desc;
            if (jvm != null && m == null) {
                errors.add(ref + ": no " + (statik ? "static" : "instance") + " field like that");
                super.visitFieldInsn(op, owner, name, desc);
                return;
            }
            boolean finalWrite =
                    m != null
                            && gameMember(m)
                            && (kind == PUT || kind == PUT_STATIC)
                            && (m.access() & Opcodes.ACC_FINAL) != 0;
            if (finalWrite && (statik || !FINAL_WRITES.contains(m.owner() + "." + name))) {
                // The readable-name jar is opened for compiling, finals included, so javac accepts
                // the write; the game would refuse it (IllegalAccessError).
                errors.add(ref + ": writes a final field");
                super.visitFieldInsn(op, owner, name, desc);
                return;
            }
            // An accepted final write goes through GameAccess, which can set an instance final.
            if (m != null && (finalWrite || denied(className, owner, m, statik))) {
                String type =
                        switch (kind) {
                            case GET -> "(L" + owner + ";)" + desc;
                            case PUT -> "(L" + owner + ";" + desc + ")V";
                            case GET_STATIC -> "()" + desc;
                            default -> "(" + desc + ")V";
                        };
                rewritten++;
                super.visitInvokeDynamicInsn(name, erase(type), ACCESS, kind, m.owner(), desc);
                return;
            }
            // Accessible, but the JVM would resolve the reference to the other field of that name
            // and type: the declaring class goes in the instruction.
            super.visitFieldInsn(op, m != null && !m.equals(jvm) ? m.owner() : owner, name, desc);
        }

        @Override
        public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
            if (op == Opcodes.INVOKEVIRTUAL || op == Opcodes.INVOKESTATIC) {
                Member m = method(owner, name, desc);
                boolean statik = op == Opcodes.INVOKESTATIC;
                if (m != null && denied(className, owner, m, statik)) {
                    String type = statik ? desc : "(L" + owner + ";" + desc.substring(1);
                    rewritten++;
                    super.visitInvokeDynamicInsn(
                            name, erase(type), ACCESS, statik ? STATIC : VIRTUAL, m.owner(), desc);
                    return;
                }
            } else if (op == Opcodes.INVOKESPECIAL && !itf) {
                Member m = method(owner, name, desc);
                if (m != null && denied(className, owner, m, false)) {
                    errors.add(className + "." + where + " -> " + owner + "." + name + desc);
                }
            }
            super.visitMethodInsn(op, owner, name, desc, itf);
        }
    }

    // ---- the access rule, read from the real game jar ----

    private Info get(String name) {
        if (name == null) {
            return null;
        }
        if (cache.containsKey(name)) {
            return cache.get(name);
        }
        Info info = null;
        try {
            byte[] b = own.get(name);
            if (b == null) {
                ZipEntry e = game.getEntry(name + ".class");
                if (e != null) {
                    try (InputStream in = game.getInputStream(e)) {
                        b = in.readAllBytes();
                    }
                }
            }
            if (b != null) {
                info = read(b);
            }
        } catch (IOException | RuntimeException e) {
            info = null;
        }
        cache.put(name, info);
        return info;
    }

    private static Info read(byte[] b) {
        Map<String, Integer> fields = new HashMap<>();
        Map<String, Integer> methods = new HashMap<>();
        String[] head = new String[1];
        int[] classAccess = new int[1];
        String[] sup = new String[1];
        String[][] itfs = new String[1][];
        new ClassReader(b)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(
                                    int v,
                                    int access,
                                    String name,
                                    String sig,
                                    String s,
                                    String[] i) {
                                head[0] = name;
                                classAccess[0] = access;
                                sup[0] = s;
                                itfs[0] = i == null ? new String[0] : i;
                            }

                            @Override
                            public FieldVisitor visitField(
                                    int access,
                                    String name,
                                    String desc,
                                    String sig,
                                    Object value) {
                                fields.put(name + ":" + desc, access);
                                return null;
                            }

                            @Override
                            public MethodVisitor visitMethod(
                                    int access, String name, String desc, String sig, String[] ex) {
                                methods.put(name + desc, access);
                                return null;
                            }
                        },
                        ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new Info(head[0], classAccess[0], sup[0], itfs[0], fields, methods);
    }

    /**
     * The field in the JVM's resolution order: the class, its interfaces, the superclass. With
     * {@code statik} null, the first one of that name and type, as the JVM resolves it; otherwise
     * the first one that is also static (or instance) as asked, skipping the others.
     */
    private Member field(String owner, String name, String desc, Boolean statik) {
        Info c = get(owner);
        if (c == null) {
            return null;
        }
        Integer a = c.fields().get(name + ":" + desc);
        if (a != null && (statik == null || ((a & Opcodes.ACC_STATIC) != 0) == statik)) {
            return new Member(c.name(), a);
        }
        for (String i : c.itfs()) {
            Member m = field(i, name, desc, statik);
            if (m != null) {
                return m;
            }
        }
        return field(c.sup(), name, desc, statik);
    }

    /** The method through the superclass chain (interface ones are public). */
    private Member method(String owner, String name, String desc) {
        for (Info c = get(owner); c != null; c = get(c.sup())) {
            Integer a = c.methods().get(name + desc);
            if (a != null) {
                return new Member(c.name(), a);
            }
        }
        return null;
    }

    /**
     * A game class the Bridge cannot name: not public (the Bridge never shares a package with the
     * game).
     */
    private boolean closedClass(String name) {
        if (own.containsKey(name) || game.getEntry(name + ".class") == null) {
            return false;
        }
        Info c = get(name);
        return c != null && (c.access() & Opcodes.ACC_PUBLIC) == 0;
    }

    /**
     * The call-site type with every closed game class replaced by Object: the JVM resolves the
     * invokedynamic's type in the caller, and a type it may not access would fail there; the
     * ConstantCallSite adapts the member's real type to it.
     */
    private String erase(String type) {
        Type t = Type.getMethodType(type);
        Type[] args = t.getArgumentTypes();
        for (int i = 0; i < args.length; i++) {
            args[i] = erase(args[i]);
        }
        return Type.getMethodDescriptor(erase(t.getReturnType()), args);
    }

    private Type erase(Type t) {
        if (t.getSort() == Type.OBJECT && closedClass(t.getInternalName())) {
            return Type.getType(Object.class);
        }
        if (t.getSort() == Type.ARRAY
                && t.getElementType().getSort() == Type.OBJECT
                && closedClass(t.getElementType().getInternalName())) {
            return Type.getType(Object.class);
        }
        return t;
    }

    /** Declared in a class of the game jar (not the Bridge's own, LWJGL or the JDK). */
    private boolean gameMember(Member m) {
        return !own.containsKey(m.owner()) && game.getEntry(m.owner() + ".class") != null;
    }

    /** Would the JVM refuse this access? Only for members declared in a class of the game jar. */
    private boolean denied(String accessor, String owner, Member m, boolean statik) {
        if (!gameMember(m)) {
            return false;
        }
        // A member of a class the Bridge cannot name is refused whatever the member's own access.
        if (closedClass(owner) || closedClass(m.owner())) {
            return true;
        }
        int a = m.access();
        if ((a & Opcodes.ACC_PUBLIC) != 0) {
            return false;
        }
        if ((a & Opcodes.ACC_PRIVATE) != 0) {
            return true;
        }
        if (pkg(accessor).equals(pkg(m.owner()))) {
            return false;
        }
        if ((a & Opcodes.ACC_PROTECTED) == 0) {
            return true;
        }
        return !(isSub(accessor, m.owner()) && (statik || isSub(owner, accessor)));
    }

    private boolean isSub(String c, String parent) {
        for (Info i = get(c); i != null; i = get(i.sup())) {
            if (i.name().equals(parent)) {
                return true;
            }
        }
        return false;
    }

    private static String pkg(String name) {
        int s = name.lastIndexOf('/');
        return s < 0 ? "" : name.substring(0, s);
    }
}
