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
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * Produces a copy of the Minecraft jar with call sites rewritten — LWJGL 2 names swapped for LWJGL
 * 3 ones, calls diverted to the SteinBridge classes — and, when needed, method bodies replaced.
 *
 * <p>The input jar is never modified. The output jar is derived locally from the user's own copy
 * and must not be redistributed.
 *
 * <p>Usage: Patcher &lt;input.jar&gt; &lt;output.jar&gt; &lt;rules.tsv&gt; [rules.tsv ...]
 */
public final class Patcher {

    /** The rule's mode column (6th); anything else is a plain rename / detour. */
    private enum Mode {
        BODY,
        BEFORE_HOOK,
        AFTER_HOOK,
        FIELD,
        SOLO,
        STATIC,
        NEW,
        RENAME;

        static Mode of(String column) {
            return switch (column) {
                case "body" -> BODY;
                case "before" -> BEFORE_HOOK;
                case "after" -> AFTER_HOOK;
                case "field" -> FIELD;
                case "solo" -> SOLO;
                case "static" -> STATIC;
                case "new" -> NEW;
                default -> RENAME;
            };
        }
    }

    /** Target of a rewrite. */
    private static final class Target {
        final String owner;
        final String name;

        /** An instance call becomes static, with the receiver as the first argument. */
        final boolean toStatic;

        /** If not null, only applies to call sites inside this method ("class.method"). */
        final String scope;

        /**
         * "Solo" mode: at this call site the before/after hooks of the same call do not apply (the
         * target takes care of it).
         */
        boolean solo;

        Target(String owner, String name, boolean toStatic, String scope) {
            this.owner = owner;
            this.name = name;
            this.toStatic = toStatic;
            this.scope = scope;
        }
    }

    /**
     * Call-site detours: class.method:descriptor -> targets. A scoped target wins over the unscoped
     * one inside its method; this way a local optimization (e.g. glBegin only in the FontRenderer)
     * coexists with a general detour of the same call (e.g. the glBegin counter of the benchmark
     * build).
     */
    private static final Map<String, List<Target>> MAP = new LinkedHashMap<>();

    private static final Map<String, Integer> HITS = new HashMap<>();
    private static int hitCount;

    /**
     * Methods whose entire BODY is replaced by a static delegation: class.method:descriptor ->
     * target that receives (Object this, arguments...). For when diverting a call site is not
     * enough because what needs to change is the behavior of the method itself, wherever the call
     * comes from.
     */
    private static final Map<String, Target> BODIES = new LinkedHashMap<>();

    /**
     * Hooks inserted before / after call sites: class.method:descriptor -> static calls with no
     * arguments. The original call stays where it is, subject to the detours in MAP.
     */
    private static final Map<String, List<Target>> BEFORE = new LinkedHashMap<>();

    private static final Map<String, List<Target>> AFTER = new LinkedHashMap<>();

    /**
     * New fields in game classes: class -> "name:descriptor". Public and transient, so that the
     * optimizations can keep state alongside the object (e.g. a cache on the ItemStack itself, with
     * no external map).
     */
    private static final Map<String, List<String>> FIELDS = new LinkedHashMap<>();

    /**
     * Allocations handed to a static method: "type.&lt;init&gt;:descriptor" -> scoped targets. The
     * type is a class (its constructor with that descriptor) or an array descriptor such as "[F"
     * with "(I)V" (the length). For scratch objects a hot method allocates on every call and drops
     * before returning: the target may hand out a kept one instead.
     */
    private static final Map<String, List<Target>> NEWS = new LinkedHashMap<>();

    /**
     * -Dpatcher.lenient=true: a rule with no call site is not an error. For mod jars, which go
     * through the same rules as the game (LWJGL renaming, matrix stack, batch) and only have call
     * sites for some of them.
     */
    private static final boolean LENIENT = Boolean.getBoolean("patcher.lenient");

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println(
                    "usage: Patcher <input.jar> <output.jar> <rules.tsv> [rules.tsv ...]");
            System.exit(2);
        }
        Path in = Path.of(args[0]);
        Path out = Path.of(args[1]);
        for (int i = 2; i < args.length; i++) {
            Path rules = Path.of(args[i]);
            if (Files.exists(rules)) {
                loadMapping(rules);
            }
        }
        System.out.println(
                "rules loaded: "
                        + (count(MAP)
                                + BODIES.size()
                                + count(BEFORE)
                                + count(AFTER)
                                + count(NEWS)));

        rewriteJar(in, out);
        reportUnused();
    }

    /** Copies the jar to out, transforming each class; prints the counts. */
    private static void rewriteJar(Path in, Path out) throws IOException {
        int classes = 0;
        int rewrites = 0;
        int copied = 0;

        try (JarFile jar = new JarFile(in.toFile());
                JarOutputStream jos = new JarOutputStream(Files.newOutputStream(out))) {

            List<JarEntry> entries = new ArrayList<>();
            jar.stream().forEach(entries::add);

            for (JarEntry e : entries) {
                if (e.isDirectory()) {
                    continue;
                }
                String n = e.getName();
                // Signatures do not survive class changes.
                if (n.startsWith("META-INF/")
                        && (n.endsWith(".SF") || n.endsWith(".DSA") || n.endsWith(".RSA"))) {
                    continue;
                }
                byte[] data;
                try (InputStream is = jar.getInputStream(e)) {
                    data = is.readAllBytes();
                }
                if (n.endsWith(".class")) {
                    int before = hitCount;
                    data = transform(data);
                    rewrites += hitCount - before;
                    classes++;
                } else {
                    copied++;
                }
                JarEntry ne = new JarEntry(n);
                ne.setTime(e.getTime());
                jos.putNextEntry(ne);
                jos.write(data);
                jos.closeEntry();
            }
        }

        System.out.println("classes processed: " + classes + " | other resources: " + copied);
        System.out.println("call sites rewritten: " + rewrites);
    }

    /** Lists the rules that never matched; exits 1 on any, unless lenient. */
    private static void reportUnused() {
        int unused = 0;
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, List<Target>> e : MAP.entrySet()) {
            boolean scopedUsed = false;
            for (Target t : e.getValue()) {
                scopedUsed |= t.scope != null && HITS.getOrDefault(ruleKey(e.getKey(), t), 0) > 0;
            }
            for (Target t : e.getValue()) {
                String k = ruleKey(e.getKey(), t);
                if (t.scope == null && scopedUsed && HITS.getOrDefault(k, 0) == 0) {
                    // All call sites ended up with scope rules for the same call.
                    System.out.println("  (general rule with no call site left: " + k + ")");
                    continue;
                }
                keys.add(k);
            }
        }
        keys.addAll(BODIES.keySet());
        for (Map.Entry<String, List<Target>> e : NEWS.entrySet()) {
            for (Target t : e.getValue()) {
                keys.add(ruleKey(e.getKey(), t));
            }
        }
        for (Map.Entry<String, List<Target>> e : BEFORE.entrySet()) {
            for (Target h : e.getValue()) {
                keys.add(hookKey("before ", e.getKey(), h));
            }
        }
        for (Map.Entry<String, List<Target>> e : AFTER.entrySet()) {
            for (Target h : e.getValue()) {
                keys.add(hookKey("after ", e.getKey(), h));
            }
        }
        for (Map.Entry<String, List<String>> e : FIELDS.entrySet()) {
            for (String fd : e.getValue()) {
                keys.add("field " + e.getKey() + "." + fd);
            }
        }
        for (String k : keys) {
            if (HITS.getOrDefault(k, 0) == 0) {
                if (!LENIENT) {
                    System.out.println("  WARNING unused rule: " + k);
                }
                unused++;
            }
        }
        if (unused > 0 && LENIENT) {
            // Mod jar: receives the same rules as the game, and most have no call site in it.
            System.out.println(unused + " rule(s) with no call site in this jar (lenient mode)");
        } else if (unused > 0) {
            System.out.println(unused + " rule(s) did not match — scan and patch disagree.");
            System.exit(1);
        }
        if (unused == 0) {
            System.out.println("all rules were applied.");
        }
    }

    /**
     * Format: class TAB name2 TAB descriptor TAB name3 [TAB newOwner [TAB mode [TAB scope]]].
     *
     * <ul>
     *   <li>without the 5th column: pure renaming of the call site;
     *   <li>with the 5th: the call site now points to another class;
     *   <li>mode "solo": like the renaming with a 5th column, but without the before/after hooks of
     *       that call at that call site (the target does what they would do);
     *   <li>mode "static": an instance call becomes invokestatic, with the receiver as the first
     *       argument typed as Object;
     *   <li>mode "body": the BODY of class.name2 is replaced by a call to newOwner.name3(Object
     *       this, arguments...);
     *   <li>modes "before" / "after": inserts newOwner.name3() — static, no arguments — before /
     *       after each call site of class.name2, which stays there;
     *   <li>mode "new" (scope required): in the scoped method, the allocation of class (NEW, DUP,
     *       the arguments, invokespecial &lt;init&gt; with the descriptor) becomes
     *       newOwner.name3(the same arguments, references as Object), cast back to the class; with
     *       class an array descriptor ("[F", "[Lalz;") and descriptor "(I)V", the NEWARRAY /
     *       ANEWARRAY becomes newOwner.name3(int length);
     *   <li>scope "class.method": only applies to call sites inside methods with that name (e.g.
     *       "xy.&lt;init&gt;"), to divert a call in one place only; "class.method(descriptor)"
     *       tells overloads apart (e.g. "bcy.a(Lfy;)V").
     * </ul>
     *
     * A later rule with the same key and the same scope replaces the earlier one. Reference types
     * reach the target as Object, so that the SteinBridge classes do not depend on the game's
     * obfuscated classes to compile.
     */
    private static void loadMapping(Path file) throws IOException {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t");
            if (f.length < 4) {
                continue;
            }
            String owner = f[0].trim();
            String newOwner = (f.length > 4 && !f[4].isEmpty()) ? f[4] : owner;
            Mode mode = Mode.of(f.length > 5 ? f[5] : "");
            String scope = f.length > 6 && !f[6].trim().isEmpty() ? f[6].trim() : null;
            String key = owner + "." + f[1] + ":" + f[2];
            switch (mode) {
                case BODY -> BODIES.put(key, new Target(newOwner, f[3], true, null));
                case BEFORE_HOOK ->
                        BEFORE.computeIfAbsent(key, k -> new ArrayList<>())
                                .add(new Target(newOwner, f[3], false, scope));
                case AFTER_HOOK ->
                        AFTER.computeIfAbsent(key, k -> new ArrayList<>())
                                .add(new Target(newOwner, f[3], false, scope));
                case FIELD ->
                        FIELDS.computeIfAbsent(owner, k -> new ArrayList<>())
                                .add(f[1] + ":" + f[2]);
                case NEW -> {
                    if (scope == null || !f[1].equals("<init>")) {
                        throw new IllegalArgumentException(
                                "a \"new\" rule needs <init> and a scope: " + line);
                    }
                    List<Target> l = NEWS.computeIfAbsent(key, k -> new ArrayList<>());
                    l.removeIf(x -> Objects.equals(x.scope, scope));
                    l.add(new Target(newOwner, f[3], true, scope));
                }
                default -> {
                    List<Target> l = MAP.computeIfAbsent(key, k -> new ArrayList<>());
                    l.removeIf(x -> Objects.equals(x.scope, scope));
                    Target nt = new Target(newOwner, f[3], mode == Mode.STATIC, scope);
                    nt.solo = mode == Mode.SOLO;
                    l.add(nt);
                }
            }
        }
    }

    /** The target that applies in this method: the one with equal scope, else the unscoped one. */
    private static Target pick(List<Target> l, String here, String hereDesc) {
        if (l == null) {
            return null;
        }
        Target general = null;
        for (Target t : l) {
            if (t.scope == null) {
                general = t;
            } else if (inScope(t, here, hereDesc)) {
                return t;
            }
        }
        return general;
    }

    /** Scope by method name ("bcy.a") or by name and descriptor ("bcy.a(Lfy;)V"). */
    private static boolean inScope(Target t, String here, String hereDesc) {
        return t.scope.equals(here) || t.scope.equals(hereDesc);
    }

    private static String ruleKey(String key, Target t) {
        return t.scope == null ? key : key + " @" + t.scope;
    }

    private static String hookKey(String where, String key, Target h) {
        return where + ruleKey(key, h) + " -> " + h.owner + "." + h.name;
    }

    private static int count(Map<String, List<Target>> m) {
        int n = 0;
        for (List<Target> l : m.values()) {
            n += l.size();
        }
        return n;
    }

    private static byte[] transform(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        // No COMPUTE_FRAMES/COMPUTE_MAXS: the stack effect of each rewritten call is identical to
        // the original (the receiver only changes role, from "this" to first argument), the hooks
        // do not touch the stack, a redirected allocation needs two slots less (no NEW and DUP)
        // and has no frame or branch inside it, and the replaced bodies have no branches — they
        // need no StackMapTable and declare their maxs themselves.
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
                    public void visitEnd() {
                        List<String> added = FIELDS.get(className);
                        if (added != null) {
                            for (String fd : added) {
                                int c = fd.indexOf(':');
                                super.visitField(
                                                Opcodes.ACC_PUBLIC | Opcodes.ACC_TRANSIENT,
                                                fd.substring(0, c),
                                                fd.substring(c + 1),
                                                null,
                                                null)
                                        .visitEnd();
                                hit("field " + className + "." + fd);
                            }
                        }
                        super.visitEnd();
                    }

                    @Override
                    public MethodVisitor visitMethod(
                            int access, String name, String desc, String sig, String[] ex) {
                        String bodyKey = className + "." + name + ":" + desc;
                        Target body = BODIES.get(bodyKey);
                        if (body != null
                                && (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
                            hit(bodyKey);
                            MethodVisitor out = super.visitMethod(access, name, desc, sig, ex);
                            delegate(out, access, desc, body);
                            return null; // the ClassReader discards the original body
                        }

                        MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                        if (mv == null) {
                            return null;
                        }
                        final String here = className + "." + name;
                        final String hereDesc = here + desc;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            /**
                             * The NEW instructions of this method whose invokespecial has not come
                             * yet, innermost last: the type, and whether a "new" rule took it (its
                             * NEW and DUP were not written).
                             */
                            private final List<String> news = new ArrayList<>();

                            private final List<Boolean> taken = new ArrayList<>();

                            /** A taken NEW was written as nothing: its DUP must come next. */
                            private boolean dropDup;

                            /**
                             * Inside a taken allocation the code must run straight: a frame or a
                             * branch there would describe the NEW that is no longer written.
                             */
                            private void straight(String what) {
                                if (dropDup || taken.contains(Boolean.TRUE)) {
                                    throw new IllegalStateException(
                                            what
                                                    + " inside a redirected allocation in "
                                                    + hereDesc
                                                    + ": not supported");
                                }
                            }

                            /** Only the DUP may follow a taken NEW. */
                            private void notDup() {
                                if (dropDup) {
                                    straight("an instruction before the DUP");
                                }
                            }

                            private Target newRule(String type, String ctorDesc) {
                                return pick(NEWS.get(type + ".<init>:" + ctorDesc), here, hereDesc);
                            }

                            /** Some "new" rule of this class applies in this method. */
                            private boolean newRuleHere(String type) {
                                String prefix = type + ".<init>:";
                                for (Map.Entry<String, List<Target>> e : NEWS.entrySet()) {
                                    if (e.getKey().startsWith(prefix)
                                            && pick(e.getValue(), here, hereDesc) != null) {
                                        return true;
                                    }
                                }
                                return false;
                            }

                            /** The allocation's static call, cast back to the allocated type. */
                            private void allocate(String key, Target t, Type[] args, String type) {
                                hit(ruleKey(key, t));
                                super.visitMethodInsn(
                                        Opcodes.INVOKESTATIC,
                                        t.owner,
                                        t.name,
                                        erasedDescriptor(args, Type.getObjectType(type), false),
                                        false);
                                super.visitTypeInsn(Opcodes.CHECKCAST, type);
                            }

                            /** NEWARRAY / ANEWARRAY of this array descriptor: true if taken. */
                            private boolean array(String array) {
                                Target t = newRule(array, "(I)V");
                                if (t == null) {
                                    return false;
                                }
                                allocate(
                                        array + ".<init>:(I)V",
                                        t,
                                        new Type[] {Type.INT_TYPE},
                                        array);
                                return true;
                            }

                            @Override
                            public void visitTypeInsn(int op, String type) {
                                notDup();
                                if (op == Opcodes.NEW) {
                                    boolean take = newRuleHere(type);
                                    news.add(type);
                                    taken.add(take);
                                    if (take) {
                                        dropDup = true;
                                        return;
                                    }
                                } else if (op == Opcodes.ANEWARRAY
                                        && array("[" + Type.getObjectType(type).getDescriptor())) {
                                    return;
                                }
                                super.visitTypeInsn(op, type);
                            }

                            @Override
                            public void visitIntInsn(int op, int operand) {
                                notDup();
                                if (op == Opcodes.NEWARRAY && array("[" + primitive(operand))) {
                                    return;
                                }
                                super.visitIntInsn(op, operand);
                            }

                            @Override
                            public void visitInsn(int op) {
                                if (dropDup && op == Opcodes.DUP) {
                                    dropDup = false;
                                    return;
                                }
                                notDup();
                                super.visitInsn(op);
                            }

                            @Override
                            public void visitVarInsn(int op, int var) {
                                notDup();
                                super.visitVarInsn(op, var);
                            }

                            @Override
                            public void visitFieldInsn(
                                    int op, String owner, String fName, String fDesc) {
                                notDup();
                                super.visitFieldInsn(op, owner, fName, fDesc);
                            }

                            @Override
                            public void visitLdcInsn(Object value) {
                                notDup();
                                super.visitLdcInsn(value);
                            }

                            @Override
                            public void visitFrame(
                                    int type, int nLocal, Object[] local, int nStack, Object[] st) {
                                straight("a stack map frame");
                                super.visitFrame(type, nLocal, local, nStack, st);
                            }

                            @Override
                            public void visitJumpInsn(int op, Label label) {
                                straight("a jump");
                                super.visitJumpInsn(op, label);
                            }

                            @Override
                            public void visitTableSwitchInsn(
                                    int min, int max, Label dflt, Label... labels) {
                                straight("a switch");
                                super.visitTableSwitchInsn(min, max, dflt, labels);
                            }

                            @Override
                            public void visitLookupSwitchInsn(
                                    Label dflt, int[] keys, Label[] labels) {
                                straight("a switch");
                                super.visitLookupSwitchInsn(dflt, keys, labels);
                            }

                            @Override
                            public void visitMethodInsn(
                                    int op, String owner, String mName, String mDesc, boolean itf) {
                                notDup();
                                if (op == Opcodes.INVOKESPECIAL
                                        && mName.equals("<init>")
                                        && !news.isEmpty()
                                        && news.get(news.size() - 1).equals(owner)) {
                                    news.remove(news.size() - 1);
                                    if (taken.remove(taken.size() - 1)) {
                                        Target t = newRule(owner, mDesc);
                                        if (t == null) {
                                            throw new IllegalStateException(
                                                    "no \"new\" rule for "
                                                            + owner
                                                            + ".<init>"
                                                            + mDesc
                                                            + " in "
                                                            + hereDesc);
                                        }
                                        allocate(
                                                owner + ".<init>:" + mDesc,
                                                t,
                                                Type.getArgumentTypes(mDesc),
                                                owner);
                                        return;
                                    }
                                }
                                String key = owner + "." + mName + ":" + mDesc;
                                Target t = pick(MAP.get(key), here, hereDesc);
                                boolean solo = t != null && t.solo;
                                hooks(BEFORE.get(key), "before ", key, solo);
                                rewrite(op, owner, mName, mDesc, itf, key);
                                hooks(AFTER.get(key), "after ", key, solo);
                            }

                            /**
                             * With {@code solo}, the hook counts as applied (the solo target plays
                             * its role) and is not inserted.
                             */
                            private void hooks(
                                    List<Target> l, String where, String key, boolean solo) {
                                if (l == null) {
                                    return;
                                }
                                for (Target h : l) {
                                    if (h.scope == null || inScope(h, here, hereDesc)) {
                                        hit(hookKey(where, key, h));
                                        if (solo) {
                                            continue;
                                        }
                                        super.visitMethodInsn(
                                                Opcodes.INVOKESTATIC,
                                                h.owner,
                                                h.name,
                                                "()V",
                                                false);
                                    }
                                }
                            }

                            private void rewrite(
                                    int op,
                                    String owner,
                                    String mName,
                                    String mDesc,
                                    boolean itf,
                                    String key) {
                                Target t = pick(MAP.get(key), here, hereDesc);
                                if (t == null) {
                                    super.visitMethodInsn(op, owner, mName, mDesc, itf);
                                    return;
                                }
                                hit(ruleKey(key, t));
                                if (t.toStatic) {
                                    // Receiver (if any) and reference parameters become Object,
                                    // as in body mode: the target cannot depend on the obfuscated
                                    // classes to compile. Also applies to a static call (with no
                                    // receiver). With the return erased, the caller needs the
                                    // return type.
                                    Type ret = Type.getReturnType(mDesc);
                                    super.visitMethodInsn(
                                            Opcodes.INVOKESTATIC,
                                            t.owner,
                                            t.name,
                                            erasedDescriptor(
                                                    Type.getArgumentTypes(mDesc),
                                                    ret,
                                                    op != Opcodes.INVOKESTATIC),
                                            false);
                                    castIfReference(this, ret);
                                } else {
                                    super.visitMethodInsn(op, t.owner, t.name, mDesc, itf);
                                }
                            }
                        };
                    }
                },
                0);
        return cw.toByteArray();
    }

    /** Emits: loads this and the arguments, calls the static target, returns the result. */
    private static void delegate(MethodVisitor out, int access, String desc, Target body) {
        boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
        Type[] args = Type.getArgumentTypes(desc);
        Type ret = Type.getReturnType(desc);

        out.visitCode();
        int slot = 0;
        if (!isStatic) {
            out.visitVarInsn(Opcodes.ALOAD, 0);
            slot = 1;
        }
        for (Type a : args) {
            out.visitVarInsn(a.getOpcode(Opcodes.ILOAD), slot);
            slot += a.getSize();
        }
        out.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                body.owner,
                body.name,
                erasedDescriptor(args, ret, !isStatic),
                false);
        // The target returns Object; the verifier requires the declared type.
        castIfReference(out, ret);
        out.visitInsn(ret.getOpcode(Opcodes.IRETURN));
        out.visitMaxs(Math.max(slot, ret.getSize()), slot);
        out.visitEnd();
    }

    /** The descriptor letter of a NEWARRAY operand (Opcodes.T_INT...). */
    private static String primitive(int operand) {
        return switch (operand) {
            case Opcodes.T_BOOLEAN -> "Z";
            case Opcodes.T_CHAR -> "C";
            case Opcodes.T_FLOAT -> "F";
            case Opcodes.T_DOUBLE -> "D";
            case Opcodes.T_BYTE -> "B";
            case Opcodes.T_SHORT -> "S";
            case Opcodes.T_INT -> "I";
            case Opcodes.T_LONG -> "J";
            default -> throw new IllegalArgumentException("NEWARRAY " + operand);
        };
    }

    /** "(" + optional Object receiver + each argument erased + ")" + the return erased. */
    private static String erasedDescriptor(Type[] args, Type ret, boolean withReceiver) {
        StringBuilder sd = new StringBuilder(withReceiver ? "(Ljava/lang/Object;" : "(");
        for (Type a : args) {
            sd.append(erase(a));
        }
        return sd.append(')').append(erase(ret)).toString();
    }

    /** After a call whose return was erased to Object: the CHECKCAST back to the declared type. */
    private static void castIfReference(MethodVisitor mv, Type ret) {
        if (ret.getSort() == Type.OBJECT || ret.getSort() == Type.ARRAY) {
            mv.visitTypeInsn(Opcodes.CHECKCAST, ret.getInternalName());
        }
    }

    private static String erase(Type t) {
        return t.getSort() == Type.OBJECT || t.getSort() == Type.ARRAY
                ? "Ljava/lang/Object;"
                : t.getDescriptor();
    }

    private static void hit(String key) {
        HITS.merge(key, 1, Integer::sum);
        hitCount++;
    }
}
