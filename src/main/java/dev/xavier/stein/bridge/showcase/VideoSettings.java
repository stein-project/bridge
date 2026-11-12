/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.showcase;

import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * The video options as data: the game's and Stein's, by topic, each one with its text, value, hint
 * and what changes it. One source for every screen that shows them: the Stein Loader's video
 * settings (over the showcase) and the Bridge's own, which stays for those without the Loader.
 *
 * <p>The Lens fills it ({@link #provide}); a screen reads {@link #sections()} when it opens and
 * calls {@link Source#changed()} after each change and {@link Source#closed()} when it closes.
 * Every text comes already translated. On the render thread.
 *
 * <p>Pipeline: both.
 */
public final class VideoSettings {

    /** How an option is changed. */
    public enum Kind {
        /**
         * Steps through values ({@link Entry#next}, {@link Entry#previous}); on/off has a state.
         */
        CHOICE,
        /** A track from 0 to 1 ({@link Entry#fraction}, {@link Entry#setFraction}). */
        SLIDER,
        /** Shown only: a value that is chosen elsewhere. */
        INFO,
        /** Does something when clicked ({@link Entry#next}): opens a step-by-step, for one. */
        ACTION
    }

    /** One option. */
    public static final class Entry {
        public final Kind kind;
        public final Supplier<String> label;
        public final Supplier<String> value;

        /** What it does, in plain words; empty for none. */
        public final Supplier<String> hint;

        /** Whether it can be changed now (it may depend on another option, or on the API). */
        public final BooleanSupplier enabled;

        /** On or off for the two-state ones (the screen draws a switch); null for the others. */
        public final BooleanSupplier state;

        public final Runnable next;
        public final Runnable previous;
        public final DoubleSupplier fraction;
        public final DoubleConsumer setFraction;

        /**
         * A stable name for the few options a screen looks for by itself (the HDR calibration:
         * "hdr", "hdrWhite", "hdrPeak"); null for the rest.
         */
        public String id;

        /**
         * Where a track starts out ({@link #fraction}), for screens that begin from the default
         * (the HDR calibration); negative when it has none.
         */
        public double byDefault = -1;

        /** Sets {@link #byDefault} and returns the option. */
        public Entry byDefault(double f) {
            this.byDefault = f;
            return this;
        }

        /** Names the option ({@link #id}) and returns it. */
        public Entry id(String name) {
            this.id = name;
            return this;
        }

        /**
         * A slider that rebuilds the world (the graphics preset, the render distance): the screen
         * applies it only when the drag ends.
         */
        public final boolean onRelease;

        private Entry(
                Kind kind,
                Supplier<String> label,
                Supplier<String> value,
                Supplier<String> hint,
                BooleanSupplier enabled,
                BooleanSupplier state,
                Runnable next,
                Runnable previous,
                DoubleSupplier fraction,
                DoubleConsumer setFraction,
                boolean onRelease) {
            this.kind = kind;
            this.label = label;
            this.value = value;
            this.hint = hint == null ? () -> "" : hint;
            this.enabled = enabled == null ? () -> true : enabled;
            this.state = state;
            this.next = next;
            this.previous = previous;
            this.fraction = fraction;
            this.setFraction = setFraction;
            this.onRelease = onRelease;
        }

        /** Steps through values; {@code state} non-null makes it a switch. */
        public static Entry choice(
                Supplier<String> label,
                Supplier<String> value,
                Supplier<String> hint,
                BooleanSupplier enabled,
                BooleanSupplier state,
                Runnable next,
                Runnable previous) {
            return new Entry(
                    Kind.CHOICE,
                    label,
                    value,
                    hint,
                    enabled,
                    state,
                    next,
                    previous == null ? next : previous,
                    null,
                    null,
                    false);
        }

        /** A track from 0 to 1; {@code value} is its text ("12 chunks"). */
        public static Entry slider(
                Supplier<String> label,
                Supplier<String> value,
                Supplier<String> hint,
                BooleanSupplier enabled,
                DoubleSupplier fraction,
                DoubleConsumer setFraction,
                boolean onRelease) {
            return new Entry(
                    Kind.SLIDER,
                    label,
                    value,
                    hint,
                    enabled,
                    null,
                    null,
                    null,
                    fraction,
                    setFraction,
                    onRelease);
        }

        /** Runs {@code run} when clicked; {@code value} is the text on its button. */
        public static Entry action(
                Supplier<String> label,
                Supplier<String> value,
                Supplier<String> hint,
                BooleanSupplier enabled,
                Runnable run) {
            return new Entry(
                    Kind.ACTION, label, value, hint, enabled, null, run, run, null, null, false);
        }

        /** Shown only. */
        public static Entry info(
                Supplier<String> label, Supplier<String> value, Supplier<String> hint) {
            return new Entry(
                    Kind.INFO,
                    label,
                    value,
                    hint,
                    () -> false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false);
        }
    }

    /** A topic: its title and its options. */
    public static final class Section {
        /** Stable name of the topic ("general", "image"...), for icons and remembering the tab. */
        public final String id;

        public final Supplier<String> title;
        public final List<Entry> entries;

        public Section(String id, Supplier<String> title, List<Entry> entries) {
            this.id = id;
            this.title = title;
            this.entries = Collections.unmodifiableList(entries);
        }
    }

    /** Who owns the options (the Lens). */
    public interface Source {
        /** The topics, built now (the values are read live through the suppliers). */
        List<Section> sections();

        /** After a change: save and apply. */
        void changed();

        /** The screen closed: save the game's options too. */
        void closed();
    }

    private static volatile Source source;

    private VideoSettings() {}

    /** The Lens, at startup. */
    public static void provide(Source s) {
        source = s;
    }

    /** The owner of the options, or null when nothing provided them. */
    public static Source source() {
        return source;
    }

    /** The option named {@code id} ({@link Entry#id}) among the topics now, or null. */
    public static Entry find(String id) {
        for (Section s : sections()) {
            for (Entry e : s.entries) {
                if (id.equals(e.id)) {
                    return e;
                }
            }
        }
        return null;
    }

    /** The topics now; empty when nothing provided them. */
    public static List<Section> sections() {
        Source s = source;
        return s == null ? Collections.emptyList() : s.sections();
    }
}
