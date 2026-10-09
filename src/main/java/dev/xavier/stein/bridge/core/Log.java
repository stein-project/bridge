/*
 * Copyright (c) 2026 Breno Xavier. All rights reserved.
 * Proprietary software: see the LICENSE file at the root of this repository.
 */

package dev.xavier.stein.bridge.core;

/**
 * The Bridge's console log: every message is one line, {@code [tag] message}, on the game's
 * standard output (the launcher console and {@code logs/latest.log}).
 *
 * <p>This is not {@link SteinLog}: that one is the opt-in topic log the player turns on in the
 * Stein menu, written to its own file. Use {@code Log} for what the Bridge always reports
 * (start-up, failures, the {@code *Check} verification modes, diagnostics), and keep messages on
 * cold paths: build the message only when it is printed.
 */
public final class Log {

    private Log() {}

    /** Prints {@code [tag] message}. */
    public static void info(String tag, String message) {
        System.out.println("[" + tag + "] " + message);
    }

    /** Prints {@code [tag] message} with {@link String#format} arguments. */
    public static void info(String tag, String format, Object... args) {
        System.out.println("[" + tag + "] " + String.format(format, args));
    }

    /**
     * Prints {@code [tag] message} and then where it was called from, one {@code [tag] at ...} line
     * per frame, on the standard output: for diagnostics that need the origin of a call (the {@code
     * *Check} modes), not for errors.
     */
    public static void trace(String tag, String message) {
        StackTraceElement[] stack = new Throwable().getStackTrace();
        StringBuilder b = new StringBuilder("[").append(tag).append("] ").append(message);
        for (int i = 1; i < stack.length; i++) {
            b.append(System.lineSeparator())
                    .append('[')
                    .append(tag)
                    .append("]   at ")
                    .append(stack[i]);
        }
        System.out.println(b);
    }

    /** Prints {@code [tag] warning: message} on the error output. */
    public static void warn(String tag, String message) {
        System.err.println("[" + tag + "] warning: " + message);
    }

    /** Prints {@code [tag] message: error} on the error output, followed by the stack trace. */
    public static void error(String tag, String message, Throwable error) {
        System.err.println("[" + tag + "] " + message + ": " + error);
        error.printStackTrace();
    }
}
