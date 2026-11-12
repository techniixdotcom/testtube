package org.schabi.newpipe.extractor.services.youtube;

import static org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty;

import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.utils.JavaScript;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Manage the extraction and the usage of YouTube's player JavaScript needed data in the YouTube
 * service.
 *
 * <p>
 * YouTube restrict streaming their media in multiple ways by requiring their HTML5 clients to use
 * a signature timestamp, and on streaming URLs a signature deobfuscation function for some
 * contents and a throttling parameter deobfuscation one for all contents.
 * </p>
 *
 * <p>
 * This class provides access to methods which allows to get base JavaScript player's signature
 * timestamp and to deobfuscate streaming URLs' signature and/or throttling parameter of HTML5
 * clients.
 * </p>
 *
 * <p>
 * Everything extracted from the player file can also be kept between launches with
 * {@link #setPersistentCache(PersistentCache)}. Stored values are tied to the player file's URL,
 * so a new player version is always extracted again, and the multi-megabyte player file is only
 * downloaded when something for the current version is missing.
 * </p>
 *
 * <p>
 * All methods are synchronized: extractions run in parallel and used to share these caches with
 * no locking at all.
 * </p>
 */
public final class YoutubeJavaScriptPlayerManager {

    /**
     * Storage that outlives the process, for values extracted from the player file.
     */
    public interface PersistentCache {
        @Nullable
        String read(@Nonnull String key);

        void write(@Nonnull String key, @Nonnull String value);
    }

    private static final String KEY_SIGNATURE_TIMESTAMP = "signature_timestamp";
    private static final String KEY_SIGNATURE_FUNCTION = "signature_function";
    private static final String KEY_THROTTLING_FUNCTION = "throttling_function";
    private static final char URL_SEPARATOR = '\n';

    @Nonnull
    private static final Map<String, String> CACHED_THROTTLING_PARAMETERS = new HashMap<>();

    @Nullable
    private static PersistentCache persistentCache;

    @Nullable
    private static String cachedPlayerUrl;
    @Nullable
    private static String cachedJavaScriptPlayerCode;

    @Nullable
    private static Integer cachedSignatureTimestamp;
    @Nullable
    private static String cachedSignatureDeobfuscationFunction;
    @Nullable
    private static String cachedThrottlingDeobfuscationFunction;

    @Nullable
    private static ParsingException throttlingDeobfFuncExtractionEx;
    @Nullable
    private static ParsingException sigDeobFuncExtractionEx;
    @Nullable
    private static ParsingException sigTimestampExtractionEx;

    private YoutubeJavaScriptPlayerManager() {
    }

    /**
     * Sets where extracted values are kept between launches, or {@code null} to keep them in
     * memory only.
     *
     * @param cache the storage to use
     */
    public static synchronized void setPersistentCache(@Nullable final PersistentCache cache) {
        persistentCache = cache;
    }

    /**
     * Get the signature timestamp of the base JavaScript player file.
     *
     * <p>
     * A valid signature timestamp sent in the payload of player InnerTube requests is required to
     * get valid stream URLs on HTML5 clients for videos which have obfuscated signatures.
     * </p>
     *
     * <p>
     * The base JavaScript player file will be fetched if it is not already done.
     * </p>
     *
     * <p>
     * The result of the extraction is cached until {@link #clearAllCaches()} is called, making
     * subsequent calls faster.
     * </p>
     *
     * @param videoId the video ID used to get the JavaScript base player file (an empty one can be
     *                passed, even it is not recommended in order to spoof better official YouTube
     *                clients)
     * @return the signature timestamp of the base JavaScript player file
     * @throws ParsingException if the extraction of the base JavaScript player file or the
     * signature timestamp failed
     */
    @Nonnull
    public static synchronized Integer getSignatureTimestamp(@Nonnull final String videoId)
            throws ParsingException {
        // Return the cached result if it is present
        if (cachedSignatureTimestamp != null) {
            return cachedSignatureTimestamp;
        }

        // If the signature timestamp has been not extracted on a previous call, this means that we
        // will fail to extract it on next calls too if the player code has been not changed
        // Throw again the corresponding stored exception in this case to improve performance
        if (sigTimestampExtractionEx != null) {
            throw sigTimestampExtractionEx;
        }

        resolvePlayerUrlIfNeeded(videoId);
        final String stored = readStored(KEY_SIGNATURE_TIMESTAMP);
        if (stored != null) {
            try {
                cachedSignatureTimestamp = Integer.valueOf(stored);
                return cachedSignatureTimestamp;
            } catch (final NumberFormatException ignored) {
                // Garbage on disk, extract it again below
            }
        }

        final String playerCode = playerCode(videoId);
        final Integer signatureTimestamp;
        try {
            signatureTimestamp = Integer.valueOf(
                    YoutubeSignatureUtils.getSignatureTimestamp(playerCode));
        } catch (final ParsingException e) {
            // Store the exception for future calls of this method, in order to improve performance
            sigTimestampExtractionEx = e;
            throw e;
        } catch (final NumberFormatException e) {
            sigTimestampExtractionEx =
                    new ParsingException("Could not convert signature timestamp to a number", e);
            throw sigTimestampExtractionEx;
        } catch (final Exception e) {
            sigTimestampExtractionEx = new ParsingException("Could not get signature timestamp", e);
            throw e;
        }

        cachedSignatureTimestamp = signatureTimestamp;
        writeStored(KEY_SIGNATURE_TIMESTAMP, signatureTimestamp.toString());
        return signatureTimestamp;
    }

    /**
     * Deobfuscate a signature of a streaming URL using its corresponding JavaScript base player's
     * function.
     *
     * <p>
     * Obfuscated signatures are only present on streaming URLs of some videos with HTML5 clients.
     * </p>
     *
     * @param videoId             the video ID used to get the JavaScript base player file (an
     *                            empty one can be passed, even it is not recommended in order to
     *                            spoof better official YouTube clients)
     * @param obfuscatedSignature the obfuscated signature of a streaming URL
     * @return the deobfuscated signature
     * @throws ParsingException if the extraction of the base JavaScript player file or the
     * signature deobfuscation function failed
     */
    @Nonnull
    public static synchronized String deobfuscateSignature(
            @Nonnull final String videoId,
            @Nonnull final String obfuscatedSignature) throws ParsingException {
        // If the signature deobfuscation function has been not extracted on a previous call, this
        // means that we will fail to extract it on next calls too if the player code has been not
        // changed
        // Throw again the corresponding stored exception in this case to improve performance
        if (sigDeobFuncExtractionEx != null) {
            throw sigDeobFuncExtractionEx;
        }

        if (cachedSignatureDeobfuscationFunction == null) {
            resolvePlayerUrlIfNeeded(videoId);
            cachedSignatureDeobfuscationFunction = readStoredFunction(KEY_SIGNATURE_FUNCTION);
        }

        if (cachedSignatureDeobfuscationFunction == null) {
            final String playerCode = playerCode(videoId);
            final String function;
            try {
                function = YoutubeSignatureUtils.getDeobfuscationCode(playerCode);
            } catch (final ParsingException e) {
                // Store the exception for future calls of this method in order to improve
                // performance
                sigDeobFuncExtractionEx = e;
                throw e;
            } catch (final Exception e) {
                sigDeobFuncExtractionEx = new ParsingException(
                        "Could not get signature parameter deobfuscation JavaScript function", e);
                throw e;
            }
            cachedSignatureDeobfuscationFunction = function;
            writeStored(KEY_SIGNATURE_FUNCTION, function);
        }

        try {
            // Return an empty parameter in the case the function returns null
            return Objects.requireNonNullElse(
                    JavaScript.run(cachedSignatureDeobfuscationFunction,
                            YoutubeSignatureUtils.DEOBFUSCATION_FUNCTION_NAME,
                            obfuscatedSignature), "");
        } catch (final Exception e) {
            // This shouldn't happen as the function validity is checked when it is extracted
            throw new ParsingException(
                    "Could not run signature parameter deobfuscation JavaScript function", e);
        }
    }

    /**
     * Return a streaming URL with the throttling parameter of a given one deobfuscated, if it is
     * present, using its corresponding JavaScript base player's function.
     *
     * <p>
     * The throttling parameter is present on all streaming URLs of HTML5 clients.
     * </p>
     *
     * <p>
     * If it is not given or deobfuscated, speeds will be throttled to a very slow speed (around 50
     * KB/s) and some streaming URLs could even lead to invalid HTTP responses such a 403 one.
     * </p>
     *
     * <p>
     * As throttling parameters can be common between multiple streaming URLs of the same player
     * response, deobfuscated parameters are cached with their obfuscated variant, in order to
     * improve performance with multiple calls of this method having the same obfuscated throttling
     * parameter.
     * </p>
     *
     * <p>
     * The cache's size can be get using {@link #getThrottlingParametersCacheSize()} and the cache
     * can be cleared using {@link #clearThrottlingParametersCache()} or {@link #clearAllCaches()}.
     * </p>
     *
     * @param videoId      the video ID used to get the JavaScript base player file (an empty one
     *                     can be passed, even it is not recommended in order to spoof better
     *                     official YouTube clients)
     * @param streamingUrl a streaming URL
     * @return the original streaming URL if it has no throttling parameter or a URL with a
     * deobfuscated throttling parameter
     * @throws ParsingException if the extraction of the base JavaScript player file or the
     * throttling parameter deobfuscation function failed
     */
    @Nonnull
    public static synchronized String getUrlWithThrottlingParameterDeobfuscated(
            @Nonnull final String videoId,
            @Nonnull final String streamingUrl) throws ParsingException {
        final String obfuscatedThrottlingParameter =
                YoutubeThrottlingParameterUtils.getThrottlingParameterFromStreamingUrl(
                        streamingUrl);
        // If the throttling parameter is not present, return the original streaming URL
        if (obfuscatedThrottlingParameter == null) {
            return streamingUrl;
        }

        // Do not use the containsKey method of the Map interface in order to avoid a double
        // element search, and so to improve performance
        final String cacheResult = CACHED_THROTTLING_PARAMETERS.get(
                obfuscatedThrottlingParameter);
        if (cacheResult != null) {
            // If the throttling parameter function has been already ran on the throttling parameter
            // of the current streaming URL, replace directly the obfuscated throttling parameter
            // with the cached result in the streaming URL
            return streamingUrl.replace(obfuscatedThrottlingParameter, cacheResult);
        }

        // If the throttling parameter deobfuscation function has been not extracted on a previous
        // call, this means that we will fail to extract it on next calls too if the player code
        // has not been changed.
        // Throw again the corresponding stored exception in this case to improve performance
        if (throttlingDeobfFuncExtractionEx != null) {
            throw throttlingDeobfFuncExtractionEx;
        }

        if (cachedThrottlingDeobfuscationFunction == null) {
            resolvePlayerUrlIfNeeded(videoId);
            cachedThrottlingDeobfuscationFunction = readStoredFunction(KEY_THROTTLING_FUNCTION);
        }

        if (cachedThrottlingDeobfuscationFunction == null) {
            final String playerCode = playerCode(videoId);
            final String function;
            try {
                function = YoutubeThrottlingParameterUtils.getDeobfuscationCode(playerCode);
            } catch (final ParsingException e) {
                // Store the exception for future calls of this method in order to improve
                // performance
                throttlingDeobfFuncExtractionEx = e;
                throw e;
            } catch (final Exception e) {
                throttlingDeobfFuncExtractionEx = new ParsingException(
                        "Could not get throttling parameter deobfuscation JavaScript function", e);
                throw e;
            }
            cachedThrottlingDeobfuscationFunction = function;
            writeStored(KEY_THROTTLING_FUNCTION, function);
        }

        try {
            final String deobfuscatedThrottlingParameter = JavaScript.run(
                    cachedThrottlingDeobfuscationFunction,
                    YoutubeThrottlingParameterUtils.DEOBFUSCATION_FUNCTION_NAME,
                    obfuscatedThrottlingParameter);

            if (isNullOrEmpty(deobfuscatedThrottlingParameter)) {
                throw new IllegalStateException("Extracted n-parameter is empty");
            }

            CACHED_THROTTLING_PARAMETERS.put(
                    obfuscatedThrottlingParameter, deobfuscatedThrottlingParameter);

            return streamingUrl.replace(
                    obfuscatedThrottlingParameter, deobfuscatedThrottlingParameter);
        } catch (final Exception e) {
            // This shouldn't happen as the function validity is checked when it is extracted
            throw new ParsingException(
                    "Could not run throttling parameter deobfuscation JavaScript function", e);
        }
    }

    /**
     * Get the current cache size of throttling parameters.
     *
     * @return the current cache size of throttling parameters
     */
    public static synchronized int getThrottlingParametersCacheSize() {
        return CACHED_THROTTLING_PARAMETERS.size();
    }

    /**
     * Clear all caches.
     *
     * <p>
     * This method will clear all cached JavaScript code and throttling parameters. Values kept
     * between launches stay where they are, but they're only used again for the same player URL.
     * </p>
     *
     * <p>
     * The next time {@link #getSignatureTimestamp(String)},
     * {@link #deobfuscateSignature(String, String)} or
     * {@link #getUrlWithThrottlingParameterDeobfuscated(String, String)} is called, the player URL
     * will be looked up again and the corresponding extraction methods will be ran.
     * </p>
     */
    public static synchronized void clearAllCaches() {
        cachedPlayerUrl = null;
        cachedJavaScriptPlayerCode = null;
        cachedSignatureDeobfuscationFunction = null;
        cachedThrottlingDeobfuscationFunction = null;
        cachedSignatureTimestamp = null;
        clearThrottlingParametersCache();

        // Clear cached extraction exceptions, if applicable
        throttlingDeobfFuncExtractionEx = null;
        sigDeobFuncExtractionEx = null;
        sigTimestampExtractionEx = null;
    }

    /**
     * Gets the stored signature and throttling functions ready ahead of the first video: looks up
     * the player URL (one small request) and, if functions were kept for that player, loads them
     * into the JavaScript engine. Never downloads the player file itself; if nothing is stored,
     * the first video extracts as before. Meant for a background thread at startup.
     */
    public static synchronized void warmUp() {
        try {
            resolvePlayerUrlIfNeeded("");
        } catch (final ParsingException | RuntimeException offline) {
            return;
        }
        if (cachedSignatureDeobfuscationFunction == null && sigDeobFuncExtractionEx == null) {
            cachedSignatureDeobfuscationFunction = readStoredFunction(KEY_SIGNATURE_FUNCTION);
        }
        if (cachedThrottlingDeobfuscationFunction == null
                && throttlingDeobfFuncExtractionEx == null) {
            cachedThrottlingDeobfuscationFunction = readStoredFunction(KEY_THROTTLING_FUNCTION);
        }
    }

    /**
     * Clear all cached throttling parameters.
     *
     * <p>
     * The throttling parameter deobfuscation function will be ran again on these parameters if
     * streaming URLs containing them are passed in the future.
     * </p>
     *
     * <p>
     * This method doesn't clear the cached throttling parameter deobfuscation function, this can
     * be done using {@link #clearAllCaches()}.
     * </p>
     */
    public static synchronized void clearThrottlingParametersCache() {
        CACHED_THROTTLING_PARAMETERS.clear();
    }

    /**
     * Looks up the current player file's URL once per process. It's a small request, and it's what
     * tells us whether the values kept from an earlier launch still belong to the current player.
     */
    private static void resolvePlayerUrlIfNeeded(@Nonnull final String videoId)
            throws ParsingException {
        if (cachedPlayerUrl == null) {
            cachedPlayerUrl = YoutubeJavaScriptExtractor.extractJavaScriptPlayerUrl(videoId);
        }
    }

    /**
     * The player file itself, downloaded the first time something has to be extracted from it.
     */
    @Nonnull
    private static String playerCode(@Nonnull final String videoId) throws ParsingException {
        resolvePlayerUrlIfNeeded(videoId);
        if (cachedJavaScriptPlayerCode == null) {
            final YoutubeJavaScriptExtractor.PlayerCode player =
                    YoutubeJavaScriptExtractor.extractJavaScriptPlayerCode(
                            Objects.requireNonNull(cachedPlayerUrl), videoId);
            // It may have come from the embed page's URL instead; what's stored has to match it
            cachedPlayerUrl = player.url;
            cachedJavaScriptPlayerCode = player.code;
        }
        return cachedJavaScriptPlayerCode;
    }

    /**
     * A stored value, if one was saved for the current player URL.
     */
    @Nullable
    private static String readStored(@Nonnull final String key) {
        final PersistentCache cache = persistentCache;
        final String playerUrl = cachedPlayerUrl;
        if (cache == null || playerUrl == null) {
            return null;
        }
        final String raw;
        try {
            raw = cache.read(key);
        } catch (final RuntimeException ignored) {
            return null;
        }
        final String prefix = playerUrl + URL_SEPARATOR;
        if (raw == null || !raw.startsWith(prefix) || raw.length() == prefix.length()) {
            return null;
        }
        return raw.substring(prefix.length());
    }

    /**
     * A stored function, if one was saved for the current player URL and it still compiles.
     * With QuickJS it's also evaluated right away, so running it later only has to call it.
     */
    @Nullable
    private static String readStoredFunction(@Nonnull final String key) {
        final String function = readStored(key);
        if (function == null) {
            return null;
        }
        try {
            JavaScript.prepareOrThrow(function);
            return function;
        } catch (final Exception ignored) {
            // Corrupt on disk; extracting it again from the player file is the safe bet
            return null;
        }
    }

    private static void writeStored(@Nonnull final String key, @Nonnull final String value) {
        final PersistentCache cache = persistentCache;
        final String playerUrl = cachedPlayerUrl;
        if (cache == null || playerUrl == null) {
            return;
        }
        try {
            cache.write(key, playerUrl + URL_SEPARATOR + value);
        } catch (final RuntimeException ignored) {
            // Keeping it for the next launch is a bonus; it's still cached in memory
        }
    }
}
