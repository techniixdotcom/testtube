package com.testtube.app.net;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.testtube.app.R;

import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException;
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException;
import org.schabi.newpipe.extractor.exceptions.ContentNotSupportedException;
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException;
import org.schabi.newpipe.extractor.exceptions.PaidContentException;
import org.schabi.newpipe.extractor.exceptions.PrivateContentException;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException;
import org.schabi.newpipe.extractor.exceptions.UnsupportedContentInCountryException;
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import javax.net.ssl.SSLException;

/**
 * What went wrong with a request, in terms the app can act on and explain: whether retrying
 * could help, after how long, and what to tell the person.
 */
public enum Failure {
	/** YouTube asked this network to confirm it isn't a bot, or answered 429. */
	SLOW_DOWN(R.string.failure_slow_down, R.string.failure_slow_down_feed, true),
	OFFLINE(R.string.failure_offline, R.string.failure_offline_feed, true),
	TIMEOUT(R.string.failure_timeout, R.string.failure_timeout_feed, true),
	AGE_RESTRICTED(R.string.failure_age_restricted, R.string.feed_error, false),
	REGION(R.string.failure_region, R.string.feed_error, false),
	PRIVATE(R.string.failure_private, R.string.feed_error, false),
	UNSUPPORTED(R.string.failure_unsupported, R.string.feed_error, false),
	UNAVAILABLE(R.string.failure_unavailable, R.string.feed_error, false),
	OTHER(R.string.failure_other, R.string.feed_error, true);

	@StringRes
	public final int message;
	/** Shorter text for an empty list that couldn't load. */
	@StringRes
	public final int feedMessage;
	/** Trying again later can work (as opposed to a private or removed video). */
	public final boolean retryable;

	Failure(@StringRes int message, @StringRes int feedMessage, boolean retryable) {
		this.message = message;
		this.feedMessage = feedMessage;
		this.retryable = retryable;
	}

	/**
	 * What to say when a playlist or queue moves past a video because of this, e.g. "Private
	 * video, skipped". Only for failures that retrying can't fix.
	 */
	@StringRes
	public int skippedMessage() {
		switch (this) {
			case AGE_RESTRICTED:
				return R.string.video_skipped_age_restricted;
			case REGION:
				return R.string.video_skipped_region;
			case PRIVATE:
				return R.string.video_skipped_private;
			case UNSUPPORTED:
				return R.string.video_skipped_unsupported;
			default:
				return R.string.video_unavailable_skipped;
		}
	}

	@NonNull
	public static Failure of(@Nullable Throwable error) {
		List<Throwable> chain = chain(error);
		// Most specific first. YouTube loves wrapping its damn bot check in a "not available" error
		if (has(chain, SignInConfirmNotBotException.class) || has(chain, ReCaptchaException.class)
						|| has(chain, RequestPacer.PausedException.class)) {
			return SLOW_DOWN;
		}
		if (has(chain, AgeRestrictedContentException.class)) return AGE_RESTRICTED;
		if (has(chain, GeographicRestrictionException.class)
						|| has(chain, UnsupportedContentInCountryException.class)) {
			return REGION;
		}
		if (has(chain, PrivateContentException.class)) return PRIVATE;
		if (has(chain, PaidContentException.class) || has(chain, YoutubeMusicPremiumContentException.class)
						|| has(chain, ContentNotSupportedException.class)) {
			return UNSUPPORTED;
		}
		if (has(chain, ContentNotAvailableException.class)) return UNAVAILABLE;
		if (has(chain, UnknownHostException.class) || has(chain, ConnectException.class)
						|| has(chain, NoRouteToHostException.class)) {
			return OFFLINE;
		}
		if (has(chain, SocketTimeoutException.class) || has(chain, SSLException.class)) {
			return TIMEOUT;
		}
		return OTHER;
	}

	/**
	 * Classifies an error the person will hear about, and reacts to YouTube's bot check: requests
	 * pause for a while and the next ones come from a new signed-out visitor, which often clears
	 * the check. A pause that is already running isn't made longer.
	 */
	@NonNull
	public static Failure report(@Nullable Throwable error) {
		Failure failure = of(error);
		if (failure == SLOW_DOWN && RequestPacer.pauseRemainingMs() == 0L) {
			RequestPacer.onSlowDown(0L);
			YoutubeCookies.startNewVisitor();
		}
		return failure;
	}

	/** Whether the error only means the work was no longer wanted (another video was picked). */
	public static boolean isCancellation(@Nullable Throwable error) {
		for (Throwable t : chain(error)) {
			if (t instanceof InterruptedException || t instanceof java.util.concurrent.CancellationException) {
				return true;
			}
			if (t instanceof java.io.InterruptedIOException && !(t instanceof SocketTimeoutException)) {
				return true;
			}
		}
		return false;
	}

	@NonNull
	private static List<Throwable> chain(@Nullable Throwable error) {
		List<Throwable> out = new ArrayList<>();
		Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		List<Throwable> pending = new ArrayList<>();
		if (error != null) pending.add(error);
		while (!pending.isEmpty()) {
			Throwable next = pending.remove(pending.size() - 1);
			if (next == null || !seen.add(next)) continue;
			out.add(next);
			pending.add(next.getCause());
			Collections.addAll(pending, next.getSuppressed());
		}
		return out;
	}

	private static boolean has(@NonNull List<Throwable> chain, @NonNull Class<? extends Throwable> type) {
		for (Throwable t : chain) {
			if (type.isInstance(t)) return true;
		}
		return false;
	}
}
