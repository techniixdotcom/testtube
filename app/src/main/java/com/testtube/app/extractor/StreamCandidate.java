package com.testtube.app.extractor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.SubtitlesStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

/**
 * Candidate stream wrapper with selection metadata.
 */
public class StreamCandidate {
	@NonNull
	private StreamCandidateKind kind = StreamCandidateKind.MUXED;
	@NonNull
	private StreamProtocol protocol = StreamProtocol.UNKNOWN;
	@Nullable
	private String sourceClient;
	private boolean playerPoToken;
	private boolean streamPoToken;
	private boolean live;
	@Nullable
	private String url;
	@Nullable
	private VideoStream videoStream;
	@Nullable
	private AudioStream audioStream;
	@Nullable
	private SubtitlesStream subtitleStream;

	@NonNull
	public static StreamCandidate dashManifest(@NonNull String url,
	                                           @Nullable String sourceClient,
	                                           final boolean playerPoToken,
	                                           final boolean streamPoToken,
	                                           final boolean live) {
		return candidate(StreamCandidateKind.DASH_MANIFEST, StreamProtocol.DASH, url, sourceClient,
						playerPoToken, streamPoToken, live, null, null, null);
	}

	@NonNull
	public static StreamCandidate hlsManifest(@NonNull String url,
	                                          @Nullable String sourceClient,
	                                          final boolean playerPoToken,
	                                          final boolean streamPoToken,
	                                          final boolean live) {
		return candidate(StreamCandidateKind.HLS_MANIFEST, StreamProtocol.HLS, url, sourceClient,
						playerPoToken, streamPoToken, live, null, null, null);
	}

	@NonNull
	public static StreamCandidate videoOnly(@NonNull VideoStream stream,
	                                        @Nullable String sourceClient,
	                                        final boolean playerPoToken,
	                                        final boolean streamPoToken,
	                                        final boolean live) {
		return candidate(StreamCandidateKind.VIDEO_ONLY, protocolOf(stream.getContent()),
						stream.getContent(), sourceClient, playerPoToken, streamPoToken, live, stream, null, null);
	}

	@NonNull
	public static StreamCandidate muxed(@NonNull VideoStream stream,
	                                    @Nullable String sourceClient,
	                                    final boolean playerPoToken,
	                                    final boolean streamPoToken,
	                                    final boolean live) {
		return candidate(StreamCandidateKind.MUXED, protocolOf(stream.getContent()),
						stream.getContent(), sourceClient, playerPoToken, streamPoToken, live, stream, null, null);
	}

	@NonNull
	public static StreamCandidate audioOnly(@NonNull AudioStream stream,
	                                        @Nullable String sourceClient,
	                                        final boolean playerPoToken,
	                                        final boolean streamPoToken,
	                                        final boolean live) {
		return candidate(StreamCandidateKind.AUDIO_ONLY, protocolOf(stream.getContent()),
						stream.getContent(), sourceClient, playerPoToken, streamPoToken, live, null, stream, null);
	}

	@NonNull
	public static StreamCandidate subtitle(@NonNull SubtitlesStream stream) {
		return candidate(StreamCandidateKind.SUBTITLE, protocolOf(stream.getContent()),
						stream.getContent(), null, false, false, false, null, null, stream);
	}

	@NonNull
	private static StreamCandidate candidate(@NonNull StreamCandidateKind kind,
	                                         @NonNull StreamProtocol protocol,
	                                         @Nullable String url,
	                                         @Nullable String sourceClient,
	                                         final boolean playerPoToken,
	                                         final boolean streamPoToken,
	                                         final boolean live,
	                                         @Nullable VideoStream videoStream,
	                                         @Nullable AudioStream audioStream,
	                                         @Nullable SubtitlesStream subtitleStream) {
		StreamCandidate candidate = new StreamCandidate();
		candidate.setKind(kind);
		candidate.setProtocol(protocol);
		candidate.setUrl(url);
		candidate.setSourceClient(sourceClient);
		candidate.setPlayerPoToken(playerPoToken);
		candidate.setStreamPoToken(streamPoToken);
		candidate.setLive(live);
		candidate.setVideoStream(videoStream);
		candidate.setAudioStream(audioStream);
		candidate.setSubtitleStream(subtitleStream);
		return candidate;
	}

	@NonNull
	private static StreamProtocol protocolOf(@Nullable String url) {
		if (url == null || url.isBlank()) {
			return StreamProtocol.UNKNOWN;
		}
		if (url.contains("manifest/dash") || url.contains("mpd_version=")) {
			return StreamProtocol.DASH;
		}
		if (url.contains("manifest/hls") || url.contains(".m3u8")) {
			return StreamProtocol.HLS;
		}
		return StreamProtocol.HTTPS;
	}

	@NonNull
	public StreamCandidateKind getKind() {
		return kind;
	}

	public void setKind(@NonNull StreamCandidateKind kind) {
		this.kind = kind;
	}

	public void setProtocol(@NonNull StreamProtocol protocol) {
		this.protocol = protocol;
	}

	@Nullable
	public String getSourceClient() {
		return sourceClient;
	}

	public void setSourceClient(@Nullable String sourceClient) {
		this.sourceClient = sourceClient;
	}

	public void setPlayerPoToken(boolean playerPoToken) {
		this.playerPoToken = playerPoToken;
	}

	public void setStreamPoToken(boolean streamPoToken) {
		this.streamPoToken = streamPoToken;
	}

	public boolean isLive() {
		return live;
	}

	public void setLive(boolean live) {
		this.live = live;
	}

	@Nullable
	public String getUrl() {
		return url;
	}

	public void setUrl(@Nullable String url) {
		this.url = url;
	}

	@Nullable
	public VideoStream getVideoStream() {
		return videoStream;
	}

	public void setVideoStream(@Nullable VideoStream videoStream) {
		this.videoStream = videoStream;
	}

	@Nullable
	public AudioStream getAudioStream() {
		return audioStream;
	}

	public void setAudioStream(@Nullable AudioStream audioStream) {
		this.audioStream = audioStream;
	}

	public void setSubtitleStream(@Nullable SubtitlesStream subtitleStream) {
		this.subtitleStream = subtitleStream;
	}
}
