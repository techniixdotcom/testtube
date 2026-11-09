package com.testtube.app.plus;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.media3.common.util.UnstableApi;

import com.testtube.app.AppGraph;
import com.testtube.app.Constant;
import com.testtube.app.R;
import com.testtube.app.external.ExternalException;
import com.testtube.app.external.ExternalVideo;
import com.testtube.app.extractor.FeedItem;

import java.io.IOException;
import java.io.InputStream;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Facebook videos and SoundCloud tracks for the player: which links, their ids, what to play.
 * Used through {@code ExternalSources}.
 */
@UnstableApi
public final class PlusSources {
	private static final String TAG = "PlusSources";
	/** Covers are a few dozen KB, bigger isn't a cover. */
	private static final int MAX_ARTWORK_BYTES = 2 * 1024 * 1024;

	private PlusSources() {
	}

	public static boolean handles(@Nullable String url) {
		if (url == null) return false;
		if (FacebookReader.isVideoUrl(url)) return true;
		SoundCloudLink.Ref ref = SoundCloudLink.parse(url);
		return ref != null && ref.kind() == SoundCloudLink.Kind.TRACK;
	}

	@Nullable
	public static String idOf(@Nullable String url) {
		if (url == null) return null;
		if (FacebookReader.isVideoUrl(url)) {
			String key = FacebookReader.idKey(url);
			return key != null ? "fb:" + key : null;
		}
		SoundCloudLink.Ref ref = SoundCloudLink.parse(url);
		if (ref != null && ref.kind() == SoundCloudLink.Kind.TRACK) return "sc:" + ref.key();
		return null;
	}

	@WorkerThread
	@NonNull
	public static ExternalVideo resolve(@NonNull Context context, @NonNull AppGraph graph, @NonNull String url)
					throws IOException, InterruptedException, ExternalException {
		String id = idOf(url);
		if (id == null) throw new ExternalException(R.string.failure_other, "not a supported link: " + url);
		OkHttpClient client = WebPage.client(graph.okHttpClient());
		if (id.startsWith("fb:")) return facebook(context, client, id, url);
		return soundCloud(graph, client, id, url);
	}

	@NonNull
	private static ExternalVideo facebook(@NonNull Context context, @NonNull OkHttpClient client, @NonNull String id,
	                                      @NonNull String url) throws IOException, ExternalException {
		try {
			FacebookVideo video = FacebookReader.read(client, url, Constant.DESKTOP_USER_AGENT);
			String title = video.title() != null ? video.title() : context.getString(R.string.link_facebook_video);
			return new ExternalVideo(id, url, video.streamUrl(), false, title, video.author(), video.thumbnailUrl(),
							video.description(), video.durationMs(), FacebookReader.STREAM_USER_AGENT, null);
		} catch (FacebookReader.FacebookException e) {
			Log.w(TAG, "Facebook video unavailable: " + e.problem + " " + e.getMessage());
			int message = switch (e.problem) {
				case LOGIN -> R.string.link_facebook_login;
				case NO_VIDEO -> R.string.link_facebook_no_video;
				case UNREADABLE -> R.string.link_facebook_failed;
			};
			throw new ExternalException(message, String.valueOf(e.getMessage()));
		}
	}

	@NonNull
	private static ExternalVideo soundCloud(@NonNull AppGraph graph, @NonNull OkHttpClient client, @NonNull String id,
	                                        @NonNull String url) throws IOException, InterruptedException, ExternalException {
		try {
			SoundCloudReader.Resolved resolved = SoundCloudReader.resolve(client, url);
			if (!"track".equals(resolved.kind())) {
				throw new ExternalException(R.string.link_soundcloud_failed, "not a track: " + resolved.kind());
			}
			SoundCloudReader.Track track = SoundCloudReader.track(client, resolved.json());
			return new ExternalVideo(id, url, track.streamUrl(), track.hls(), track.title(), track.author(),
							track.artworkUrl(), track.description(), track.durationMs(), Constant.DESKTOP_USER_AGENT,
							artwork(client, track.artworkUrl()));
		} catch (SoundCloudReader.SoundCloudException e) {
			Log.w(TAG, "SoundCloud track unavailable: " + e.problem + " " + e.getMessage());
			if (e.problem == SoundCloudReader.SoundCloudException.Problem.PREVIEW && e.song != null) {
				// only 30 s on SoundCloud (Go+): whole song from YouTube if it's there
				FeedItem match = YoutubeSearch.find(graph.feedClient(), e.song);
				if (match != null && match.videoId() != null) {
					throw new ExternalException(R.string.link_soundcloud_preview_youtube, "preview, playing YouTube",
									match.videoId());
				}
				throw new ExternalException(R.string.link_soundcloud_preview_only, "preview, not on YouTube");
			}
			int message = switch (e.problem) {
				case NOT_FOUND -> R.string.link_soundcloud_not_found;
				case BLOCKED -> R.string.link_soundcloud_blocked;
				case UNPLAYABLE -> R.string.link_soundcloud_unplayable;
				case PREVIEW -> R.string.link_soundcloud_preview_only;
				case UNREADABLE -> R.string.link_soundcloud_failed;
			};
			throw new ExternalException(message, String.valueOf(e.getMessage()));
		}
	}

	/** Cover bytes to show over the sound, or null. */
	@Nullable
	private static byte[] artwork(@NonNull OkHttpClient client, @Nullable String url) {
		if (url == null || !url.startsWith("https://")) return null;
		Request request = new Request.Builder().url(url).header("User-Agent", Constant.DESKTOP_USER_AGENT).build();
		try (Response response = client.newCall(request).execute()) {
			ResponseBody body = response.body();
			if (!response.isSuccessful() || body == null) return null;
			if (body.contentLength() > MAX_ARTWORK_BYTES) return null;
			try (InputStream in = body.byteStream()) {
				java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
				byte[] buffer = new byte[16 * 1024];
				int read;
				int total = 0;
				while ((read = in.read(buffer)) != -1) {
					total += read;
					if (total > MAX_ARTWORK_BYTES) return null;
					out.write(buffer, 0, read);
				}
				return out.toByteArray();
			}
		} catch (IOException | IllegalArgumentException e) {
			return null;
		}
	}
}
