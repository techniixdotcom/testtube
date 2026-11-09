package com.testtube.app.player.common;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;

import com.testtube.app.AppSettings;
import com.testtube.app.player.engine.Engine;
import com.testtube.app.util.StringUtils;

import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@UnstableApi
public final class PlayerUtils {
	private static final Pattern FPS_SUFFIX = Pattern.compile("p(\\d+)$", Pattern.CASE_INSENSITIVE);

	public static boolean isPortrait(@NonNull Engine engine) {
		int videoWidth = engine.getVideoSize().width;
		int videoHeight = engine.getVideoSize().height;
		return videoWidth > 0 && videoHeight > 0 && videoHeight > videoWidth;
	}

	@NonNull
	public static List<VideoStream> filterBestStreams(@Nullable List<VideoStream> streams) {
		if (streams == null || streams.isEmpty()) return new ArrayList<>();

		Map<String, VideoStream> best = new HashMap<>();

		for (VideoStream stream : streams) {
			String res = stream.getResolution();
			String key = res + "#" + stream.getFps();
			VideoStream prev = best.get(key);

			if (prev == null || isBetterStream(stream, prev)) best.put(key, stream);
		}

		List<VideoStream> result = new ArrayList<>(best.values());
		result.sort((s1, s2) -> {
			int h1 = streamHeight(s1);
			int h2 = streamHeight(s2);
			if (h1 != h2) return Integer.compare(h2, h1);
			return Integer.compare(s2.getFps(), s1.getFps());
		});
		return result;
	}

	@NonNull
	public static List<String> sortResolutionLabels(@NonNull List<String> resolutions) {
		List<String> sorted = new ArrayList<>(resolutions);
		sorted.sort((left, right) -> {
			int height = Integer.compare(StringUtils.parseHeight(right), StringUtils.parseHeight(left));
			if (height != 0) return height;
			int fps = Integer.compare(parseFps(right), parseFps(left));
			if (fps != 0) return fps;
			return left.compareTo(right);
		});
		return sorted;
	}

	/** Height in px, from the label ("1080p60") when the stream doesn't say. */
	public static int streamHeight(@NonNull VideoStream stream) {
		int height = stream.getHeight();
		return height > 0 ? height : StringUtils.parseHeight(stream.getResolution());
	}

	private static int parseFps(@Nullable String resolution) {
		if (resolution == null) return 0;
		Matcher matcher = FPS_SUFFIX.matcher(resolution);
		return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
	}

	public static boolean isBetterStream(@NonNull VideoStream s1, @NonNull VideoStream s2) {
		int p1 = getCodecPriority(s1.getCodec());
		int p2 = getCodecPriority(s2.getCodec());
		if (p1 != p2) return p1 > p2;

		if (s1.getFps() != s2.getFps()) return s1.getFps() > s2.getFps();

		return s1.getBitrate() > s2.getBitrate();
	}

	/**
	 * Playback preference at the same size: hardware AV1, hardware VP9 (both much smaller than
	 * H.264), H.264, then software VP9 and AV1, which drain the battery.
	 */
	public static int getCodecPriority(@Nullable String codec) {
		if (codec == null) return 0;
		// with HDR off, HDR loses to any SDR of the same size
		if (isLikelyHdr(codec) && !AppSettings.allowHdr()) return -1;
		return switch (codecFamily(codec)) {
			case "av1" -> DecoderSupport.hardwareAv1() ? 6 : 1;
			case "vp9" -> DecoderSupport.hardwareVp9() ? 5 : 3;
			case "avc" -> 4;
			case "hevc" -> 2;
			default -> 0;
		};
	}

	/** "avc", "vp9" (VP8 too), "hevc", "av1" or "other". */
	@NonNull
	public static String codecFamily(@Nullable String codec) {
		if (codec == null) return "other";
		String lower = codec.toLowerCase(Locale.ROOT);
		if (isAvc(codec)) return "avc";
		if (lower.contains("vp9") || lower.contains("vp09") || lower.contains("vp8")) return "vp9";
		if (lower.contains("h265") || lower.contains("hev") || lower.contains("hvc")) return "hevc";
		if (lower.contains("av01")) return "av1";
		return "other";
	}

	/** AV1 this phone would decode in software. */
	public static boolean isSoftwareAv1(@Nullable String codec) {
		return "av1".equals(codecFamily(codec)) && !DecoderSupport.hardwareAv1();
	}

	/**
	 * One stream per quality the way downloads always picked them: H.264 first (it merges into
	 * MP4 reliably), then VP9, H.265, AV1; then frame rate and bitrate.
	 */
	@NonNull
	public static List<VideoStream> downloadStreams(@NonNull List<VideoStream> streams) {
		Map<String, VideoStream> best = new LinkedHashMap<>();
		for (VideoStream stream : streams) {
			String key = stream.getResolution() + "#" + stream.getFps();
			VideoStream prev = best.get(key);
			if (prev == null || downloadRank(stream, prev) > 0) best.put(key, stream);
		}
		return new ArrayList<>(best.values());
	}

	private static int downloadRank(@NonNull VideoStream first, @NonNull VideoStream second) {
		int codec = Integer.compare(downloadCodecRank(first.getCodec()), downloadCodecRank(second.getCodec()));
		if (codec != 0) return codec;
		int fps = Integer.compare(first.getFps(), second.getFps());
		if (fps != 0) return fps;
		return Integer.compare(first.getBitrate(), second.getBitrate());
	}

	private static int downloadCodecRank(@Nullable String codec) {
		return switch (codecFamily(codec)) {
			case "avc" -> 4;
			case "vp9" -> 3;
			case "hevc" -> 2;
			case "av1" -> 1;
			default -> 0;
		};
	}

	/** H.264. Never HDR on YouTube. */
	public static boolean isAvc(@Nullable String codec) {
		if (codec == null) return false;
		String lower = codec.toLowerCase(Locale.ROOT);
		return lower.startsWith("avc") || lower.startsWith("h264");
	}

	/**
	 * HDR (10+ bit) by codec string: VP9 profile 2/3 ("vp09.02.…") or AV1 depth 10/12
	 * ("av01.0.12M.10"). Parsed by field, not by searching ".10", which an AV1 level like "10M" would
	 * match. Unclear means SDR so nothing playable gets dropped.
	 */
	public static boolean isLikelyHdr(@Nullable String codec) {
		if (codec == null) return false;
		String[] fields = codec.trim().toLowerCase(Locale.ROOT).split("\\.");
		if (fields.length >= 2 && fields[0].equals("vp09")) {
			return fields[1].equals("02") || fields[1].equals("03")
							|| (fields.length >= 4 && isHighBitDepth(fields[3]));
		}
		if (fields.length >= 4 && fields[0].equals("av01")) return isHighBitDepth(fields[3]);
		return false;
	}

	private static boolean isHighBitDepth(@NonNull String field) {
		return field.equals("10") || field.equals("12");
	}

	@Nullable
	public static VideoStream selectVideoStream(@Nullable List<VideoStream> streams, @Nullable String targetRes) {
		if (streams == null || streams.isEmpty()) return null;
		if (targetRes == null) return streams.get(0);

		for (VideoStream s : streams) if (s.getResolution().equals(targetRes)) return s;

		int targetHeight = StringUtils.parseHeight(targetRes);
		for (VideoStream s : streams) if (s.getHeight() <= targetHeight) return s;

		return streams.get(0);
	}

	/** First audio track, YouTube's default. */
	@Nullable
	public static AudioStream defaultAudioStream(@Nullable List<AudioStream> streams) {
		return streams == null || streams.isEmpty() ? null : streams.get(0);
	}
}
