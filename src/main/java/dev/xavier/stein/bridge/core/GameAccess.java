/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 Breno Xavier
 */

package dev.xavier.stein.bridge.core;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Bootstrap of the invokedynamic instructions that reach non-public members of the game.
 *
 * <p>The Bridge is written against the game with its readable names and with every member opened
 * (build-time only); in the real game a private field or a package-private method is still closed,
 * and the JVM would reject the plain instruction. The build step {@code tools/AccessRewrite.java}
 * swaps each such instruction for an invokedynamic that lands here <b>once</b>, on first use. A
 * constant {@link MethodHandle} for the member comes back, and from then on the JIT treats the call
 * site like direct access.
 *
 * <p>Not for hand-written code: the build writes the calls (the numbering of the kinds is shared
 * with that tool).
 */
public final class GameAccess {

    /** Instance field read. */
    public static final int GET = 0;

    /** Instance field write. */
    public static final int PUT = 1;

    /** Static field read. */
    public static final int GET_STATIC = 2;

    /** Static field write. */
    public static final int PUT_STATIC = 3;

    /** Instance method call. */
    public static final int VIRTUAL = 4;

    /** Static method call. */
    public static final int STATIC = 5;

    private GameAccess() {}

    /**
     * Links the call site to the member.
     *
     * @param name member name
     * @param type the original instruction's stack: receiver (if any), arguments and return
     * @param kind {@link #GET}, {@link #PUT}, ...
     * @param owner the class that declares the member, with slashes
     * @param desc member descriptor (field or method)
     */
    public static CallSite bootstrap(
            MethodHandles.Lookup caller,
            String name,
            MethodType type,
            int kind,
            String owner,
            String desc)
            throws ReflectiveOperationException {
        ClassLoader cl = caller.lookupClass().getClassLoader();
        Class<?> c = Class.forName(owner.replace('/', '.'), false, cl);
        MethodHandle h;
        if (kind <= PUT_STATIC) {
            // By name AND type: the obfuscated game has fields that share a name (Vec3i.a is both x
            // and
            // a static constant).
            Field f =
                    field(
                            c,
                            name,
                            MethodType.fromMethodDescriptorString("()" + desc, cl).returnType());
            f.setAccessible(true);
            // unreflectSetter accepts an instance final field after setAccessible.
            h =
                    kind == GET || kind == GET_STATIC
                            ? caller.unreflectGetter(f)
                            : caller.unreflectSetter(f);
        } else {
            h = caller.unreflect(method(c, name, MethodType.fromMethodDescriptorString(desc, cl)));
        }
        return new ConstantCallSite(h.asType(type));
    }

    private static Field field(Class<?> c, String name, Class<?> type) throws NoSuchFieldException {
        for (Field f : c.getDeclaredFields()) {
            if (f.getName().equals(name) && f.getType() == type) {
                f.setAccessible(true);
                return f;
            }
        }
        throw new NoSuchFieldException(c.getName() + "." + name + " " + type.getName());
    }

    private static Method method(Class<?> c, String name, MethodType want)
            throws NoSuchMethodException {
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name)
                    && m.getReturnType() == want.returnType()
                    && Arrays.equals(m.getParameterTypes(), want.parameterArray())) {
                m.setAccessible(true);
                return m;
            }
        }
        throw new NoSuchMethodException(c.getName() + "." + name + want);
    }
}
