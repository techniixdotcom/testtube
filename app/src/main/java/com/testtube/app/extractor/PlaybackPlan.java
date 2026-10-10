package com.testtube.app.extractor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.stream.StreamType;

/**
 * Chosen playback plan for one video.
 */
public class PlaybackPlan {
	@NonNull
	private PlaybackMode mode = PlaybackMode.NONE;
	@NonNull
	private StreamType streamType = StreamType.VIDEO_STREAM;
	@Nullable
	private Delivery delivery;
	@Nullable
	private StreamCandidate videoCandidate;
	@Nullable
	private StreamCandidate audioCandidate;
	@Nullable
	private StreamCandidate muxedCandidate;

	@Nullable
	public String getManifestUrl() {
		return delivery != null ? delivery.getUrl() : null;
	}

	@NonNull
	public PlaybackMode getMode() {
		return mode;
	}

	public void setMode(@NonNull PlaybackMode mode) {
		this.mode = mode;
	}

	@NonNull
	public StreamType getStreamType() {
		return streamType;
	}

	public void setStreamType(@NonNull StreamType streamType) {
		this.streamType = streamType;
	}

	@Nullable
	public Delivery getDelivery() {
		return delivery;
	}

	public void setDelivery(@Nullable Delivery delivery) {
		this.delivery = delivery;
	}

	@Nullable
	public StreamCandidate getVideoCandidate() {
		return videoCandidate;
	}

	public void setVideoCandidate(@Nullable StreamCandidate videoCandidate) {
		this.videoCandidate = videoCandidate;
	}

	@Nullable
	public StreamCandidate getAudioCandidate() {
		return audioCandidate;
	}

	public void setAudioCandidate(@Nullable StreamCandidate audioCandidate) {
		this.audioCandidate = audioCandidate;
	}

	@Nullable
	public StreamCandidate getMuxedCandidate() {
		return muxedCandidate;
	}

	public void setMuxedCandidate(@Nullable StreamCandidate muxedCandidate) {
		this.muxedCandidate = muxedCandidate;
	}
}
