/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * The {@code -Dsteinbridge.*} option names become short codes in the jars that reach players (the
 * obfuscated build, {@code ./gradlew dist}).
 *
 * <p>ProGuard renames classes and members but leaves text alone: without this step the obfuscated
 * jar still lists every option by name ({@code steinbridge.opt.chunkArena}...), which is an index
 * of the engine. Each name becomes a code ({@code s1a2}) and the real name stays in the table, in
 * build/dist-mapping (never distributed). The Loader's {@code runClient}/{@code verify} with {@code
 * -Pstein.dist} translate the {@code -D} names through that table.
 *
 * <p>Usage: {@code Codes table-out.txt [--base table-in.txt] jar...} (rewrites each jar in place).
 * With {@code --base}, the names already in that table keep their codes (the Loader is coded with
 * the Bridge's table, so an option both read has one code).
 *
 * <p>What is a name: {@code steinbridge.<area>.<name>}, letters, digits and dots. Two more shapes
 * keep the code consistent when the code reads options by prefix:
 *
 * <ul>
 *   <li>a prefix constant, {@code "steinbridge.diag."} (Scenes looks for any diag option with
 *       startsWith): the prefix becomes the area code ({@code s1a3.}) and every option of that area
 *       is coded as area code + its own code, so startsWith still finds them;
 *   <li>a prefix concatenated with a name at run time, {@code "steinbridge.gfx." + name} (a {@code
 *       makeConcatWithConstants} recipe): the name part cannot be coded, so the options of that
 *       area become area code + the name as written ({@code s1a4.textCache}).
 * </ul>
 *
 * <p>Each table line is {@code code TAB name [TAB prefix|prefix-plain]}: the Loader's run tasks
 * translate an exact name first, then a {@code prefix-plain} area by its prefix.
 *
 * <p>The old build also coded log messages ({@code "[tag] ..."}); the code now logs through {@code
 * core.Log.info(tag, message)}, which builds the tag at run time, so there is nothing of that shape
 * left to code, and the {@code verify} task reads the check lines as they are.
 */
public final class Codes {

    /** Markers of a string concatenation recipe: an argument and a constant. */
    private static final char ARG = '\u0001';

    private static final char CONST = '\u0002';

    private static final String ROOT = "steinbridge.";

    private final Map<String, String> table = new LinkedHashMap<>();
    private final Map<String, String> areaCodes = new LinkedHashMap<>();

    /** Areas read by prefix ("steinbridge.diag."). */
    private final Set<String> prefixAreas = new HashSet<>();

    /** Areas whose prefix is concatenated with a name at run time: the name part stays as is. */
    private final Set<String> plainAreas = new HashSet<>();

    private final List<String> warnings = new ArrayList<>();
    private int next;

    /**
     * An option name: steinbridge.opt.chunkArena and the like (at least one dot after the root).
     */
    private static boolean flag(String s) {
        if (!s.startsWith(ROOT) || s.indexOf('.', ROOT.length()) < 0 || s.endsWith(".")) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '.') {
                return false;
            }
        }
        return true;
    }

    /** The area of "steinbridge.<area>." (a prefix constant), or null. */
    private static String prefixArea(String s) {
        if (!s.startsWith(ROOT) || !s.endsWith(".")) {
            return null;
        }
        String area = s.substring(ROOT.length(), s.length() - 1);
        if (area.isEmpty()) {
            return null;
        }
        for (int i = 0; i < area.length(); i++) {
            if (!Character.isLetterOrDigit(area.charAt(i))) {
                return null;
            }
        }
        return area;
    }

    /** The leading plain text of a recipe (up to the first marker). */
    private static String lead(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) != ARG && s.charAt(i) != CONST) {
            i++;
        }
        return s.substring(0, i);
    }

    /** First pass: which areas the code reads by prefix, and which by prefix + name. */
    private void survey(String s) {
        String lead = lead(s);
        String area = prefixArea(lead);
        if (area == null) {
            return;
        }
        prefixAreas.add(area);
        if (lead.length() < s.length()) {
            plainAreas.add(area);
        }
    }

    /** Three characters, from "100" on. */
    private String id() {
        return Integer.toString(next++ + 1296, 36);
    }

    private String areaCode(String area) {
        return areaCodes.computeIfAbsent(area, a -> "s" + id() + ".");
    }

    /** The code of this constant, or null when it is not one of ours. */
    private String rewrite(String s) {
        String done = table.get(s);
        if (done != null) {
            return done;
        }
        String lead = lead(s);
        if (lead.length() < s.length()) {
            // A recipe: only "steinbridge.<area>." + something is ours.
            String area = prefixArea(lead);
            if (area != null) {
                return areaCode(area) + s.substring(lead.length());
            }
            if (lead.startsWith(ROOT) && lead.indexOf('.', ROOT.length()) >= 0) {
                warnings.add("option name built at run time, left as is: " + escape(s));
            }
            return null;
        }
        String area = prefixArea(s);
        if (area != null) {
            String code = areaCode(area);
            table.put(s, code);
            return code;
        }
        if (!flag(s)) {
            return null;
        }
        String optionArea = s.substring(ROOT.length(), s.indexOf('.', ROOT.length()));
        String code;
        if (plainAreas.contains(optionArea)) {
            code = areaCode(optionArea) + s.substring(ROOT.length() + optionArea.length() + 1);
        } else if (prefixAreas.contains(optionArea)) {
            code = areaCode(optionArea) + id();
        } else {
            code = "s" + id();
        }
        table.put(s, code);
        return code;
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: Codes table-out.txt [--base table-in.txt] jar...");
            System.exit(2);
        }
        Codes codes = new Codes();
        int first = 1;
        if (args.length > 2 && args[1].equals("--base")) {
            codes.base(Paths.get(args[2]));
            first = 3;
        }
        List<Path> jars = new ArrayList<>();
        for (int i = first; i < args.length; i++) {
            jars.add(Paths.get(args[i]));
        }
        for (Path jar : jars) {
            codes.jar(jar, false);
        }
        int classes = 0;
        for (Path jar : jars) {
            classes += codes.jar(jar, true);
        }
        for (String w : codes.warnings) {
            System.out.println("warning: " + w);
        }
        codes.write(Paths.get(args[0]));
        System.out.println(
                "option names as codes: "
                        + codes.table.size()
                        + " names, "
                        + classes
                        + " classes, table in "
                        + args[0]);
    }

    /** Reads a table written by this program: its names keep their codes. */
    private void base(Path file) throws IOException {
        int max = -1;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.startsWith("#") || line.isBlank()) {
                continue;
            }
            String[] c = line.split("\t");
            String code = c[0];
            String name = c[1];
            String kind = c.length > 2 ? c[2] : "";
            if (kind.startsWith("prefix")) {
                String area = prefixArea(name);
                areaCodes.put(area, code);
                prefixAreas.add(area);
                if (kind.equals("prefix-plain")) {
                    plainAreas.add(area);
                }
            } else {
                table.put(name, code);
            }
            for (String part : code.substring(1).split("\\.")) {
                if (part.length() == 3) {
                    try {
                        max = Math.max(max, Integer.parseInt(part, 36) - 1296);
                    } catch (NumberFormatException plainName) {
                        // the name part of a prefix-plain option
                    }
                }
            }
        }
        next = max + 1;
    }

    private void write(Path file) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add(
                "# code\tname[\tkind] - DO NOT DISTRIBUTE: the option names the jars no longer have");
        for (Map.Entry<String, String> e : areaCodes.entrySet()) {
            lines.add(
                    e.getValue()
                            + "\t"
                            + ROOT
                            + e.getKey()
                            + ".\t"
                            + (plainAreas.contains(e.getKey()) ? "prefix-plain" : "prefix"));
        }
        for (Map.Entry<String, String> e : table.entrySet()) {
            if (prefixArea(e.getKey()) == null) {
                lines.add(e.getValue() + "\t" + e.getKey());
            }
        }
        Files.write(file, lines, StandardCharsets.UTF_8);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\t", "\\t")
                .replace(String.valueOf(ARG), "\\1")
                .replace(String.valueOf(CONST), "\\2");
    }

    /** Surveys (write = false) or rewrites in place (write = true) one jar; returns its classes. */
    private int jar(Path path, boolean write) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".codes");
        int classes = 0;
        try (JarFile in = new JarFile(path.toFile());
                JarOutputStream out =
                        write ? new JarOutputStream(Files.newOutputStream(tmp)) : null) {
            for (Enumeration<JarEntry> e = in.entries(); e.hasMoreElements(); ) {
                JarEntry entry = e.nextElement();
                byte[] body = read(in.getInputStream(entry));
                if (entry.getName().endsWith(".class")) {
                    if (write) {
                        body = clazz(body, s -> rewrite(s));
                    } else {
                        clazz(
                                body,
                                s -> {
                                    survey(s);
                                    return null;
                                });
                    }
                    classes++;
                }
                if (write) {
                    JarEntry copy = new JarEntry(entry.getName());
                    copy.setTime(entry.getTime());
                    out.putNextEntry(copy);
                    out.write(body);
                    out.closeEntry();
                }
            }
        }
        if (write) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
        return classes;
    }

    private static byte[] read(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            s.transferTo(buffer);
            return buffer.toByteArray();
        }
    }

    /**
     * A ClassWriter without the reader: with it, ASM would copy the whole constant pool and the old
     * text would stay in the class.
     */
    private static byte[] clazz(byte[] body, Function<String, String> codes) {
        ClassReader reader = new ClassReader(body);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new Strings(writer, codes), 0);
        return writer.toByteArray();
    }

    /**
     * Every text constant: instruction, field value, invokedynamic argument and annotation value.
     */
    private static final class Strings extends ClassVisitor {

        private final Function<String, String> codes;

        Strings(ClassVisitor next, Function<String, String> codes) {
            super(Opcodes.ASM9, next);
            this.codes = codes;
        }

        private Object value(Object o) {
            if (o instanceof String s) {
                String code = codes.apply(s);
                return code != null ? code : s;
            }
            return o;
        }

        @Override
        public FieldVisitor visitField(
                int access, String name, String desc, String signature, Object value) {
            return new Field(super.visitField(access, name, desc, signature, value(value)));
        }

        @Override
        public MethodVisitor visitMethod(
                int access, String name, String desc, String signature, String[] thrown) {
            return new Method(super.visitMethod(access, name, desc, signature, thrown));
        }

        @Override
        public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
            return new Note(super.visitAnnotation(desc, visible));
        }

        private final class Field extends FieldVisitor {
            Field(FieldVisitor next) {
                super(Opcodes.ASM9, next);
            }

            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                return new Note(super.visitAnnotation(desc, visible));
            }
        }

        private final class Note extends AnnotationVisitor {
            Note(AnnotationVisitor next) {
                super(Opcodes.ASM9, next);
            }

            @Override
            public void visit(String name, Object value) {
                super.visit(name, value(value));
            }
        }

        private final class Method extends MethodVisitor {
            Method(MethodVisitor next) {
                super(Opcodes.ASM9, next);
            }

            @Override
            public void visitLdcInsn(Object cst) {
                super.visitLdcInsn(value(cst));
            }

            @Override
            public void visitInvokeDynamicInsn(
                    String name, String desc, Handle bsm, Object... args) {
                Object[] copy = new Object[args.length];
                for (int i = 0; i < args.length; i++) {
                    copy[i] = value(args[i]);
                }
                super.visitInvokeDynamicInsn(name, desc, bsm, copy);
            }

            @Override
            public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                return new Note(super.visitAnnotation(desc, visible));
            }
        }
    }

    private Codes() {}
}
