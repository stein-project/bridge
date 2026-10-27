/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.bench;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Whether the machine runs on AC or battery, the charge and battery saver, from Windows' {@code
 * GetSystemPowerStatus} (a laptop on battery runs its processor and GPU slower: the report has to
 * say). Called once, through Java's foreign function API; empty off Windows or on any failure.
 */
final class BenchPower {

    private BenchPower() {}

    private static Charset console;

    /**
     * The charset console programs (reg, typeperf, powercfg) write in: the OEM code page (850 on a
     * Brazilian Windows), not Java's default; the default charset off Windows or on failure.
     */
    static synchronized Charset consoleCharset() {
        if (console != null) {
            return console;
        }
        console = Charset.defaultCharset();
        try (Arena arena = Arena.ofConfined()) {
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", arena);
            MethodHandle call =
                    Linker.nativeLinker()
                            .downcallHandle(
                                    kernel32.find("GetOEMCP").orElseThrow(),
                                    FunctionDescriptor.of(ValueLayout.JAVA_INT));
            int cp = (int) call.invokeExact();
            console = Charset.forName(cp == 65001 ? "UTF-8" : "Cp" + cp);
        } catch (Throwable e) {
            // not Windows, or the code page has no Java charset: the default stays
        }
        return console;
    }

    static Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        try (Arena arena = Arena.ofConfined()) {
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", arena);
            MethodHandle call =
                    Linker.nativeLinker()
                            .downcallHandle(
                                    kernel32.find("GetSystemPowerStatus").orElseThrow(),
                                    FunctionDescriptor.of(
                                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            // SYSTEM_POWER_STATUS: ACLineStatus, BatteryFlag, BatteryLifePercent,
            // SystemStatusFlag (bytes), BatteryLifeTime, BatteryFullLifeTime (DWORDs)
            MemorySegment s = arena.allocate(12);
            if ((int) call.invokeExact(s) == 0) {
                return m;
            }
            int ac = s.get(ValueLayout.JAVA_BYTE, 0) & 0xFF;
            int flag = s.get(ValueLayout.JAVA_BYTE, 1) & 0xFF;
            int percent = s.get(ValueLayout.JAVA_BYTE, 2) & 0xFF;
            int saver = s.get(ValueLayout.JAVA_BYTE, 3) & 0xFF;
            m.put("power_source", ac == 1 ? "ac" : ac == 0 ? "battery" : "unknown");
            m.put("battery", flag == 128 ? "none" : percent == 255 ? "unknown" : percent + "%");
            m.put("battery_saver", saver == 1);
        } catch (Throwable e) {
            m.put("power_source", "unknown (" + e.getClass().getSimpleName() + ")");
        }
        return m;
    }
}
