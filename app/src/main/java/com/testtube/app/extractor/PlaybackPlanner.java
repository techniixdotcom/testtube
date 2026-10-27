package com.testtube.app.extractor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;

import com.testtube.app.player.common.PlayerUtils;

import org.schabi.newpipe.extractor.services.youtube.ItagItem;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.AudioTrackType;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@OptIn(markerClass = UnstableApi.class)
public final class PlaybackPlanner {
	private PlaybackPlanner() {
	}

	@NonNull
	public static PlaybackPlan plan(@NonNull DeliveryCatalog deliveries) {
		return plan(deliveries, null);
	}

	@NonNull
	public static PlaybackPlan plan(@NonNull DeliveryCatalog deliveries,
	                                @Nullable String preferredQuality) {
		PlaybackPlan plan = new PlaybackPlan();
		plan.setStreamType(deliveries.getStreamType());

		boolean live = deliveries.getStreamType() == StreamType.LIVE_STREAM
						|| deliveries.getStreamType() == StreamType.AUDIO_LIVE_STREAM;
		if (live) {
			Delivery dash = deliveries.first(PlaybackMode.LIVE_DASH);
			if (dash != null) {
				return adaptivePlan(plan, dash, preferredQuality);
			}
			Delivery hls = deliveries.first(PlaybackMode.LIVE_HLS);
			if (hls != null) {
				plan.setMode(PlaybackMode.LIVE_HLS);
				plan.setDelivery(hls);
				return plan;
			}
			return plan;
		}

		Delivery adaptive = deliveries.first(PlaybackMode.ADAPTIVE);
		if (adaptive != null) {
			PlaybackPlan adaptivePlan = adaptivePlan(plan, adaptive, preferredQuality);
			if (adaptivePlan.getVideoCandidate() != null && adaptivePlan.getAudioCandidate() != null) {
				return adaptivePlan;
			}
		}

		Delivery muxed = deliveries.first(PlaybackMode.MUXED);
		if (muxed != null) {
			VideoStream selected = PlayerUtils.selectVideoStream(muxed.muxedStreams(), preferredQuality);
			plan.setMode(PlaybackMode.MUXED);
			plan.setDelivery(muxed);
			plan.setMuxedCandidate(findVideoCandidate(muxed.getMuxed(), selected));
			return plan;
		}

		Delivery audio = deliveries.first(PlaybackMode.AUDIO_ONLY);
		if (audio != null) {
			List<AudioStream> tracks = originalFirst(audio.audioStreams());
			plan.setMode(PlaybackMode.AUDIO_ONLY);
			plan.setDelivery(audio);
			plan.setAudioCandidate(findAudioCandidate(audio.getAudio(),
							PlayerUtils.defaultAudioStream(tracks)));
		}
		return plan;
	}

	@Nullable
	public static PlaybackPlan muxedFallbackPlan(@NonNull DeliveryCatalog deliveries,
	                                             @Nullable String preferredQuality) {
		Delivery muxed = deliveries.first(PlaybackMode.MUXED);
		if (muxed == null) {
			return null;
		}
		VideoStream selected = PlayerUtils.selectVideoStream(muxed.muxedStreams(), preferredQuality);
		PlaybackPlan plan = new PlaybackPlan();
		plan.setStreamType(deliveries.getStreamType());
		plan.setMode(PlaybackMode.MUXED);
		plan.setDelivery(muxed);
		plan.setMuxedCandidate(findVideoCandidate(muxed.getMuxed(), selected));
		return plan.getMuxedCandidate() != null ? plan : null;
	}

	@Nullable
	public static PlaybackPlan muxedFallbackPlan(@NonNull DeliveryCatalog deliveries,
	                                             @Nullable String preferredQuality,
	                                             @NonNull Predicate<StreamCandidate> blocked) {
		Delivery muxed = deliveries.first(PlaybackMode.MUXED);
		if (muxed == null) {
			return null;
		}
		List<StreamCandidate> candidates = muxed.getMuxed().stream()
						.filter(candidate -> !blocked.test(candidate))
						.collect(Collectors.toList());
		List<VideoStream> streams = videoStreams(candidates);
		VideoStream selected = PlayerUtils.selectVideoStream(streams, preferredQuality);
		PlaybackPlan plan = new PlaybackPlan();
		plan.setStreamType(deliveries.getStreamType());
		plan.setMode(PlaybackMode.MUXED);
		plan.setDelivery(muxed);
		plan.setMuxedCandidate(findVideoCandidate(candidates, selected));
		return plan.getMuxedCandidate() != null ? plan : null;
	}

	@Nullable
	public static PlaybackPlan adaptiveFallbackPlan(@NonNull DeliveryCatalog deliveries,
	                                                @Nullable String preferredQuality,
	                                                @NonNull Predicate<StreamCandidate> blocked) {
		Delivery adaptive = deliveries.first(PlaybackMode.ADAPTIVE);
		if (adaptive == null) {
			return null;
		}
		List<StreamCandidate> video = adaptive.getVideo().stream()
						.filter(candidate -> !blocked.test(candidate))
						.collect(Collectors.toList());
		List<StreamCandidate> audio = adaptive.getAudio().stream()
						.filter(candidate -> !blocked.test(candidate))
						.collect(Collectors.toList());
		PlaybackPlan plan = adaptivePlan(new PlaybackPlan(), adaptive, video, audio, preferredQuality);
		return plan.getVideoCandidate() != null && plan.getAudioCandidate() != null ? plan : null;
	}

	@Nullable
	private static StreamCandidate findVideoCandidate(@NonNull List<StreamCandidate> candidates,
	                                                  @Nullable VideoStream stream) {
		if (stream == null) {
			return null;
		}
		for (StreamCandidate candidate : candidates) {
			if (candidate.getVideoStream() != null
							&& stream.getContent().equals(candidate.getVideoStream().getContent())) {
				return candidate;
			}
		}
		return null;
	}

	@Nullable
	public static StreamCandidate findAudioCandidate(@NonNull List<StreamCandidate> candidates,
	                                                  @Nullable AudioStream stream) {
		if (stream == null) {
			return null;
		}
		for (StreamCandidate candidate : candidates) {
			if (candidate.getAudioStream() != null
							&& stream.getContent().equals(candidate.getAudioStream().getContent())) {
				return candidate;
			}
		}
		return null;
	}

	/**
	 * Audio tracks with the creator's original first, the way it was uploaded. Then other tracks
	 * a person recorded (dubs, secondary), then audio description, and YouTube's automatic AI
	 * dubs last. Within each, the better bitrate wins.
	 */
	@NonNull
	public static List<AudioStream> originalFirst(@NonNull List<AudioStream> source) {
		List<AudioStream> sorted = new ArrayList<>(source);
		sorted.sort((first, second) -> {
			int rank = Integer.compare(audioRank(first), audioRank(second));
			return rank != 0 ? rank : Integer.compare(audioBitrate(second), audioBitrate(first));
		});
		return sorted;
	}

	/** The original track, or the closest there is; null without audio. */
	@Nullable
	public static AudioStream originalAudio(@NonNull List<AudioStream> streams) {
		return streams.isEmpty() ? null : originalFirst(streams).get(0);
	}

	/** Lower is closer to the original upload. */
	private static int audioRank(@NonNull AudioStream stream) {
		ItagItem itag = stream.getItagItem();
		if (itag != null && itag.isAutoGenerated()) return 4;
		AudioTrackType type = stream.getAudioTrackType();
		if (type == null) return 1;
		return switch (type) {
			case ORIGINAL -> 0;
			case DESCRIPTIVE -> 3;
			default -> 2;
		};
	}

	static int audioBitrate(@NonNull AudioStream stream) {
		return stream.getAverageBitrate() > 0 ? stream.getAverageBitrate() : stream.getBitrate();
	}

	@NonNull
	private static PlaybackPlan adaptivePlan(@NonNull PlaybackPlan plan,
	                                         @NonNull Delivery delivery,
	                                         @Nullable String preferredQuality) {
		return adaptivePlan(plan, delivery, delivery.getVideo(), delivery.getAudio(), preferredQuality);
	}

	@NonNull
	private static PlaybackPlan adaptivePlan(@NonNull PlaybackPlan plan,
	                                         @NonNull Delivery delivery,
	                                         @NonNull List<StreamCandidate> videoCandidates,
	                                         @NonNull List<StreamCandidate> audioCandidates,
	                                         @Nullable String preferredQuality) {
		plan.setMode(delivery.getMode());
		plan.setDelivery(delivery);
		VideoStream video = PlayerUtils.selectVideoStream(videoStreams(videoCandidates), preferredQuality);
		List<AudioStream> tracks = originalFirst(audioStreams(audioCandidates));
		plan.setVideoCandidate(findVideoCandidate(videoCandidates, video));
		plan.setAudioCandidate(findAudioCandidate(audioCandidates, PlayerUtils.defaultAudioStream(tracks)));
		return plan;
	}

	@NonNull
	private static List<VideoStream> videoStreams(@NonNull List<StreamCandidate> candidates) {
		List<VideoStream> streams = new ArrayList<>();
		for (StreamCandidate candidate : candidates) {
			if (candidate.getVideoStream() != null) {
				streams.add(candidate.getVideoStream());
			}
		}
		return streams;
	}

	@NonNull
	private static List<AudioStream> audioStreams(@NonNull List<StreamCandidate> candidates) {
		List<AudioStream> streams = new ArrayList<>();
		for (StreamCandidate candidate : candidates) {
			if (candidate.getAudioStream() != null) {
				streams.add(candidate.getAudioStream());
			}
		}
		return streams;
	}
}
