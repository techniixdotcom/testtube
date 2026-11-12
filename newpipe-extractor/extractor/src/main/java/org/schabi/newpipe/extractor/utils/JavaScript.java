package org.schabi.newpipe.extractor.utils;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.ScriptableObject;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Runs the JavaScript pulled out of YouTube's player. QuickJS ({@link NativeJavaScript}) when the
 * native library is there, Rhino otherwise, and Rhino again if QuickJS fails on something, so a
 * difference between the two engines can never cost playback.
 */
public final class JavaScript {
    @Nullable
    private static JavaScriptInterpreter externalJavaScriptInterpreter;

    private JavaScript() {
    }

    public static void compileOrThrow(final String function) {
        if (NativeJavaScript.isAvailable()) {
            try {
                NativeJavaScript.check(function);
                return;
            } catch (final RuntimeException quickJsSays) {
                // let Rhino have the last word, it's what the extractor was written against
            }
        }
        rhinoCompile(function);
    }

    /**
     * Checks {@code code} and, with QuickJS, also evaluates and keeps it, so the first
     * {@link #run} with it only has to call. Use it for code that's about to be run (like a
     * stored deobfuscation function); with Rhino it's {@link #compileOrThrow}.
     */
    public static void prepareOrThrow(@Nonnull final String code) {
        if (NativeJavaScript.isAvailable()) {
            try {
                NativeJavaScript.prepare(code);
                return;
            } catch (final RuntimeException quickJsSays) {
                // fall through to Rhino's verdict
            }
        }
        rhinoCompile(code);
    }

    public static String run(final String function,
                             final String functionName,
                             final String... parameters) {
        if (externalJavaScriptInterpreter != null) {
            return externalJavaScriptInterpreter.run(function, functionName, parameters);
        }
        if (NativeJavaScript.isAvailable()) {
            try {
                return NativeJavaScript.run(function, functionName, parameters);
            } catch (final RuntimeException quickJsFailed) {
                // slow, but it's what always worked
            }
        }
        return rhinoRun(function, functionName, parameters);
    }

    private static void rhinoCompile(final String function) {
        try (Context context = Context.enter()) {
            context.setInterpretedMode(true);

            // If it doesn't compile it throws an exception here
            context.compileString(function, null, 1, null);
        }
    }

    private static String rhinoRun(final String function,
                                   final String functionName,
                                   final String... parameters) {
        try (Context context = Context.enter()) {
            context.setInterpretedMode(true);
            final ScriptableObject scope = context.initSafeStandardObjects();

            context.evaluateString(scope, function, functionName, 1, null);
            final Function jsFunction = (Function) scope.get(functionName, scope);
            final Object result = jsFunction.call(context, scope, scope, parameters);
            return result.toString();
        }
    }

    /**
     * Sets an external JavaScript interpreter, that is used to run JavaScript code.
     *
     * @param javaScriptInterpreter the complete JavaScript base player code
     */
    public static void setExternalJavaScriptInterpreter(
            @Nullable final JavaScriptInterpreter javaScriptInterpreter) {
        JavaScript.externalJavaScriptInterpreter = javaScriptInterpreter;
    }
}
