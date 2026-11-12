package org.schabi.newpipe.extractor.utils;

import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * JavaScript in QuickJS, through TestTube's native library ({@code libtesttube_core.so}, built
 * from {@code native/testtube-core}).
 *
 * <p>
 * Rhino started a new context and parsed the code again on every call. For the n parameter that
 * code is the whole player file (a few MB), so every new video paid seconds before it could play.
 * Here each code is evaluated once and kept; later calls only call the function.
 * </p>
 *
 * <p>
 * If the library can't be loaded (unit tests on the JVM, an ABI we don't ship) {@link #isAvailable()}
 * is false and {@link JavaScript} keeps using Rhino.
 * </p>
 */
public final class NativeJavaScript {
    private static final int VERSION = 1;
    /** As many as the native side keeps (MAX_SOURCES); it tells us when it dropped one anyway. */
    private static final int MAX_HANDLES = 4;

    private static final boolean AVAILABLE = loadLibrary();
    private static volatile boolean enabled = true;

    /** Code to its native handle. Keys are the extractor's own cached strings, so lookups hit
     * String's cached hash and an identity equals. */
    private static final Map<String, Long> HANDLES =
            new LinkedHashMap<>(MAX_HANDLES * 2, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(final Map.Entry<String, Long> eldest) {
                    return size() > MAX_HANDLES;
                }
            };

    private NativeJavaScript() {
    }

    private static boolean loadLibrary() {
        try {
            System.loadLibrary("testtube_core");
            return nativeVersion() == VERSION;
        } catch (final Throwable notThere) {
            // UnsatisfiedLinkError, SecurityException: Rhino it is
            return false;
        }
    }

    /** Whether calls go to QuickJS. */
    public static boolean isAvailable() {
        return AVAILABLE && enabled;
    }

    /** Switch QuickJS off (or back on), e.g. to compare with Rhino. */
    public static void setEnabled(final boolean value) {
        enabled = value;
    }

    /**
     * Evaluates {@code code} now, so the first {@link #run} with it doesn't have to. Throws if it
     * doesn't parse or its top level throws.
     */
    public static synchronized void prepare(@Nonnull final String code) {
        handle(code);
    }

    /** Throws if {@code code} doesn't parse. Doesn't run or keep anything. */
    public static void check(@Nonnull final String code) {
        nativeCheck(code);
    }

    @Nonnull
    public static synchronized String run(@Nonnull final String code,
                                          @Nonnull final String functionName,
                                          @Nonnull final String... parameters) {
        final Long known = HANDLES.get(code);
        if (known != null) {
            final String result = nativeCall(known, functionName, parameters);
            if (result != null) {
                return result;
            }
            // dropped on the native side: load it again below
        }
        final String result = nativeCall(handle(code), functionName, parameters);
        if (result == null) {
            throw new IllegalStateException("Native JavaScript dropped code it just loaded");
        }
        return result;
    }

    /** Forgets everything evaluated so far, freeing its memory. */
    public static synchronized void clear() {
        HANDLES.clear();
        if (AVAILABLE) {
            nativeClear();
        }
    }

    private static long handle(@Nonnull final String code) {
        final long handle = nativeLoad(code);
        HANDLES.put(code, handle);
        return handle;
    }

    private static native int nativeVersion();

    private static native long nativeLoad(@Nonnull String code);

    @Nullable
    private static native String nativeCall(long handle, @Nonnull String functionName,
                                            @Nonnull String[] parameters);

    private static native void nativeCheck(@Nonnull String code);

    private static native void nativeClear();
}
