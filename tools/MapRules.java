/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The patcher rules with the obfuscated names and without a single comment, for the installer and
 * for the Loader's ModPatch (the obfuscated build, {@code ./gradlew dist}).
 *
 * <p>The installer patches the game jar on the player's machine, so the rules travel with it. As
 * written, they explain the whole engine: each line says which game method is replaced, by which of
 * our classes, and the comment above says why. Translated through the ProGuard mapping and without
 * comments they become a table of opaque names on both sides: the game's are already obfuscated by
 * Mojang, and ours are now too.
 *
 * <p>Usage: {@code MapRules mapping.txt out-dir rule.tsv...}
 *
 * <p>Each rule: {@code class TAB name2 TAB descriptor TAB name3 [TAB newOwner [TAB mode [TAB
 * scope]]]}. The {@code newOwner} (our class) and {@code name3} (its method) are translated. A rule
 * without newOwner is an LWJGL rename, which is not ours and stays as it is. An ambiguous name (two
 * methods with the same name going to different obfuscated names) stops the program: better to fail
 * here than to have the player's game call the wrong method.
 */
public final class MapRules {

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("usage: MapRules mapping.txt out-dir rule.tsv...");
            System.exit(2);
        }
        Map<String, String> classes = new HashMap<>();
        Map<String, Map<String, String>> members = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        read(Paths.get(args[0]), classes, members, ambiguous);

        Path out = Paths.get(args[1]);
        Files.createDirectories(out);
        int translated = 0;
        int kept = 0;
        for (int i = 2; i < args.length; i++) {
            Path in = Paths.get(args[i]);
            List<String> lines = new ArrayList<>();
            for (String line : Files.readAllLines(in, StandardCharsets.UTF_8)) {
                String clean = line.strip();
                if (clean.isEmpty() || clean.startsWith("#")) {
                    continue; // comments do not go to the player's machine
                }
                String[] c = line.split("\t", -1);
                if (c.length < 5 || c[4].isEmpty()) {
                    lines.add(line);
                    kept++;
                    continue;
                }
                String owner = c[4].replace('/', '.');
                String obfOwner = classes.get(owner);
                if (obfOwner == null) {
                    lines.add(line); // not one of ours (the game's, or "-")
                    kept++;
                    continue;
                }
                if (ambiguous.contains(owner + "." + c[3])) {
                    System.err.println(
                            "ambiguous name in the mapping: "
                                    + owner
                                    + "."
                                    + c[3]
                                    + " (two methods with that name and different targets)");
                    System.exit(1);
                }
                String member = members.getOrDefault(owner, Map.of()).get(c[3]);
                if (member == null) {
                    // The class is in the mapping and the method is not: the rule names a method
                    // the obfuscated jar does not have. Without this the rule would keep the
                    // original name and the player's game would find out at the first frame, as
                    // NoSuchMethodError.
                    System.err.println(
                            "method not in the mapping: "
                                    + owner
                                    + "."
                                    + c[3]
                                    + " (the obfuscated jar does not have it)");
                    System.exit(1);
                }
                c[4] = obfOwner.replace('.', '/');
                c[3] = member;
                lines.add(String.join("\t", c));
                translated++;
            }
            Files.write(out.resolve(in.getFileName().toString()), lines, StandardCharsets.UTF_8);
        }
        System.out.println(
                "patcher rules: "
                        + translated
                        + " translated, "
                        + kept
                        + " without an owner of ours, in "
                        + out);
    }

    /** Reads the ProGuard mapping: "package.Class -> package.a:" and, indented, its members. */
    private static void read(
            Path map,
            Map<String, String> classes,
            Map<String, Map<String, String>> members,
            Set<String> ambiguous)
            throws IOException {
        String current = null;
        for (String line : Files.readAllLines(map, StandardCharsets.UTF_8)) {
            if (line.startsWith("#")) {
                continue;
            }
            int arrow = line.indexOf(" -> ");
            if (arrow < 0) {
                continue;
            }
            if (!line.startsWith(" ") && line.endsWith(":")) {
                current = line.substring(0, arrow).strip();
                String to = line.substring(arrow + 4, line.length() - 1).strip();
                // Every class, renamed or not: a class that keeps its name (-keepnames) can still
                // have its
                // methods renamed.
                classes.put(current, to);
                continue;
            }
            if (current == null) {
                continue;
            }
            // "    10:11:void method(long) -> a" or "    java.lang.String FIELD -> a"
            String left = line.substring(0, arrow).strip();
            String to = line.substring(arrow + 4).strip();
            int paren = left.indexOf('(');
            if (paren < 0) {
                continue; // a field: the rules only name methods
            }
            int space = left.lastIndexOf(' ', paren);
            String name = left.substring(space + 1, paren);
            Map<String, String> byName = members.computeIfAbsent(current, k -> new HashMap<>());
            String previous = byName.put(name, to);
            if (previous != null && !previous.equals(to)) {
                ambiguous.add(current + "." + name);
            }
        }
    }

    private MapRules() {}
}
