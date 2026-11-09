package com.testtube.app.plus;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** A page fetched for its embedded data: final url, status, text. */
final class WebPage {
	/** Pages are a few MB tops, way bigger isn't the page we asked for. */
	private static final long MAX_BYTES = 16L * 1024L * 1024L;
	private static final Pattern META = Pattern.compile(
					"<meta\\s+[^>]*?(?:property|name)=\"([^\"]+)\"[^>]*?content=\"([^\"]*)\"[^>]*>"
									+ "|<meta\\s+[^>]*?content=\"([^\"]*)\"[^>]*?(?:property|name)=\"([^\"]+)\"[^>]*>",
					Pattern.CASE_INSENSITIVE);
	private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#\\d+|amp|lt|gt|quot|apos|nbsp);");

	@NonNull
	final HttpUrl url;
	final int code;
	@NonNull
	final String text;

	private WebPage(@NonNull HttpUrl url, int code, @NonNull String text) {
		this.url = url;
		this.code = code;
		this.text = text;
	}

	/**
	 * Client for one site's pages: no shared HTTP cache (signed, short-lived links inside) and
	 * cookies only for its own requests, like a browser tab.
	 */
	@NonNull
	static OkHttpClient client(@NonNull OkHttpClient shared) {
		return shared.newBuilder()
						.cache(null)
						.cookieJar(new MemoryCookies())
						.followRedirects(true)
						.followSslRedirects(false)
						.callTimeout(45L, TimeUnit.SECONDS)
						.build();
	}

	@WorkerThread
	@NonNull
	static WebPage get(@NonNull OkHttpClient client, @NonNull String url, @NonNull String userAgent)
					throws IOException {
		Request request = new Request.Builder().url(url)
						.header("User-Agent", userAgent)
						.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
						.header("Accept-Language", acceptLanguage())
						.header("Sec-Fetch-Dest", "document")
						.header("Sec-Fetch-Mode", "navigate")
						.header("Sec-Fetch-Site", "none")
						.header("Sec-Fetch-User", "?1")
						.header("Upgrade-Insecure-Requests", "1")
						.build();
		return execute(client, request);
	}

	@WorkerThread
	@NonNull
	static WebPage postEmpty(@NonNull OkHttpClient client, @NonNull String url, @NonNull String userAgent)
					throws IOException {
		Request request = new Request.Builder().url(url)
						.header("User-Agent", userAgent)
						.header("Accept-Language", acceptLanguage())
						.post(RequestBody.create(new byte[0]))
						.build();
		return execute(client, request);
	}

	@NonNull
	private static WebPage execute(@NonNull OkHttpClient client, @NonNull Request request) throws IOException {
		if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
		try (Response response = client.newCall(request).execute()) {
			ResponseBody body = response.body();
			String text = body == null ? "" : read(body);
			return new WebPage(response.request().url(), response.code(), text);
		}
	}

	@NonNull
	private static String read(@NonNull ResponseBody body) throws IOException {
		long length = body.contentLength();
		if (length > MAX_BYTES) throw new IOException("page too large");
		MediaType type = body.contentType();
		Charset charset = type != null ? type.charset(StandardCharsets.UTF_8) : StandardCharsets.UTF_8;
		if (charset == null) charset = StandardCharsets.UTF_8;
		try (InputStream in = body.byteStream()) {
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(
							length > 0 ? (int) length : 64 * 1024);
			byte[] buffer = new byte[32 * 1024];
			long total = 0L;
			int read;
			while ((read = in.read(buffer)) != -1) {
				total += read;
				if (total > MAX_BYTES) throw new IOException("page too large");
				out.write(buffer, 0, read);
			}
			return out.toString(charset.name());
		}
	}

	/** Device language first, then English, so titles come in a language people read. */
	@NonNull
	private static String acceptLanguage() {
		Locale locale = Locale.getDefault();
		String tag = locale.toLanguageTag();
		if (tag.isEmpty() || "und".equals(tag) || locale.getLanguage().equals("en")) return "en-US,en;q=0.9";
		return tag + "," + locale.getLanguage() + ";q=0.9,en;q=0.8";
	}

	/** Content of a {@code <meta property=…>} or {@code <meta name=…>} tag, decoded. */
	@Nullable
	String meta(@NonNull String name) {
		Matcher matcher = META.matcher(text);
		while (matcher.find()) {
			String key = matcher.group(1) != null ? matcher.group(1) : matcher.group(4);
			String value = matcher.group(1) != null ? matcher.group(2) : matcher.group(3);
			if (name.equalsIgnoreCase(key) && value != null) {
				String decoded = unescapeHtml(value).trim();
				return decoded.isEmpty() ? null : decoded;
			}
		}
		return null;
	}

	/** Decodes the few HTML entities metadata uses. */
	@NonNull
	static String unescapeHtml(@NonNull String value) {
		if (value.indexOf('&') < 0) return value;
		Matcher matcher = ENTITY.matcher(value);
		StringBuilder out = new StringBuilder(value.length());
		int last = 0;
		while (matcher.find()) {
			out.append(value, last, matcher.start());
			String entity = matcher.group(1);
			String replacement;
			try {
				replacement = switch (entity) {
					case "amp" -> "&";
					case "lt" -> "<";
					case "gt" -> ">";
					case "quot" -> "\"";
					case "apos" -> "'";
					case "nbsp" -> " ";
					default -> new String(Character.toChars(entity.startsWith("#x")
									? Integer.parseInt(entity.substring(2), 16)
									: Integer.parseInt(entity.substring(1))));
				};
			} catch (IllegalArgumentException e) {
				replacement = matcher.group();
			}
			out.append(replacement);
			last = matcher.end();
		}
		out.append(value, last, value.length());
		return out.toString();
	}

	/** Decodes a JSON string's inside ("https:\/\/…&…"), or null if invalid. */
	@Nullable
	static String unescapeJson(@NonNull String raw) {
		try {
			JsonElement element = JsonParser.parseString("\"" + raw + "\"");
			return element.isJsonPrimitive() ? element.getAsString() : null;
		} catch (JsonParseException | IllegalStateException e) {
			return null;
		}
	}

	/** In-memory cookies for one client: what a site sets during one lookup. */
	private static final class MemoryCookies implements CookieJar {
		private final List<Cookie> cookies = new ArrayList<>();

		@Override
		public synchronized void saveFromResponse(@NonNull HttpUrl url, @NonNull List<Cookie> received) {
			for (Cookie cookie : received) {
				cookies.removeIf(old -> old.name().equals(cookie.name()) && old.domain().equals(cookie.domain())
								&& old.path().equals(cookie.path()));
				if (cookie.expiresAt() > System.currentTimeMillis()) cookies.add(cookie);
			}
		}

		@NonNull
		@Override
		public synchronized List<Cookie> loadForRequest(@NonNull HttpUrl url) {
			List<Cookie> matching = new ArrayList<>();
			long now = System.currentTimeMillis();
			cookies.removeIf(cookie -> cookie.expiresAt() <= now);
			for (Cookie cookie : cookies) {
				if (cookie.matches(url)) matching.add(cookie);
			}
			return matching;
		}
	}
}
