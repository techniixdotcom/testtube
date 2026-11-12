package org.schabi.newpipe.extractor.utils;

import java.lang.ref.WeakReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * {@code matcher.find()} for big texts (YouTube's player file, HTML pages), with the scanning
 * done by TestTube's native library ({@code libtesttube_core.so}, {@code regex_scan.rs}).
 *
 * <p>
 * Java tries a pattern at every position of a multi-MB text; Rust's regex engine skips ahead
 * with literal prefilters. The native side runs a relaxed version of the pattern that matches
 * wherever the Java one can (and maybe at a few more places), so its first hit is never after
 * Java's first match. Java then runs its own pattern from that position, which gives exactly
 * the {@link Matcher} a plain {@code matcher.find()} would. When the relaxed pattern doesn't
 * match at all, the Java one can't either. Patterns the native side can't relax, small texts
 * and a missing library all fall back to a plain {@code matcher.find()}.
 * </p>
 */
public final class NativeRegex {
    private static final int VERSION = 1;
    /**
     * Shorter texts stay in Java: copying the text across JNI costs about as much as Java's
     * own search there.
     */
    static final int MIN_NATIVE_LENGTH = 64 * 1024;

    private static final long NO_MATCH = -1;
    private static final long UNSUPPORTED = -2;
    private static final long TEXT_DROPPED = -3;

    private static final boolean AVAILABLE = loadLibrary();
    private static volatile boolean enabled = true;

    /** The text last sent to the native side, by identity, and its handle there. */
    @Nonnull
    private static WeakReference<String> loadedText = new WeakReference<>(null);
    private static long loadedHandle;

    private NativeRegex() {
    }

    private static boolean loadLibrary() {
        try {
            System.loadLibrary("testtube_core");
            return nativeVersion() == VERSION;
        } catch (final Throwable notThere) {
            return false;
        }
    }

    /** Whether big texts are searched natively. */
    public static boolean isAvailable() {
        return AVAILABLE && enabled;
    }

    /** Switch the native search off (or back on), e.g. to compare with plain Java. */
    public static void setEnabled(final boolean value) {
        enabled = value;
    }

    /**
     * Like {@code pattern.matcher(input).find()}.
     *
     * @return the matcher, positioned on the first match, or {@code null} if there's none
     */
    @Nullable
    public static Matcher find(@Nonnull final Pattern pattern, final String input) {
        final Matcher matcher = pattern.matcher(input);
        if (input.length() >= MIN_NATIVE_LENGTH && isAvailable()) {
            final long start = firstPossibleMatch(pattern, input);
            if (start == NO_MATCH) {
                return null;
            }
            if (start >= 0 && start <= input.length()) {
                // find(int) resets the matcher, so lookbehinds and \b still see what's before
                return matcher.find((int) start) ? matcher : null;
            }
        }
        return matcher.find() ? matcher : null;
    }

    /** Drops the texts and patterns kept on the native side. */
    public static synchronized void clear() {
        loadedText = new WeakReference<>(null);
        loadedHandle = 0;
        if (AVAILABLE) {
            nativeClear();
        }
    }

    private static synchronized long firstPossibleMatch(@Nonnull final Pattern pattern,
                                                        @Nonnull final String input) {
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                if (loadedText.get() != input) {
                    loadedHandle = nativeLoadText(input);
                    loadedText = new WeakReference<>(input);
                }
                final long result = nativeFind(loadedHandle, pattern.pattern(), pattern.flags(),
                        0);
                if (result != TEXT_DROPPED) {
                    return result;
                }
                loadedText = new WeakReference<>(null);
            }
        } catch (final RuntimeException nativeFailed) {
            // Java searches itself
        }
        return UNSUPPORTED;
    }

    private static native int nativeVersion();

    private static native long nativeLoadText(@Nonnull String text);

    private static native long nativeFind(long text, @Nonnull String pattern, int flags,
                                          int from);

    private static native void nativeClear();
}
