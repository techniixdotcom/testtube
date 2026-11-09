package com.testtube.app.player.common;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;

import androidx.annotation.NonNull;

import java.util.Locale;

/**
 * Which of YouTube's efficient codecs this phone decodes in hardware. Software VP9 and AV1 eat
 * battery, hardware ones beat H.264 on size at the same quality. Asked once, then cached.
 */
public final class DecoderSupport {
	private static final String MIME_VP9 = "video/x-vnd.on2.vp9";
	private static final String MIME_AV1 = "video/av01";

	private static volatile boolean loaded;
	private static boolean vp9;
	private static boolean av1;

	private DecoderSupport() {
	}

	/** Asks the system now; call off the main thread so the first video doesn't. */
	public static void load() {
		if (loaded) return;
		synchronized (DecoderSupport.class) {
			if (loaded) return;
			boolean hasVp9 = false;
			boolean hasAv1 = false;
			try {
				MediaCodecInfo[] codecs = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
				if (codecs != null) {
					for (MediaCodecInfo info : codecs) {
						if (info.isEncoder() || !isHardware(info)) continue;
						for (String type : info.getSupportedTypes()) {
							if (MIME_VP9.equalsIgnoreCase(type)) hasVp9 = true;
							if (MIME_AV1.equalsIgnoreCase(type)) hasAv1 = true;
						}
					}
				}
			} catch (RuntimeException e) {
				// unknown: rank like before, H.264 first
			}
			vp9 = hasVp9;
			av1 = hasAv1;
			loaded = true;
		}
	}

	public static boolean hardwareVp9() {
		load();
		return vp9;
	}

	public static boolean hardwareAv1() {
		load();
		return av1;
	}

	/** Tests: pretend these decoders exist (or not). */
	static synchronized void override(boolean hardwareVp9, boolean hardwareAv1) {
		vp9 = hardwareVp9;
		av1 = hardwareAv1;
		loaded = true;
	}

	/** Tests: ask the system again next time. */
	static synchronized void reset() {
		loaded = false;
	}

	private static boolean isHardware(@NonNull MediaCodecInfo info) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.isHardwareAccelerated();
		// same name rules as Media3's MediaCodecUtil before Android 10
		String name = info.getName().toLowerCase(Locale.ROOT);
		if (name.startsWith("arc.")) return false;
		return !(name.startsWith("omx.google.")
						|| name.startsWith("omx.ffmpeg.")
						|| (name.startsWith("omx.sec.") && name.contains(".sw."))
						|| name.equals("omx.qcom.video.decoder.hevcswvdec")
						|| name.startsWith("c2.android.")
						|| name.startsWith("c2.google.")
						|| (!name.startsWith("omx.") && !name.startsWith("c2.")));
	}
}
