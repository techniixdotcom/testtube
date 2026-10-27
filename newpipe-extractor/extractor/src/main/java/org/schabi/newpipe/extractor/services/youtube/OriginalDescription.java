package org.schabi.newpipe.extractor.services.youtube;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;

/**
 * TestTube: a video description as the creator wrote it. The player response has it only as
 * plain text (the "next" response has links, but in the content language, translated by the
 * creator or by YouTube), so the links are put back here the way YouTube shows them: web
 * addresses, timestamps, hashtags and channel handles.
 */
public final class OriginalDescription {
    private static final Pattern TOKENS = Pattern.compile(
            // web address, without punctuation that ends the sentence
            "(https?://[^\\s<>\"]+?)(?=[.,!?;:)\\]]*(?:\\s|$))"
                    // timestamp: 1:23, 01:23 or 1:02:03
                    + "|(?<![\\w:./])(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})(?![\\w:])"
                    // hashtag
                    + "|(?<![\\w&#])#([\\p{L}\\p{N}_]+)"
                    // channel handle
                    + "|(?<![\\w@./])@([A-Za-z0-9._-]{3,30})");
    private static final Pattern TIMESTAMP = Pattern.compile(
            "(?<![\\w:./])(?:(\\d{1,2}):)?(\\d{1,2}):(\\d{2})(?![\\w:])");
    /** Separators around a chapter title: "0:00 - Intro", "Intro (1:23)", "1:23 | Intro". */
    private static final Pattern EDGES = Pattern.compile(
            "^[\\s\\-\u2013\u2014:|\u2022\u00b7()\\[\\]]+|[\\s\\-\u2013\u2014:|\u2022\u00b7()\\[\\]]+$");

    private OriginalDescription() {
    }

    /** The text as HTML with its links; line breaks kept. */
    @Nonnull
    public static String toHtml(@Nonnull final String text, @Nonnull final String videoUrl) {
        final StringBuilder html = new StringBuilder(text.length() + 64);
        final Matcher matcher = TOKENS.matcher(text);
        int last = 0;
        while (matcher.find()) {
            html.append(escape(text.substring(last, matcher.start())));
            final String token = matcher.group();
            final String href;
            if (matcher.group(1) != null) {
                href = token;
            } else if (matcher.group(4) != null) {
                href = videoUrl + "&t=" + seconds(matcher.group(2), matcher.group(3),
                        matcher.group(4)) + "s";
            } else if (matcher.group(5) != null) {
                href = "https://www.youtube.com/hashtag/" + encode(matcher.group(5));
            } else {
                href = "https://www.youtube.com/@" + matcher.group(6);
            }
            html.append("<a href=\"").append(escape(href)).append("\">")
                    .append(escape(token)).append("</a>");
            last = matcher.end();
        }
        html.append(escape(text.substring(last)));
        return html.toString().replace("\n", "<br>");
    }

    /**
     * Chapter titles written in the description, by start time in seconds: every line with a
     * timestamp, minus the timestamp and the separators around it.
     */
    @Nonnull
    public static Map<Integer, String> chapters(@Nonnull final String text) {
        final Map<Integer, String> chapters = new LinkedHashMap<>();
        for (final String line : text.split("\n")) {
            final Matcher matcher = TIMESTAMP.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            final String title = EDGES.matcher(
                    line.substring(0, matcher.start()) + " " + line.substring(matcher.end()))
                    .replaceAll("").trim();
            if (!title.isEmpty()) {
                chapters.putIfAbsent(seconds(matcher.group(1), matcher.group(2),
                        matcher.group(3)), title);
            }
        }
        return chapters;
    }

    private static int seconds(final String hours, final String minutes, final String secs) {
        final int h = hours == null ? 0 : Integer.parseInt(hours);
        return h * 3600 + Integer.parseInt(minutes) * 60 + Integer.parseInt(secs);
    }

    @Nonnull
    private static String escape(@Nonnull final String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    @Nonnull
    private static String encode(@Nonnull final String text) {
        try {
            return URLEncoder.encode(text, StandardCharsets.UTF_8.name());
        } catch (final UnsupportedEncodingException e) {
            return text;
        }
    }
}
