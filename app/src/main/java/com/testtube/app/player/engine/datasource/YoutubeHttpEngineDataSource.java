package com.testtube.app.player.engine.datasource;

import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresExtension;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.HttpEngineDataSource;

import com.google.common.net.HttpHeaders;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * YouTube streams over HttpEngine. Requests look like {@link YoutubeHttpDataSource}'s (see
 * {@link YoutubeRequests}); Media3's HttpEngineDataSource does the transfer, timeouts, redirects
 * and cancelling.
 */
@UnstableApi
@RequiresExtension(extension = Build.VERSION_CODES.S, version = 7)
final class YoutubeHttpEngineDataSource extends BaseDataSource implements HttpDataSource {
	@NonNull
	private final HttpDataSource upstream;
	@NonNull
	private final String userAgent;
	private final boolean rangeParameterEnabled;
	private final boolean rnParameterEnabled;
	private long requestNumber;
	private long bytesRemaining;
	private boolean opened;

	private YoutubeHttpEngineDataSource(@NonNull HttpDataSource upstream,
	                                    @NonNull String userAgent,
	                                    boolean rangeParameterEnabled,
	                                    boolean rnParameterEnabled) {
		super(true);
		this.upstream = upstream;
		this.userAgent = userAgent;
		this.rangeParameterEnabled = rangeParameterEnabled;
		this.rnParameterEnabled = rnParameterEnabled;
	}

	@Override
	public long open(@NonNull DataSpec dataSpec) throws HttpDataSourceException {
		transferInitializing(dataSpec);
		boolean playback = YoutubeRequests.isVideoPlayback(dataSpec.uri.getPath());
		String requestUrl = YoutubeRequests.requestUrl(dataSpec.uri.toString(), playback, rangeParameterEnabled,
						dataSpec.position, dataSpec.length, rnParameterEnabled ? () -> requestNumber++ : null);

		Map<String, String> headers = new HashMap<>(dataSpec.httpRequestHeaders);
		headers.putAll(YoutubeRequests.headers(requestUrl, userAgent));

		DataSpec.Builder request = dataSpec.buildUpon().setUri(Uri.parse(requestUrl));
		if (playback && rangeParameterEnabled) {
			// range is in the URL now, a Range header on top confuses googlevideo
			request.setPosition(0).setLength(C.LENGTH_UNSET);
		}
		if (playback) {
			headers.put(HttpHeaders.CONTENT_TYPE, YoutubeRequests.POST_CONTENT_TYPE);
			request.setHttpMethod(DataSpec.HTTP_METHOD_POST).setHttpBody(YoutubeRequests.POST_BODY);
		} else {
			request.setHttpMethod(DataSpec.HTTP_METHOD_GET).setHttpBody(null);
		}
		request.setHttpRequestHeaders(headers);

		long length = upstream.open(request.build());
		// never return more than asked, even if the server ignores the range
		bytesRemaining = dataSpec.length != C.LENGTH_UNSET ? dataSpec.length : length;
		opened = true;
		transferStarted(dataSpec);
		return bytesRemaining;
	}

	@Override
	public int read(@NonNull byte[] buffer, int offset, int length) throws HttpDataSourceException {
		if (length == 0) return 0;
		if (bytesRemaining == 0) return C.RESULT_END_OF_INPUT;
		int toRead = bytesRemaining == C.LENGTH_UNSET ? length : (int) Math.min(length, bytesRemaining);
		int read = upstream.read(buffer, offset, toRead);
		if (read == C.RESULT_END_OF_INPUT) return C.RESULT_END_OF_INPUT;
		if (bytesRemaining != C.LENGTH_UNSET) bytesRemaining -= read;
		bytesTransferred(read);
		return read;
	}

	@Override
	public void close() throws HttpDataSourceException {
		try {
			upstream.close();
		} finally {
			if (opened) {
				opened = false;
				transferEnded();
			}
		}
	}

	@Nullable
	@Override
	public Uri getUri() {
		return upstream.getUri();
	}

	@Override
	public int getResponseCode() {
		return upstream.getResponseCode();
	}

	@NonNull
	@Override
	public Map<String, List<String>> getResponseHeaders() {
		return upstream.getResponseHeaders();
	}

	@Override
	public void setRequestProperty(@NonNull String name, @NonNull String value) {
		upstream.setRequestProperty(name, value);
	}

	@Override
	public void clearRequestProperty(@NonNull String name) {
		upstream.clearRequestProperty(name);
	}

	@Override
	public void clearAllRequestProperties() {
		upstream.clearAllRequestProperties();
	}

	static final class Factory implements HttpDataSource.Factory {
		@NonNull
		private final HttpEngineDataSource.Factory upstream;
		@NonNull
		private final String userAgent;
		private final boolean rangeParameterEnabled;
		private final boolean rnParameterEnabled;

		Factory(@NonNull HttpEngineDataSource.Factory upstream,
		        @NonNull String userAgent,
		        boolean rangeParameterEnabled,
		        boolean rnParameterEnabled) {
			this.upstream = upstream;
			this.userAgent = userAgent;
			this.rangeParameterEnabled = rangeParameterEnabled;
			this.rnParameterEnabled = rnParameterEnabled;
		}

		@NonNull
		@Override
		public Factory setDefaultRequestProperties(@NonNull Map<String, String> defaultRequestProperties) {
			upstream.setDefaultRequestProperties(defaultRequestProperties);
			return this;
		}

		@NonNull
		@Override
		public HttpDataSource createDataSource() {
			return new YoutubeHttpEngineDataSource(upstream.createDataSource(), userAgent, rangeParameterEnabled, rnParameterEnabled);
		}
	}
}
