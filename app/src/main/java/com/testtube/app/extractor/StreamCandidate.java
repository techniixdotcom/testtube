package com.testtube.app.extractor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

public class StreamCandidate {
	@NonNull
	private StreamCandidateKind kind = StreamCandidateKind.MUXED;
	@Nullable
	private String sourceClient;
	@Nullable
	private String url;
	@Nullable
	private VideoStream videoStream;
	@Nullable
	private AudioStream audioStream;

	// Gson fills the fields when the details are copied out of the cache
	private StreamCandidate() {
	}

	private StreamCandidate(@NonNull StreamCandidateKind kind,
	                        @Nullable String url,
	                        @Nullable String sourceClient,
	                        @Nullable VideoStream videoStream,
	                        @Nullable AudioStream audioStream) {
		this.kind = kind;
		this.url = url;
		this.sourceClient = sourceClient;
		this.videoStream = videoStream;
		this.audioStream = audioStream;
	}

	@NonNull
	public static StreamCandidate dashManifest(@NonNull String url, @Nullable String sourceClient) {
		return new StreamCandidate(StreamCandidateKind.DASH_MANIFEST, url, sourceClient, null, null);
	}

	@NonNull
	public static StreamCandidate hlsManifest(@NonNull String url, @Nullable String sourceClient) {
		return new StreamCandidate(StreamCandidateKind.HLS_MANIFEST, url, sourceClient, null, null);
	}

	@NonNull
	public static StreamCandidate videoOnly(@NonNull VideoStream stream, @Nullable String sourceClient) {
		return new StreamCandidate(StreamCandidateKind.VIDEO_ONLY, stream.getContent(), sourceClient, stream, null);
	}

	@NonNull
	public static StreamCandidate muxed(@NonNull VideoStream stream, @Nullable String sourceClient) {
		return new StreamCandidate(StreamCandidateKind.MUXED, stream.getContent(), sourceClient, stream, null);
	}

	@NonNull
	public static StreamCandidate audioOnly(@NonNull AudioStream stream, @Nullable String sourceClient) {
		return new StreamCandidate(StreamCandidateKind.AUDIO_ONLY, stream.getContent(), sourceClient, null, stream);
	}

	@NonNull
	public StreamCandidateKind getKind() {
		return kind;
	}

	@Nullable
	public String getSourceClient() {
		return sourceClient;
	}

	@Nullable
	public String getUrl() {
		return url;
	}

	@Nullable
	public VideoStream getVideoStream() {
		return videoStream;
	}

	@Nullable
	public AudioStream getAudioStream() {
		return audioStream;
	}
}
