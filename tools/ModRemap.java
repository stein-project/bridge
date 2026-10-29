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
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Translates a mod compiled for Forge 1.8.9 to the names of the 1.8.9 jar that SteinBridge runs.
 *
 * <p>A Forge mod in production references Minecraft classes by their MCP names
 * (net/minecraft/client/Minecraft) and members by SRG names (func_71410_x, field_71441_e). The game
 * here runs with obfuscated names (ave, A, f). MCP's joined.srg links the three; this program
 * applies the reverse direction: MCP/SRG -> obfuscated. SRG names are unique across the whole game
 * (an override has the same SRG name and the same obfuscated name as the method it overrides), so
 * the by-name map also resolves overrides in the mod's classes, without needing the hierarchy.
 *
 * <p>String constants with an SRG name (reflection: getDeclaredField("field_...") or names passed
 * to obfuscation tables) and with an MCP class name are also translated.
 *
 * <p>Nothing from the mod enters the repository: input and output stay in build/.
 *
 * <p>Usage: ModRemap &lt;joined.srg&gt; &lt;output.jar&gt; [options] &lt;input.jar|dir&gt;...
 *
 * <pre>
 *   --rename from=to   renames a class (internal name, with /), in the mod and in references
 *   --drop class       does not copy the class (nor its inner ones)
 *   --ldc from=to      replaces an exact string constant
 *   --check mc.jar     checks, against the game jar, that every reference resolves
 *   --calls prefix out writes the calls to the package prefix (e.g. org/lwjgl/opengl/) to the file
 * </pre>
 */
public final class ModRemap {

    /** Packages of libraries and the JDK: classes there are not checked against the game jar. */
    private static final String[] KNOWN_PREFIXES = {
        "java/",
        "javax/",
        "jdk/",
        "sun/",
        // jdk.management, in the runtime the installer builds (the benchmark's allocation and GC)
        "com/sun/management/",
        "com/google/",
        "org/apache/",
        "io/netty/",
        "com/mojang/",
        "org/lwjgl/",
        "net/java/games/",
        "org/objectweb/",
        "com/ibm/icu/",
        "tv/twitch/",
        "oshi/",
        "org/xml/",
        "org/w3c/",
        "paulscode/",
        "com/jcraft/",
        "net/minecraft/launchwrapper/",
        "dev/xavier/stein/",
        "gnu/trove/",
        "org/json/"
    };

    private ModRemap() {}

    private static final Map<String, String> CLASS = new HashMap<>(); // MCP -> obfuscated
    private static final Map<String, String> METHOD = new HashMap<>(); // func_ -> obfuscated
    private static final Map<String, String> METHOD_BY_OWNER =
            new HashMap<>(); // owner/func_desc -> obfuscated
    private static final Set<String> AMBIGUOUS = new HashSet<>();
    private static final Map<String, String> METHOD_BY_DESC =
            new HashMap<>(); // func_desc -> obfuscated
    private static final Set<String> AMBIGUOUS_DESC = new HashSet<>();
    private static final Map<String, String> FIELD = new HashMap<>(); // field_ -> obfuscated
    private static final Map<String, String> FIELD_BY_OWNER =
            new HashMap<>(); // owner/name -> obfuscated
    private static final Map<String, String> RENAME = new LinkedHashMap<>();
    private static final Map<String, String> LDC = new HashMap<>();
    private static final Set<String> DROP = new HashSet<>();
    private static final Set<String> MISSING = new TreeSet<>();
    private static final Pattern SRG = Pattern.compile("(func|field)_\\d+_[A-Za-z]+");

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println(
                    "usage: ModRemap <joined.srg> <output.jar> [--rename from=to] [--drop class]"
                            + " [--ldc from=to] [--check mc.jar] [--calls prefix file]"
                            + " <input.jar|dir>...");
            System.exit(2);
        }
        loadSrg(Paths.get(args[0]));
        Path out = Paths.get(args[1]);
        List<Path> inputs = new ArrayList<>();
        Path check = null;
        String callsPrefix = null;
        Path callsOut = null;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--calls" -> {
                    callsPrefix = args[++i];
                    callsOut = Paths.get(args[++i]);
                }
                case "--rename" -> {
                    String[] kv = args[++i].split("=", 2);
                    RENAME.put(kv[0], kv[1]);
                }
                case "--drop" -> DROP.add(args[++i]);
                case "--ldc" -> {
                    String[] kv = args[++i].split("=", 2);
                    LDC.put(kv[0], kv[1]);
                }
                case "--check" -> check = Paths.get(args[++i]);
                default -> inputs.add(Paths.get(args[i]));
            }
        }

        Map<String, byte[]> classes = new LinkedHashMap<>();
        Map<String, byte[]> resources = new LinkedHashMap<>();
        translateInputs(inputs, classes, resources);

        Files.createDirectories(out.toAbsolutePath().getParent());
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(out))) {
            write(jos, resources);
            write(jos, classes);
        }
        System.out.println(
                "classes: " + classes.size() + " | resources: " + resources.size() + " -> " + out);
        if (!MISSING.isEmpty()) {
            System.out.println(MISSING.size() + " MCP/SRG name(s) without a translation:");
            MISSING.forEach(m -> System.out.println("  " + m));
        }
        if (callsOut != null) {
            writeCalls(classes, callsPrefix, callsOut);
        }
        if (check != null) {
            int bad = new Checker(check, classes).run();
            System.out.println(
                    bad == 0
                            ? "check: every reference to the game resolves"
                            : "check: " + bad + " unresolved reference(s)");
            if (bad > 0 || !MISSING.isEmpty()) {
                System.exit(1);
            }
        }
    }

    /**
     * Translates every class of the inputs (SRG to obfuscated) into classes; the rest, resources.
     */
    private static void translateInputs(
            List<Path> inputs, Map<String, byte[]> classes, Map<String, byte[]> resources)
            throws IOException {
        Remapper r = new SrgToObf();
        for (Path in : inputs) {
            for (Map.Entry<String, byte[]> e : read(in).entrySet()) {
                String n = e.getKey();
                if (n.endsWith(".class")) {
                    String cn = n.substring(0, n.length() - 6);
                    if (dropped(cn)) {
                        continue;
                    }
                    ClassReader cr = new ClassReader(e.getValue());
                    ClassWriter cw = new ClassWriter(0);
                    cr.accept(new ClassRemapper(cw, r), 0);
                    String outName = r.map(cn) + ".class";
                    if (classes.putIfAbsent(outName, cw.toByteArray()) != null) {
                        System.out.println(
                                "  WARNING duplicate class, keeping the first: " + outName);
                    }
                } else if (!n.endsWith("/") && !isSignature(n)) {
                    resources.putIfAbsent(n, e.getValue());
                }
            }
        }
    }

    /** Calls to a package (e.g. org/lwjgl/opengl/), in the format of scan/gl-calls.txt. */
    private static void writeCalls(Map<String, byte[]> classes, String prefix, Path out)
            throws IOException {
        Set<String> calls = new TreeSet<>();
        for (byte[] b : classes.values()) {
            for (Ref ref : Info.of(b).refs) {
                if (!ref.field() && ref.owner().startsWith(prefix)) {
                    calls.add(ref.owner() + "." + ref.name() + ":" + ref.desc());
                }
            }
        }
        Files.write(out, calls);
    }

    // ---- joined.srg ----

    private static void loadSrg(Path srg) throws IOException {
        for (String line : Files.readAllLines(srg)) {
            String[] f = line.trim().split(" ");
            switch (f[0]) {
                case "CL:" -> CLASS.put(f[2], f[1]);
                case "FD:" -> {
                    FIELD.put(last(f[2]), last(f[1]));
                    FIELD_BY_OWNER.put(f[2], last(f[1]));
                }
                case "MD:" -> {
                    String srgName = last(f[3]);
                    String obf = last(f[1]);
                    String prev = METHOD.putIfAbsent(srgName, obf);
                    if (prev != null && !prev.equals(obf)) {
                        AMBIGUOUS.add(srgName);
                    }
                    METHOD_BY_OWNER.put(owner(f[3]) + "/" + srgName + f[4], obf);
                    // Covariant return: the method and the synthetic bridge have the same SRG
                    // name and different obfuscated names; the descriptor tells the two apart.
                    String prevDesc = METHOD_BY_DESC.putIfAbsent(srgName + f[4], obf);
                    if (prevDesc != null && !prevDesc.equals(obf)) {
                        AMBIGUOUS_DESC.add(srgName + f[4]);
                    }
                }
                default -> {}
            }
        }
        System.out.println(
                "joined.srg: "
                        + CLASS.size()
                        + " classes, "
                        + FIELD.size()
                        + " fields, "
                        + METHOD.size()
                        + " methods ("
                        + AMBIGUOUS.size()
                        + " SRG names with more than one obfuscated name)");
    }

    private static String last(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String owner(String path) {
        return path.substring(0, path.lastIndexOf('/'));
    }

    // ---- translation ----

    private static final class SrgToObf extends Remapper {
        @Override
        public String map(String internalName) {
            String r = RENAME.get(internalName);
            if (r != null) {
                return r;
            }
            String o = CLASS.get(internalName);
            if (o != null) {
                return o;
            }
            if (internalName.startsWith("net/minecraft/")
                    && !internalName.startsWith("net/minecraft/launchwrapper/")) {
                MISSING.add("classe " + internalName);
            }
            return internalName;
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            return member(owner, name, descriptor, true);
        }

        @Override
        public String mapInvokeDynamicMethodName(String name, String descriptor) {
            return member(null, name, descriptor, true);
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            return member(owner, name, descriptor, false);
        }

        @Override
        public Object mapValue(Object value) {
            if (value instanceof String s) {
                return mapString(s);
            }
            return super.mapValue(value);
        }
    }

    private static String member(String owner, String name, String desc, boolean method) {
        if (!name.startsWith(method ? "func_" : "field_")) {
            // MCP names that are not SRG — enum constants (CHAT_OPACITY), values():
            // are not unique in the game, so only by the owner declared in the reference.
            if (owner != null) {
                String o =
                        method
                                ? METHOD_BY_OWNER.get(owner + "/" + name + desc)
                                : FIELD_BY_OWNER.get(owner + "/" + name);
                if (o != null) {
                    return o;
                }
            }
            return name;
        }
        if (method) {
            if (AMBIGUOUS.contains(name)) {
                String o = owner == null ? null : METHOD_BY_OWNER.get(owner + "/" + name + desc);
                if (o != null) {
                    return o;
                }
                // Owner that does not declare the method (mod class, or subclass): by the
                // descriptor, if it points to a single obfuscated name.
                if (!AMBIGUOUS_DESC.contains(name + desc)) {
                    o = METHOD_BY_DESC.get(name + desc);
                    if (o != null) {
                        return o;
                    }
                }
                MISSING.add(
                        "ambiguous, no owner or descriptor in joined.srg: "
                                + owner
                                + "."
                                + name
                                + " "
                                + desc);
            }
            String o = METHOD.get(name);
            if (o != null) {
                return o;
            }
        } else {
            String o = FIELD.get(name);
            if (o != null) {
                return o;
            }
        }
        MISSING.add(
                (method ? "method " : "field ")
                        + (owner == null ? "" : owner + ".")
                        + name
                        + " "
                        + desc);
        return name;
    }

    private static String mapString(String s) {
        String l = LDC.get(s);
        if (l != null) {
            s = l; // may be an SRG name, translated next
        }
        if (SRG.matcher(s).matches()) {
            String o = s.startsWith("func_") ? METHOD.get(s) : FIELD.get(s);
            return o != null ? o : s;
        }
        if (s.startsWith("net/minecraft/") || s.startsWith("net.minecraft.")) {
            String o = CLASS.get(s.replace('.', '/'));
            if (o != null) {
                return s.indexOf('/') >= 0 ? o : o.replace('/', '.');
            }
        }
        return s;
    }

    private static boolean dropped(String cn) {
        for (String d : DROP) {
            if (cn.equals(d) || cn.startsWith(d + "$")) {
                return true;
            }
        }
        return false;
    }

    // ---- input and output ----

    private static Map<String, byte[]> read(Path in) throws IOException {
        Map<String, byte[]> m = new LinkedHashMap<>();
        if (Files.isDirectory(in)) {
            try (Stream<Path> s = Files.walk(in)) {
                for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                    m.put(in.relativize(p).toString().replace('\\', '/'), Files.readAllBytes(p));
                }
            }
        } else {
            try (ZipFile z = new ZipFile(in.toFile())) {
                for (ZipEntry e : Collections.list(z.entries())) {
                    if (!e.isDirectory()) {
                        try (InputStream is = z.getInputStream(e)) {
                            m.put(e.getName(), is.readAllBytes());
                        }
                    }
                }
            }
        }
        return m;
    }

    private static boolean isSignature(String n) {
        return n.startsWith("META-INF/")
                && (n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA"));
    }

    private static void write(JarOutputStream jos, Map<String, byte[]> entries) throws IOException {
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            jos.putNextEntry(new JarEntry(e.getKey()));
            jos.write(e.getValue());
            jos.closeEntry();
        }
    }

    // ---- check ----

    /**
     * Every reference from the translated mod to a game class must resolve in the game jar, going
     * up the hierarchy. What is left over is a member that Forge added to Minecraft (the mod would
     * call something the vanilla game does not have) or a class of Forge itself.
     */
    private static final class Checker {
        private final Map<String, Info> game = new HashMap<>();
        private final Map<String, Info> mod = new HashMap<>();
        private final Set<String> bad = new TreeSet<>();

        Checker(Path mcJar, Map<String, byte[]> modClasses) throws IOException {
            try (ZipFile z = new ZipFile(mcJar.toFile())) {
                for (ZipEntry e : Collections.list(z.entries())) {
                    if (e.getName().endsWith(".class")) {
                        try (InputStream is = z.getInputStream(e)) {
                            Info i = Info.of(is.readAllBytes());
                            game.put(i.name, i);
                        }
                    }
                }
            }
            for (byte[] b : modClasses.values()) {
                Info i = Info.of(b);
                mod.put(i.name, i);
            }
        }

        int run() {
            for (Info c : mod.values()) {
                for (String t : c.types) {
                    type(c, t);
                }
                for (Ref ref : c.refs) {
                    ref(c, ref);
                }
            }
            bad.forEach(b -> System.out.println("  " + b));
            return bad.size();
        }

        private void type(Info from, String t) {
            if (!known(t)) {
                bad.add("missing class " + t + " (in " + from.name + ")");
            }
        }

        private void ref(Info from, Ref ref) {
            String owner = ref.owner();
            String name = ref.name();
            String desc = ref.desc();
            if (owner.startsWith("[")) {
                return;
            }
            if (!known(owner)) {
                bad.add("missing class " + owner + " (in " + from.name + ")");
                return;
            }
            if (!game.containsKey(owner) && !mod.containsKey(owner)) {
                return; // library or JDK: outside the translation scope
            }
            if (!resolves(owner, ref.field() ? name : name + desc, ref.field(), new HashSet<>())) {
                bad.add(
                        (ref.field() ? "field" : "method")
                                + " missing "
                                + owner
                                + "."
                                + name
                                + " "
                                + desc
                                + " (in "
                                + from.name
                                + ")");
            }
        }

        /** Goes up the hierarchy in the game and in the mod; for the JDK, checks by reflection. */
        private boolean resolves(String cls, String member, boolean field, Set<String> seen) {
            if (!seen.add(cls)) {
                return false;
            }
            Info i = mod.containsKey(cls) ? mod.get(cls) : game.get(cls);
            if (i == null) {
                return outside(cls, member, field);
            }
            if ((field ? i.fields : i.methods).contains(member)) {
                return true;
            }
            if (i.superName != null && resolves(i.superName, member, field, seen)) {
                return true;
            }
            for (String itf : i.interfaces) {
                if (resolves(itf, member, field, seen)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Class outside the game and the mod: if the JDK has it, the member must exist in it (e.g.
         * a game enum constant with a wrong name goes up to java/lang/Enum and must not pass).
         * Library outside the classpath here: no way to check.
         */
        private static boolean outside(String cls, String member, boolean field) {
            Class<?> c;
            try {
                c = Class.forName(cls.replace('/', '.'), false, ClassLoader.getSystemClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                return true;
            }
            return has(c, member, field, new HashSet<>());
        }

        private static boolean has(Class<?> c, String member, boolean field, Set<Class<?>> seen) {
            if (c == null || !seen.add(c)) {
                return false;
            }
            if (field) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getName().equals(member)) {
                        return true;
                    }
                }
            } else {
                for (Method m : c.getDeclaredMethods()) {
                    if ((m.getName() + Type.getMethodDescriptor(m)).equals(member)) {
                        return true;
                    }
                }
                for (Constructor<?> k : c.getDeclaredConstructors()) {
                    if (("<init>" + Type.getConstructorDescriptor(k)).equals(member)) {
                        return true;
                    }
                }
            }
            for (Class<?> itf : c.getInterfaces()) {
                if (has(itf, member, field, seen)) {
                    return true;
                }
            }
            return has(c.getSuperclass(), member, field, seen)
                    || (c.isInterface() && has(Object.class, member, field, seen));
        }

        /** Class of the game, of the mod, or of a library/JDK package. */
        private boolean known(String t) {
            if (game.containsKey(t) || mod.containsKey(t)) {
                return true;
            }
            for (String p : KNOWN_PREFIXES) {
                if (t.startsWith(p)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** A reference to a field ({@code field}) or method of {@code owner}. */
    private record Ref(boolean field, String owner, String name, String desc) {}

    /** The minimum of a class to resolve references. */
    private static final class Info {
        String name;
        String superName;
        String[] interfaces = new String[0];
        final Set<String> methods = new HashSet<>();
        final Set<String> fields = new HashSet<>();
        final Set<String> types = new HashSet<>();
        final List<Ref> refs = new ArrayList<>();

        static Info of(byte[] b) {
            Info i = new Info();
            new ClassReader(b)
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                @Override
                                public void visit(
                                        int v,
                                        int acc,
                                        String name,
                                        String sig,
                                        String sup,
                                        String[] itfs) {
                                    i.name = name;
                                    i.superName = sup;
                                    i.interfaces = itfs == null ? new String[0] : itfs;
                                    if (sup != null) {
                                        i.types.add(sup);
                                    }
                                    for (String t : i.interfaces) {
                                        i.types.add(t);
                                    }
                                }

                                @Override
                                public FieldVisitor visitField(
                                        int acc,
                                        String name,
                                        String desc,
                                        String sig,
                                        Object value) {
                                    i.fields.add(name);
                                    return null;
                                }

                                @Override
                                public MethodVisitor visitMethod(
                                        int acc,
                                        String name,
                                        String desc,
                                        String sig,
                                        String[] ex) {
                                    i.methods.add(name + desc);
                                    return new MethodVisitor(Opcodes.ASM9) {
                                        @Override
                                        public void visitMethodInsn(
                                                int op,
                                                String owner,
                                                String n,
                                                String d,
                                                boolean itf) {
                                            i.refs.add(new Ref(false, owner, n, d));
                                        }

                                        @Override
                                        public void visitFieldInsn(
                                                int op, String owner, String n, String d) {
                                            i.refs.add(new Ref(true, owner, n, d));
                                        }

                                        @Override
                                        public void visitTypeInsn(int op, String type) {
                                            if (!type.startsWith("[")) {
                                                i.types.add(type);
                                            }
                                        }

                                        @Override
                                        public void visitLdcInsn(Object v) {
                                            if (v instanceof Type t && t.getSort() == Type.OBJECT) {
                                                i.types.add(t.getInternalName());
                                            } else if (v instanceof Handle h) {
                                                i.refs.add(
                                                        new Ref(
                                                                false,
                                                                h.getOwner(),
                                                                h.getName(),
                                                                h.getDesc()));
                                            }
                                        }
                                    };
                                }
                            },
                            ClassReader.SKIP_FRAMES);
            return i;
        }
    }
}
