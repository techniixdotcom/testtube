package com.testtube.app.player.engine;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.FileDataSource;
import androidx.media3.datasource.cache.CacheDataSink;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.dash.DefaultDashChunkSource;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;

import com.testtube.app.Constant;
import com.testtube.app.player.engine.datasource.YoutubeHttpDataSource;

import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeOtfDashManifestCreator;
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubePostLiveStreamDvrDashManifestCreator;
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator;

/**
 * Component that handles app logic.
 */
@UnstableApi
final class PlayerDataSource {
	private static final int MANIFEST_CACHE_SIZE = 500;
	private static final int PROGRESSIVE_LOAD_INTERVAL_BYTES = 128 * 1024;

	@Nullable
	private final SimpleCache cache;
	@NonNull
	private final DefaultHttpDataSource.Factory liveHttp;
	@NonNull
	private final YoutubeHttpDataSource.Factory ytDashHttp;
	@NonNull
	private final YoutubeHttpDataSource.Factory ytProgressiveHttp;

	PlayerDataSource(@Nullable SimpleCache cache) {
		this.cache = cache;
		liveHttp = new DefaultHttpDataSource.Factory()
						.setUserAgent(Constant.USER_AGENT)
						.setConnectTimeoutMs(30_000)
						.setReadTimeoutMs(45_000);
		ytDashHttp = youtubeFactory(true, true);
		ytProgressiveHttp = youtubeFactory(false, true);
		YoutubeProgressiveDashManifestCreator.getCache().setMaximumSize(MANIFEST_CACHE_SIZE);
		YoutubeOtfDashManifestCreator.getCache().setMaximumSize(MANIFEST_CACHE_SIZE);
		YoutubePostLiveStreamDvrDashManifestCreator.getCache().setMaximumSize(MANIFEST_CACHE_SIZE);
	}

	@NonNull
	HlsMediaSource.Factory liveHlsFactory() {
		return new HlsMediaSource.Factory(liveHttp)
						.setAllowChunklessPreparation(true);
	}

	@NonNull
	DashMediaSource.Factory liveYoutubeDashFactory() {
		return new DashMediaSource.Factory(
						new DefaultDashChunkSource.Factory(liveHttp),
						liveHttp)
						.setManifestParser(new YoutubeDashLiveManifestParser());
	}

	@NonNull
	DashMediaSource.Factory youtubeProgressiveDashFactory(boolean live) {
		DataSource.Factory source = live ? ytDashHttp : maybeCache(ytDashHttp);
		return new DashMediaSource.Factory(source);
	}

	@NonNull
	ProgressiveMediaSource.Factory youtubeProgressiveFactory(boolean live) {
		DataSource.Factory source = progressiveSource(live);
		return new ProgressiveMediaSource.Factory(source)
						.setContinueLoadingCheckIntervalBytes(PROGRESSIVE_LOAD_INTERVAL_BYTES);
	}

	@NonNull
	DataSource.Factory progressiveSource(boolean live) {
		return live ? ytProgressiveHttp : maybeCache(ytProgressiveHttp);
	}

	int loadIntervalBytes() {
		return PROGRESSIVE_LOAD_INTERVAL_BYTES;
	}

	@NonNull
	private YoutubeHttpDataSource.Factory youtubeFactory(boolean rangeEnabled,
	                                                     boolean rnEnabled) {
		return new YoutubeHttpDataSource.Factory(Constant.USER_AGENT)
						.setConnectTimeoutMs(30_000)
						.setReadTimeoutMs(30_000)
						.setRangeParameterEnabled(rangeEnabled)
						.setRnParameterEnabled(rnEnabled);
	}

	@NonNull
	private DataSource.Factory maybeCache(@NonNull DataSource.Factory upstream) {
		if (cache == null) {
			return upstream;
		}
		return new CacheDataSource.Factory()
						.setCache(cache)
						.setUpstreamDataSourceFactory(upstream)
						.setCacheWriteDataSinkFactory(
										new CacheDataSink.Factory()
														.setCache(cache)
														.setFragmentSize(2L * 1024L * 1024L))
						.setCacheReadDataSourceFactory(new FileDataSource.Factory())
						.setCacheKeyFactory(PlayerDataSource::cacheKey)
						.setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
	}

	/**
	 * Stream URLs change with every extraction, so they are cached under the parts that stay the
	 * same (video, format and size). Otherwise every replay would be stored again.
	 */
	@NonNull
	static String cacheKey(@NonNull DataSpec spec) {
		if (spec.key != null) return spec.key;
		Uri uri = spec.uri;
		String host = uri.getHost();
		if (host != null && host.endsWith(".googlevideo.com") && uri.isHierarchical()) {
			String id = uri.getQueryParameter("id");
			String itag = uri.getQueryParameter("itag");
			if (id != null && itag != null) {
				String clen = uri.getQueryParameter("clen");
				return "gv:" + id + ":" + itag + ":" + (clen == null ? "" : clen);
			}
		}
		return uri.toString();
	}
}
