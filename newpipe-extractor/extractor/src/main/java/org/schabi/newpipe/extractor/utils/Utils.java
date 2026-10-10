package org.schabi.newpipe.extractor.utils;

import org.schabi.newpipe.extractor.exceptions.ParsingException;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public final class Utils {
    public static final String HTTP = "http://";
    public static final String HTTPS = "https://";
    private static final Pattern M_PATTERN = Pattern.compile("(https?)?://m\\.");
    private static final Pattern WWW_PATTERN = Pattern.compile("(https?)?://www\\.");

    private Utils() {
        // no instance
    }

    /**
     * Encodes a string to URL format using the UTF-8 character set.
     *
     * @param string The string to be encoded.
     * @return The encoded URL.
     */
    public static String encodeUrlUtf8(final String string) {
        return URLEncoder.encode(string, StandardCharsets.UTF_8);
    }

    /**
     * Decodes a URL using the UTF-8 character set.
     * @param url The URL to be decoded.
     * @return The decoded URL.
     */
    public static String decodeUrlUtf8(final String url) {
        return URLDecoder.decode(url, StandardCharsets.UTF_8);
    }

    /**
     * Remove all non-digit characters from a string.
     *
     * <p>
     * Examples:
     * </p>
     *
     * <ul>
     *     <li>1 234 567 views -&gt; 1234567</li>
     *     <li>$31,133.124 -&gt; 31133124</li>
     * </ul>
     *
     * @param toRemove string to remove non-digit chars
     * @return a string that contains only digits
     */
    @Nonnull
    public static String removeNonDigitCharacters(@Nonnull final String toRemove) {
        return toRemove.replaceAll("\\D+", "");
    }

    private static final Map<String, Long> ENGLISH_NUMBER_WORDS = Map.of(
            "k", 1_000L, "m", 1_000_000L, "b", 1_000_000_000L, "t", 1_000_000_000_000L);
    /** Decimal and grouping marks that can stand between the digits of a count. */
    private static final String NUMBER_MARKS = ".,'’    ٫٬";

    /**
     * Lower-case words that stand for a power of ten in a short count, like "K" in "1.2K", "mil"
     * in "345 mil" or "万" in "1.2万". English unless {@link #setNumberWords(Map)} was called.
     */
    private static volatile Map<String, Long> numberWords = ENGLISH_NUMBER_WORDS;

    /**
     * Sets the words the content language uses in short counts, e.g. "mil" = 1000 in Spanish or
     * "B" = 1000 in Turkish. They take precedence over the English ones, which stay known.
     */
    public static void setNumberWords(@Nonnull final Map<String, Long> words) {
        final Map<String, Long> merged = new HashMap<>(ENGLISH_NUMBER_WORDS);
        for (final Map.Entry<String, Long> word : words.entrySet()) {
            final String key = normalizeNumberText(word.getKey()).replaceAll("\\.+$", "");
            if (!key.isEmpty() && word.getValue() > 1) {
                merged.put(key, word.getValue());
            }
        }
        numberWords = Map.copyOf(merged);
    }

    /**
     * Convert a short or full count, written the way YouTube writes it in the content language,
     * to a long.
     *
     * <p>
     * Examples:
     * </p>
     *
     * <ul>
     *     <li>123 -&gt; 123</li>
     *     <li>1.23K, 1,23 mil -&gt; 1230</li>
     *     <li>1.23M, 1,23 M de visualizaciones, 123万 -&gt; 1230000</li>
     *     <li>1,234 views, 1.234 Aufrufe, 1 234 vues -&gt; 1234</li>
     * </ul>
     *
     * @param numberWord string to be converted to a long
     * @return a long
     */
    public static long mixedNumberWordToLong(final String numberWord)
            throws NumberFormatException, ParsingException {
        final int length = numberWord.length();
        int start = 0;
        while (start < length && !Character.isDigit(numberWord.charAt(start))) {
            start++;
        }
        if (start == length) {
            throw new ParsingException("Could not find a number in \"" + numberWord + "\"");
        }
        // The digits (in any script), with each mark between them written as '.'
        final StringBuilder number = new StringBuilder();
        int end = start;
        for (; end < length; end++) {
            final char c = numberWord.charAt(end);
            if (Character.isDigit(c)) {
                number.append(Character.forDigit(Character.digit(c, 10), 10));
            } else if (NUMBER_MARKS.indexOf(c) >= 0 && end + 1 < length
                    && Character.isDigit(numberWord.charAt(end + 1))) {
                number.append('.');
            } else {
                break;
            }
        }
        final long multiplier = numberWordValue(numberWord.substring(end),
                numberWord.substring(0, start));
        final String digits = number.toString();
        final int mark = digits.lastIndexOf('.');
        // Short counts have one or two decimals; marks before three digits group thousands
        if (multiplier == 1 || mark < 0 || digits.length() - mark - 1 == 3) {
            return Long.parseLong(digits.replace(".", "")) * multiplier;
        }
        final double value = Double.parseDouble(
                digits.substring(0, mark).replace(".", "") + "." + digits.substring(mark + 1));
        return Math.round(value * multiplier);
    }

    /**
     * The value of the number word right after the count ("M de visualizaciones") or, in
     * languages that write it first, right before it ("elfu 1.2"); 1 when there is none.
     */
    private static long numberWordValue(@Nonnull final String after,
                                        @Nonnull final String before) {
        final String next = normalizeNumberText(after);
        final String previous = normalizeNumberText(before);
        long value = 1;
        int matched = 0;
        for (final Map.Entry<String, Long> word : numberWords.entrySet()) {
            final String key = word.getKey();
            if (key.length() > matched
                    && (startsWithWord(next, key) || endsWithWord(previous, key))) {
                value = word.getValue();
                matched = key.length();
            }
        }
        return value;
    }

    private static boolean startsWithWord(@Nonnull final String text, @Nonnull final String word) {
        // "m" must not match "mil"; "万" is written right before the next word ("1.2万次")
        return text.startsWith(word) && (text.length() == word.length()
                || !Character.isLetter(text.charAt(word.length()))
                || isCjk(word.charAt(word.length() - 1)));
    }

    private static boolean endsWithWord(@Nonnull final String text, @Nonnull final String word) {
        final int before = text.length() - word.length() - 1;
        return text.endsWith(word) && (before < 0 || !Character.isLetter(text.charAt(before))
                || isCjk(word.charAt(0)));
    }

    private static boolean isCjk(final char c) {
        final Character.UnicodeScript script = Character.UnicodeScript.of(c);
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HANGUL
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA;
    }

    /** Lower case, every kind of space as ' ', direction marks removed, trimmed. */
    @Nonnull
    private static String normalizeNumberText(@Nonnull final String text) {
        return text.replaceAll("[\\u00A0\\u202F\\u2009]", " ")
                .replaceAll("[\\u200E\\u200F\\u061C]", "")
                .toLowerCase(Locale.ROOT)
                .trim();
    }

    /**
     * Check if the url matches the pattern.
     *
     * @param pattern the pattern that will be used to check the url
     * @param url     the url to be tested
     */
    public static void checkUrl(final String pattern, final String url) throws ParsingException {
        checkUrl(Pattern.compile(pattern), url);
    }

    /**
     * Check if the url matches the pattern.
     *
     * @param pattern the pattern that will be used to check the url
     * @param url     the url to be tested
     */
    public static void checkUrl(final Pattern pattern, final String url) throws ParsingException {
        if (isNullOrEmpty(url)) {
            throw new IllegalArgumentException("Url can't be null or empty");
        }

        if (!Parser.isMatch(pattern, url.toLowerCase())) {
            throw new ParsingException("Url doesn't match the pattern");
        }
    }

    public static String replaceHttpWithHttps(final String url) {
        if (url == null) {
            return null;
        }

        if (url.startsWith(HTTP)) {
            return HTTPS + url.substring(HTTP.length());
        }
        return url;
    }

    /**
     * Get the value of a URL-query by name.
     *
     * <p>
     * If an url-query is give multiple times, only the value of the first query is returned.
     * </p>
     *
     * @param url           the url to be used
     * @param parameterName the pattern that will be used to check the url
     * @return a string that contains the value of the query parameter or {@code null} if nothing
     * was found
     */
    @Nullable
    public static String getQueryValue(@Nonnull final URL url,
                                       final String parameterName) {
        final String urlQuery = url.getQuery();

        if (urlQuery != null) {
            for (final String param : urlQuery.split("&")) {
                final String[] params = param.split("=", 2);
                final String query = decodeUrlUtf8(params[0]);

                if (query.equals(parameterName)) {
                    return decodeUrlUtf8(params[1]);
                }
            }
        }

        return null;
    }

    /**
     * Convert a string to a {@link URL URL object}.
     *
     * <p>
     * Defaults to HTTP if no protocol is given.
     * </p>
     *
     * @param url the string to be converted to a URL-Object
     * @return a {@link URL URL object} containing the url
     */
    @Nonnull
    public static URL stringToURL(final String url) throws MalformedURLException {
        try {
            return new URL(url);
        } catch (final MalformedURLException e) {
            // If no protocol is given try prepending "https://"
            if (e.getMessage().equals("no protocol: " + url)) {
                return new URL(HTTPS + url);
            }

            throw e;
        }
    }

    public static boolean isHTTP(@Nonnull final URL url) {
        // Make sure it's HTTP or HTTPS
        final String protocol = url.getProtocol();
        if (!protocol.equals("http") && !protocol.equals("https")) {
            return false;
        }

        final boolean usesDefaultPort = url.getPort() == url.getDefaultPort();
        final boolean setsNoPort = url.getPort() == -1;

        return setsNoPort || usesDefaultPort;
    }

    public static String removeMAndWWWFromUrl(final String url) {
        if (M_PATTERN.matcher(url).find()) {
            return url.replace("m.", "");
        }
        if (WWW_PATTERN.matcher(url).find()) {
            return url.replace("www.", "");
        }
        return url;
    }

    @Nonnull
    public static String removeUTF8BOM(@Nonnull final String s) {
        String result = s;
        if (result.startsWith("\uFEFF")) {
            result = result.substring(1);
        }
        if (result.endsWith("\uFEFF")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    @Nonnull
    public static String getBaseUrl(final String url) throws ParsingException {
        try {
            final URL uri = stringToURL(url);
            return uri.getProtocol() + "://" + uri.getAuthority();
        } catch (final MalformedURLException e) {
            final String message = e.getMessage();
            if (message.startsWith("unknown protocol: ")) {
                // Return just the protocol (e.g. vnd.youtube)
                return message.substring("unknown protocol: ".length());
            }

            throw new ParsingException("Malformed url: " + url, e);
        }
    }

    /**
     * If the provided url is a Google search redirect, then the actual url is extracted from the
     * {@code url=} query value and returned, otherwise the original url is returned.
     *
     * @param url the url which can possibly be a Google search redirect
     * @return an url with no Google search redirects
     */
    public static String followGoogleRedirectIfNeeded(final String url) {
        // If the url is a redirect from a Google search, extract the actual URL
        try {
            final URL decoded = stringToURL(url);
            if (decoded.getHost().contains("google") && decoded.getPath().equals("/url")) {
                return decodeUrlUtf8(Parser.matchGroup1("&url=([^&]+)(?:&|$)", url));
            }
        } catch (final Exception ignored) {
        }

        // URL is not a Google search redirect
        return url;
    }

    public static boolean isNullOrEmpty(final String str) {
        return str == null || str.isEmpty();
    }

    /**
     * Checks if a collection is null or empty.
     *
     * <p>
     * This method can be also used for {@link com.grack.nanojson.JsonArray JsonArray}s.
     * </p>
     *
     * @param collection the collection on which check if it's null or empty
     * @return whether the collection is null or empty
     */
    public static boolean isNullOrEmpty(final Collection<?> collection) {
        return collection == null || collection.isEmpty();
    }

    /**
     * Checks if a {@link Map map} is null or empty.
     *
     * <p>
     * This method can be also used for {@link com.grack.nanojson.JsonObject JsonObject}s.
     * </p>
     *
     * @param map the {@link Map map} on which check if it's null or empty
     * @return whether the {@link Map map} is null or empty
     */
    public static <K, V> boolean isNullOrEmpty(final Map<K, V> map) {
        return map == null || map.isEmpty();
    }

    public static boolean isBlank(final String string) {
        return string == null || string.isBlank();
    }

    @Nonnull
    public static String join(
            final String delimiter,
            final String mapJoin,
            @Nonnull final Map<? extends CharSequence, ? extends CharSequence> elements) {
        return elements.entrySet().stream()
                .map(entry -> entry.getKey() + mapJoin + entry.getValue())
                .collect(Collectors.joining(delimiter));
    }

    /**
     * Concatenate all non-null, non-empty and strings which are not equal to <code>"null"</code>.
     */
    @Nonnull
    public static String nonEmptyAndNullJoin(final CharSequence delimiter,
                                             final String... elements) {
        return Arrays.stream(elements)
                .filter(s -> !isNullOrEmpty(s) && !s.equals("null"))
                .collect(Collectors.joining(delimiter));
    }

    /**
     * Find the result of an array of string regular expressions inside an input on the first
     * group ({@code 0}).
     *
     * @param input   the input on which using the regular expressions
     * @param regexes the string array of regular expressions
     * @return the result
     * @throws Parser.RegexException if none of the patterns match the input
     */
    @Nonnull
    public static String getStringResultFromRegexArray(@Nonnull final String input,
                                                       @Nonnull final String[] regexes)
            throws Parser.RegexException {
        return getStringResultFromRegexArray(input, regexes, 0);
    }

    /**
     * Find the result of an array of {@link Pattern}s inside an input on the first group
     * ({@code 0}).
     *
     * @param input   the input on which using the regular expressions
     * @param regexes the {@link Pattern} array
     * @return the result
     * @throws Parser.RegexException if none of the patterns match the input
     */
    @Nonnull
    public static String getStringResultFromRegexArray(@Nonnull final String input,
                                                       @Nonnull final Pattern[] regexes)
            throws Parser.RegexException {
        return getStringResultFromRegexArray(input, regexes, 0);
    }

    /**
     * Find the result of an array of string regular expressions inside an input on a specific
     * group.
     *
     * @param input   the input on which using the regular expressions
     * @param regexes the string array of regular expressions
     * @param group   the group to match
     * @return the result
     * @throws Parser.RegexException if none of the patterns match the input, or at least in the
     * specified group
     */
    @Nonnull
    public static String getStringResultFromRegexArray(@Nonnull final String input,
                                                       @Nonnull final String[] regexes,
                                                       final int group)
            throws Parser.RegexException {
        return getStringResultFromRegexArray(input,
                Arrays.stream(regexes)
                        .filter(Objects::nonNull)
                        .map(Pattern::compile)
                        .toArray(Pattern[]::new),
                group);
    }

    /**
     * Find the result of an array of {@link Pattern}s inside an input on a specific
     * group.
     *
     * @param input   the input on which using the regular expressions
     * @param regexes the {@link Pattern} array
     * @param group   the group to match
     * @return the result
     * @throws Parser.RegexException if none of the patterns match the input, or at least in the
     * specified group
     */
    @Nonnull
    public static String getStringResultFromRegexArray(@Nonnull final String input,
                                                       @Nonnull final Pattern[] regexes,
                                                       final int group)
            throws Parser.RegexException {
        for (final Pattern regex : regexes) {
            try {
                final String result = Parser.matchGroup(regex, input, group);
                if (result != null) {
                    return result;
                }

                // Continue if the result is null
            } catch (final Parser.RegexException ignored) {
            }
        }

        throw new Parser.RegexException("No regex matched the input on group " + group);
    }
}
