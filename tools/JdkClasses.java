/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/**
 * The classes of the running JDK (every module, from the jrt file system) in one jar: the library
 * ProGuard reads the JDK from in the obfuscated build. Not every JDK 25 ships the jmods folder (JEP
 * 493), the runtime image always has the classes.
 *
 * <p>Usage: {@code JdkClasses out.jar}
 */
public final class JdkClasses {

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: JdkClasses out.jar");
            System.exit(2);
        }
        Path out = Paths.get(args[0]);
        Files.createDirectories(out.toAbsolutePath().getParent());
        FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
        int classes = 0;
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(out));
                Stream<Path> modules = Files.list(jrt.getPath("/modules"))) {
            for (Path module : modules.sorted().toList()) {
                List<Path> files;
                try (Stream<Path> walk = Files.walk(module)) {
                    files =
                            walk.filter(p -> p.toString().endsWith(".class"))
                                    .filter(
                                            p ->
                                                    !p.getFileName()
                                                            .toString()
                                                            .equals("module-info.class"))
                                    .sorted()
                                    .toList();
                }
                for (Path f : files) {
                    JarEntry e = new JarEntry(module.relativize(f).toString());
                    e.setTime(0);
                    jar.putNextEntry(e);
                    jar.write(Files.readAllBytes(f));
                    jar.closeEntry();
                    classes++;
                }
            }
        }
        System.out.println("JDK " + Runtime.version() + ": " + classes + " classes in " + out);
    }

    private JdkClasses() {}
}
