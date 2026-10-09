package com.dsmod.probe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Thin facade over the encrypted patch-stream decoder.  The main DEX only knows the
 * {@link Delta} result shape; the channel-tracking logic that separates chain-of-thought from
 * public answer text lives inside the decrypted payload ({@code z12decoder}).
 */
public final class NativeApiPatchDecoder {
    public static final class Delta {
        public String text = "";
        public String reasoning = "";
        public String textSet;
        public String reasoningSet;
    }

    private static final String CORE = "com.dsmod.probe.z12decoder";
    private static Constructor<?> coreCtor;
    private static Method coreDecode;

    private final Object core;

    public NativeApiPatchDecoder() {
        Object instance = null;
        try {
            Constructor<?> ctor = coreConstructor();
            if (ctor != null) instance = ctor.newInstance();
        } catch (Throwable ignored) {}
        core = instance;
    }

    public Delta decode(Object json) {
        Delta out = new Delta();
        if (core == null) return out;
        try {
            Method m = decodeMethod();
            if (m != null) m.invoke(core, json, out);
        } catch (Throwable ignored) {}
        return out;
    }

    /**
     * True when the Closed-only decoder payload is present in this build.
     *
     * The channel-tracking implementation lives in the encrypted secondary
     * payload ({@code z12decoder}). The Open edition ships {@code z14} as a stub
     * whose {@code available()} returns false and whose {@code payloadClass()}
     * returns null, so {@link #decode(Object)} is a documented no-op there.
     * Callers that need to distinguish "decoded to empty" from "cannot decode
     * in this edition" should consult this first.
     */
    public static boolean coreAvailable() {
        if (!z14.available()) return false;
        return coreConstructor() != null;
    }

    private static Constructor<?> coreConstructor() {
        if (coreCtor != null) return coreCtor;
        try {
            Class<?> cls = z14.payloadClass(null, CORE);
            if (cls == null) return null;
            Constructor<?> c = cls.getDeclaredConstructor();
            c.setAccessible(true);
            coreCtor = c;
            return c;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method decodeMethod() {
        if (coreDecode != null) return coreDecode;
        try {
            Class<?> cls = z14.payloadClass(null, CORE);
            if (cls == null) return null;
            for (Method m : cls.getDeclaredMethods()) {
                if ("decode".equals(m.getName()) && m.getParameterTypes().length == 2) {
                    m.setAccessible(true);
                    coreDecode = m;
                    return m;
                }
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
