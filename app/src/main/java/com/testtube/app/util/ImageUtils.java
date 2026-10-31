package com.testtube.app.util;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.FutureTarget;
import com.testtube.app.R;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Every picture in the app goes through here. YouTube thumbnails are asked for as WebP, about a
 * third smaller than the JPEGs with the same look, and fall back to the JPEG if a WebP version
 * doesn't exist. Pictures are never stretched in memory; the views do the cropping.
 */
public final class ImageUtils {
	private static final int THUMB = R.drawable.bg_thumbnail_placeholder;
	private static final int FADE_MS = 120;
	/** Plain thumbnail links; signed ones (with a query) only exist as they are. */
	private static final Pattern YTIMG = Pattern.compile(
					"^https?://i\\d?\\.ytimg\\.com/vi/([\\w-]{11})/(\\w+)\\.jpg$");

	private ImageUtils() {
	}

	/** The WebP version of a YouTube thumbnail link, or null when there is none. */
	@Nullable
	public static String webp(@Nullable String url) {
		if (url == null) return null;
		Matcher matcher = YTIMG.matcher(url);
		return matcher.matches()
						? "https://i.ytimg.com/vi_webp/" + matcher.group(1) + "/" + matcher.group(2) + ".webp"
						: null;
	}

	/** A video thumbnail with the usual placeholder. */
	public static void loadThumb(@NonNull ImageView view, @Nullable String url) {
		load(view, url, THUMB);
	}

	public static void showThumb(@NonNull ImageView view) {
		clear(view);
		view.setImageResource(THUMB);
	}

	/**
	 * Loads a picture into a view, showing {@code placeholder} until it's there and when it
	 * fails; no picture at all shows just the placeholder.
	 */
	public static void load(@NonNull ImageView view, @Nullable String url, @DrawableRes int placeholder) {
		if (url == null || url.isBlank()) {
			clear(view);
			view.setImageResource(placeholder);
			return;
		}
		RequestManager glide = glide(view);
		if (glide != null) request(glide, url, placeholder).into(view);
	}

	/** Tries {@code url} and, if it doesn't exist, {@code fallback} (vertical Shorts thumbnails). */
	public static void loadWithFallback(@NonNull ImageView view, @NonNull String url, @NonNull String fallback) {
		RequestManager glide = glide(view);
		if (glide != null) request(glide, url, 0).error(request(glide, fallback, 0)).into(view);
	}

	/** Stops a load into the view (a recycled row) and lets go of its picture. */
	public static void clear(@NonNull ImageView view) {
		RequestManager glide = glide(view);
		if (glide != null) glide.clear(view);
	}

	/**
	 * Glide for a view, or null when its screen is already gone: Glide throws for a destroyed
	 * activity, and late callbacks (a list refreshing after the screen was rebuilt for a new
	 * theme) would take the whole app down with it. Its requests ended with the screen anyway.
	 */
	@Nullable
	private static RequestManager glide(@NonNull View view) {
		Context context = view.getContext();
		while (context instanceof ContextWrapper wrapper) {
			if (context instanceof Activity activity) {
				return activity.isDestroyed() ? null : Glide.with(view);
			}
			context = wrapper.getBaseContext();
		}
		return Glide.with(view);
	}

	/** Downloads a picture ahead of time, for rows that aren't on screen yet. */
	public static void prefetch(@NonNull Context context, @Nullable String url) {
		if (url == null || url.isBlank()) return;
		request(Glide.with(context.getApplicationContext()), url, 0).priority(Priority.LOW).preload();
	}

	/**
	 * A square picture for the notification, from the cache when it's on screen already. Blocks;
	 * null when it can't be loaded.
	 */
	@WorkerThread
	@Nullable
	public static Bitmap squareBitmap(@NonNull Context context, @NonNull String url, int size) {
		RequestManager glide = Glide.with(context.getApplicationContext());
		String smaller = webp(url);
		RequestBuilder<Bitmap> jpeg = glide.asBitmap().load(url).centerCrop();
		RequestBuilder<Bitmap> request = smaller != null ? glide.asBitmap().load(smaller).centerCrop().error(jpeg) : jpeg;
		FutureTarget<Bitmap> target = request.submit(size, size);
		try {
			Bitmap bitmap = target.get();
			// Glide reuses its bitmaps once the request is cleared; the notification keeps a copy
			return bitmap.copy(bitmap.getConfig() != null ? bitmap.getConfig() : Bitmap.Config.ARGB_8888, false);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} catch (Exception e) {
			return null;
		} finally {
			glide.clear(target);
		}
	}

	/** The WebP when there is one, then the JPEG; {@code placeholder} 0 for none. */
	@NonNull
	private static RequestBuilder<Drawable> request(@NonNull RequestManager glide, @NonNull String url,
	                                                @DrawableRes int placeholder) {
		RequestBuilder<Drawable> jpeg = styled(glide.load(url), placeholder);
		String smaller = webp(url);
		return smaller == null ? jpeg : styled(glide.load(smaller), placeholder).error(jpeg);
	}

	@NonNull
	private static RequestBuilder<Drawable> styled(@NonNull RequestBuilder<Drawable> request,
	                                               @DrawableRes int placeholder) {
		RequestBuilder<Drawable> out = request.dontTransform()
						.transition(DrawableTransitionOptions.withCrossFade(FADE_MS));
		return placeholder == 0 ? out : out.placeholder(placeholder).error(placeholder);
	}
}
