/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.core;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.lookup.StrLookup;
import org.apache.logging.log4j.core.lookup.StrSubstitutor;

import java.beans.PropertyChangeListener;
import java.util.Locale;

/**
 * Closes Log4Shell (CVE-2021-44228) in the game's log4j 2.0-beta9, whatever launcher opens it.
 *
 * <p>1.8.9 still ships log4j-core 2.0-beta9. Mojang never updated it: its fix is a logging config
 * ({@code client-1.7.xml}) that the official launcher passes through {@code
 * -Dlog4j.configurationFile}. A launcher that does not read the version's {@code logging} block
 * (the Stein Client, pre-2021 launchers, any other client built on the Bridge) leaves the game on
 * the {@code log4j2.xml} inside its jar, where a chat line such as {@code ${jndi:ldap://host/a}}
 * makes the client open a connection to whoever the server names, and {@code ${env:...}} reads the
 * player's environment.
 *
 * <p>beta9 expands {@code ${...}} inside a message by asking the configuration's {@link
 * StrSubstitutor}. Its resolver is swapped here for one that, like log4j 2.16, resolves nothing at
 * log time (the text is written as typed, no line is dropped) and never resolves JNDI. Variables in
 * the config file itself are resolved as before. The swap is repeated when log4j reloads its
 * configuration.
 */
public final class LogLookups {

    private static final String TAG = "SteinBridge";

    private LogLookups() {}

    /** Called once by the launch entry point, before the game logs anything. */
    public static void install() {
        try {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            harden(ctx.getConfiguration());
            PropertyChangeListener reload =
                    e -> {
                        if (e.getNewValue() instanceof Configuration c) {
                            harden(c);
                        }
                    };
            ctx.addPropertyChangeListener(reload);
        } catch (Throwable t) {
            // A log4j other than the game's (another client's libraries): not ours to fix, and the
            // game must still start.
            Log.warn(TAG, "log lookups left as they are: " + t);
        }
    }

    private static void harden(Configuration config) {
        StrSubstitutor substitutor = config.getStrSubstitutor();
        StrLookup resolver = substitutor.getVariableResolver();
        if (resolver != null && !(resolver instanceof ConfigOnly)) {
            substitutor.setVariableResolver(new ConfigOnly(resolver));
        }
    }

    /** The original lookups for the config file only, and never JNDI. */
    private static final class ConfigOnly implements StrLookup {

        private final StrLookup original;

        ConfigOnly(StrLookup original) {
            this.original = original;
        }

        @Override
        public String lookup(String key) {
            return jndi(key) ? null : original.lookup(key);
        }

        @Override
        public String lookup(LogEvent event, String key) {
            // With an event, the variable came from a message being logged: its text is not ours.
            if (event != null || jndi(key)) {
                return null;
            }
            return original.lookup(null, key);
        }

        private static boolean jndi(String key) {
            return key != null && key.strip().toLowerCase(Locale.ROOT).startsWith("jndi:");
        }
    }
}
