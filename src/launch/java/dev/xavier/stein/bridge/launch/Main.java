/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.bridge.launch;

/**
 * Game entry point into SteinBridge: checks the Java version before loading anything from the game
 * or from SteinBridge.
 *
 * <p>Deliberately compiled with {@code --release 8} (the rest of SteinBridge is Java 25): when
 * opened by a launcher or profile with an old Java, this class still loads and explains what is
 * missing, instead of the game crashing with UnsupportedClassVersionError on the first class of
 * ours. It only uses the Java 8 API.
 */
public final class Main {

    /** Minimum version: the bridge (LWJGL 3, FFM, the engine) is written for Java 25. */
    static final int REQUIRED = 25;

    private Main() {}

    public static void main(String[] args) throws Throwable {
        String spec = System.getProperty("java.specification.version", "0");
        int feature = feature(spec);
        if (feature < REQUIRED) {
            refuse(feature);
            return;
        }
        Class<?> game = Class.forName("net.minecraft.client.main.Main");
        java.lang.reflect.Method main = game.getMethod("main", String[].class);
        try {
            main.invoke(null, (Object) args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /** "1.8" -> 8; "25" -> 25; unreadable -> 0. */
    static int feature(String spec) {
        try {
            String s = spec.startsWith("1.") ? spec.substring(2) : spec;
            int dot = s.indexOf('.');
            return Integer.parseInt(dot < 0 ? s : s.substring(0, dot));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void refuse(int feature) {
        // Runs before the game: no game classes, no language files. A small table chosen by the
        // system language (pt -> Portuguese, es -> Spanish, anything else -> English).
        String lang = java.util.Locale.getDefault().getLanguage();
        String[] t;
        if ("pt".equals(lang)) {
            t =
                    new String[] {
                        "O SteinBridge precisa do Java ",
                        "Este e o Java ",
                        ", em:\n",
                        "Abra pelo perfil \"SteinBridge 1.8.9\" criado pelo instalador (ele usa o"
                                + " Java 25 que o instalador trouxe) ou aponte o Java do perfil para um"
                                + " Java 25."
                    };
        } else if ("es".equals(lang)) {
            t =
                    new String[] {
                        "SteinBridge necesita Java ",
                        "Este es Java ",
                        ", en:\n",
                        "Abre el juego con el perfil \"SteinBridge 1.8.9\" creado por el instalador"
                                + " (usa el Java 25 que trajo el instalador) o apunta el Java del"
                                + " perfil a un Java 25."
                    };
        } else {
            t =
                    new String[] {
                        "SteinBridge needs Java ",
                        "This is Java ",
                        ", at:\n",
                        "Launch from the \"SteinBridge 1.8.9\" profile created by the installer (it"
                                + " uses the Java 25 the installer brought) or point the profile's Java"
                                + " to a Java 25."
                    };
        }
        String msg =
                t[0]
                        + REQUIRED
                        + ".\n\n"
                        + t[1]
                        + (feature > 0 ? String.valueOf(feature) : "?")
                        + t[2]
                        + System.getProperty("java.home")
                        + "\n\n"
                        + t[3];
        System.err.println("[steinbridge] " + msg.replace('\n', ' '));
        try {
            if (!java.awt.GraphicsEnvironment.isHeadless()) {
                javax.swing.JOptionPane.showMessageDialog(
                        null, msg, "SteinBridge", javax.swing.JOptionPane.ERROR_MESSAGE);
            }
        } catch (Throwable ignored) {
            // no graphical interface: the message already went to the log
        }
        System.exit(1);
    }
}
