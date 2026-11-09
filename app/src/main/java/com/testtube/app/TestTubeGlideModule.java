package com.testtube.app;

import android.app.ActivityManager;
import android.content.Context;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.module.AppGlideModule;
import com.bumptech.glide.request.RequestOptions;

import java.io.InputStream;

import okhttp3.OkHttpClient;

/**
 * Glide setup: own disk cache (Clear cache empties it) and our client. No HTTP cache for
 * pictures or every thumbnail gets stored twice.
 * <p>
 * Low-RAM phones decode opaque pictures as RGB_565, half the memory. Elsewhere that only costs
 * quality: from Android 9 Glide uses hardware bitmaps first anyway.
 */
@GlideModule
public final class TestTubeGlideModule extends AppGlideModule {
	/** Picture cache folder. */
	public static final String CACHE_DIR = "images";
	private static final long CACHE_BYTES = 100L * 1024 * 1024;

	@Override
	public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
		builder.setDiskCache(new InternalCacheDiskCacheFactory(context, CACHE_DIR, CACHE_BYTES));
		ActivityManager activities = context.getSystemService(ActivityManager.class);
		if (activities != null && activities.isLowRamDevice()) {
			builder.setDefaultRequestOptions(new RequestOptions().format(DecodeFormat.PREFER_RGB_565));
		}
	}

	@Override
	public void registerComponents(@NonNull Context context, @NonNull Glide glide, @NonNull Registry registry) {
		OkHttpClient images = AppGraph.of(context).okHttpClient().newBuilder().cache(null).build();
		registry.replace(GlideUrl.class, InputStream.class, new OkHttpUrlLoader.Factory(images));
	}

	@Override
	public boolean isManifestParsingEnabled() {
		return false;
	}
}
