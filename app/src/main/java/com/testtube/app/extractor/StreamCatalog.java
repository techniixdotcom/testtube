package com.testtube.app.extractor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class StreamCatalog {
	@NonNull
	private StreamType streamType = StreamType.VIDEO_STREAM;
	@NonNull
	private List<StreamCandidate> manifestCandidates = new ArrayList<>();
	@NonNull
	private List<StreamCandidate> videoCandidates = new ArrayList<>();
	@NonNull
	private List<StreamCandidate> audioCandidates = new ArrayList<>();
	@NonNull
	private List<StreamCandidate> muxedCandidates = new ArrayList<>();
	@NonNull
	private List<StreamCandidate> subtitleCandidates = new ArrayList<>();

	@NonNull
	public List<VideoStream> getVideoStreams() {
		Map<String, VideoStream> streams = new LinkedHashMap<>();
		for (StreamCandidate candidate : videoCandidates) {
			if (candidate.getVideoStream() != null) {
				streams.putIfAbsent(candidate.getVideoStream().getContent(), candidate.getVideoStream());
			}
		}
		return new ArrayList<>(streams.values());
	}

	@NonNull
	public List<AudioStream> getAudioStreams() {
		Map<String, AudioStream> streams = new LinkedHashMap<>();
		for (StreamCandidate candidate : audioCandidates) {
			if (candidate.getAudioStream() != null) {
				streams.putIfAbsent(candidate.getAudioStream().getContent(), candidate.getAudioStream());
			}
		}
		return new ArrayList<>(streams.values());
	}

	@Nullable
	public StreamCandidate firstDashManifest() {
		return firstCandidate(StreamCandidateKind.DASH_MANIFEST);
	}

	@Nullable
	public StreamCandidate firstHlsManifest() {
		return firstCandidate(StreamCandidateKind.HLS_MANIFEST);
	}

	@Nullable
	private StreamCandidate firstCandidate(@NonNull StreamCandidateKind kind) {
		for (StreamCandidate candidate : manifestCandidates) {
			if (candidate.getKind() == kind) {
				return candidate;
			}
		}
		return null;
	}

	@NonNull
	public StreamType getStreamType() {
		return streamType;
	}

	public void setStreamType(@NonNull StreamType streamType) {
		this.streamType = streamType;
	}

	@NonNull
	public List<StreamCandidate> getManifestCandidates() {
		return manifestCandidates;
	}

	@NonNull
	public List<StreamCandidate> getVideoCandidates() {
		return videoCandidates;
	}

	@NonNull
	public List<StreamCandidate> getAudioCandidates() {
		return audioCandidates;
	}

	@NonNull
	public List<StreamCandidate> getMuxedCandidates() {
		return muxedCandidates;
	}

	@NonNull
	public List<StreamCandidate> getSubtitleCandidates() {
		return subtitleCandidates;
	}
}
