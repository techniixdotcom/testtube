package com.testtube.app;

import android.content.Context;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.GlideBuilder;
import com.bumptech.glide.Registry;
import com.bumptech.glide.annotation.GlideModule;
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader;
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.module.AppGlideModule;

import java.io.InputStream;

import okhttp3.OkHttpClient;

/**
 * Glide's setup: its own disk cache ("Clear cache" empties it) and the app's connections. The
 * HTTP cache is left out for pictures, or every thumbnail would be stored twice.
 */
@GlideModule
public final class TestTubeGlideModule extends AppGlideModule {
	/** Folder of the picture cache inside the app's cache directory. */
	public static final String CACHE_DIR = "images";
	private static final long CACHE_BYTES = 100L * 1024 * 1024;

	@Override
	public void applyOptions(@NonNull Context context, @NonNull GlideBuilder builder) {
		builder.setDiskCache(new InternalCacheDiskCacheFactory(context, CACHE_DIR, CACHE_BYTES));
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
