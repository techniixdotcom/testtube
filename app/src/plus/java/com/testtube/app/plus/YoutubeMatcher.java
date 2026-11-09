package com.testtube.app.plus;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.testtube.app.extractor.FeedItem;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Picks the YouTube video that is the Spotify song: title words and artist in the video, about
 * the same length, not a cover, live take or remix unless the song is one.
 */
final class YoutubeMatcher {
	/** Below this it's probably another song, show the search results instead. */
	static final int MIN_SCORE = 40;
	private static final Pattern MARKS = Pattern.compile("\\p{M}+");
	private static final Pattern NOT_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
	/** "Song - Remastered 2011", "Song (feat. Someone)": the part naming the song. */
	private static final Pattern TITLE_EXTRA = Pattern.compile(
					"\\s+-\\s+.*$|\\s*[(\\[](?:feat|ft|with|from|remaster)[^)\\]]*[)\\]]", Pattern.CASE_INSENSITIVE);
	/** Versions nobody means when they send a song, unless its title says so. */
	private static final String[][] OTHER_VERSIONS = {
					{"live"}, {"cover"}, {"remix"}, {"karaoke"}, {"instrumental"}, {"8d"}, {"slowed"},
					{"sped", "up"}, {"reverb"}, {"nightcore"}, {"reaction"}, {"acoustic"}, {"tutorial"},
					{"lesson"}, {"mashup"}};

	private YoutubeMatcher() {
	}

	/** Best match in the results, or null if nothing's close enough. */
	@Nullable
	static FeedItem best(@NonNull List<FeedItem> results, @NonNull SpotifyItem.Track track) {
		FeedItem best = null;
		int bestScore = Integer.MIN_VALUE;
		int rank = 0;
		for (FeedItem item : results) {
			if (item.kind() != FeedItem.Kind.VIDEO || item.videoId() == null || item.live() || item.shortForm()) {
				continue;
			}
			int score = score(item, track, rank++);
			if (score > bestScore) {
				bestScore = score;
				best = item;
			}
		}
		return bestScore >= MIN_SCORE ? best : null;
	}

	static int score(@NonNull FeedItem item, @NonNull SpotifyItem.Track track, int rank) {
		Set<String> videoWords = words(item.title());
		String author = item.author() != null ? item.author() : "";
		Set<String> authorWords = words(author);
		Set<String> songWords = words(TITLE_EXTRA.matcher(track.title()).replaceAll(""));
		if (songWords.isEmpty()) songWords = words(track.title());
		Set<String> fullSongWords = words(track.title());

		int score = 0;
		// song name is most of the score; titles without spaces (Japanese, Chinese…) compared whole
		// instead of word by word
		int titleScore = 0;
		if (!songWords.isEmpty()) {
			int found = 0;
			for (String word : songWords) if (videoWords.contains(word)) found++;
			titleScore = 45 * found / songWords.size();
		}
		String songCompact = compact(TITLE_EXTRA.matcher(track.title()).replaceAll(""));
		if (songCompact.length() >= 2 && compact(item.title()).contains(songCompact)) titleScore = 45;
		score += titleScore;
		// which version: "Song - Major Lazer Remix" should find that remix, not the original
		Set<String> versionWords = new HashSet<>(fullSongWords);
		versionWords.removeAll(songWords);
		if (!versionWords.isEmpty()) {
			int found = 0;
			for (String word : versionWords) if (videoWords.contains(word)) found++;
			score += 15 * found / versionWords.size();
		}
		// main artist in the title or as the channel ("Artist - Topic", "ArtistVEVO")
		String mainArtist = track.artist() == null ? null : track.artist().split(",")[0].trim();
		if (mainArtist != null && !mainArtist.isEmpty()) {
			Set<String> artistWords = words(mainArtist);
			if (!artistWords.isEmpty()) {
				int inTitle = 0;
				int inAuthor = 0;
				for (String word : artistWords) {
					if (videoWords.contains(word)) inTitle++;
					if (authorWords.contains(word) || normalize(author).replace(" ", "").contains(word)) inAuthor++;
				}
				score += 25 * Math.max(inTitle, inAuthor) / artistWords.size();
				if (inAuthor == artistWords.size() && author.endsWith(" - Topic")) score += 10;
			}
		}
		// same length: a music video intro adds a little, a live set or loop a lot
		long seconds = track.durationMs() / 1000L;
		if (seconds > 0L && item.durationSeconds() > 0L) {
			long diff = Math.abs(item.durationSeconds() - seconds);
			if (diff <= 3L) score += 25;
			else if (diff <= 10L) score += 15;
			else if (diff <= 30L) score += 5;
			else if (diff > 90L) score -= 30;
			else if (diff > 45L) score -= 10;
		}
		// another version of the song
		for (String[] version : OTHER_VERSIONS) {
			if (containsAll(videoWords, version) && !containsAll(fullSongWords, version)) score -= 25;
		}
		// YouTube's order counts a little
		score += Math.max(0, 8 - 2 * rank);
		return score;
	}

	private static boolean containsAll(@NonNull Set<String> words, @NonNull String[] wanted) {
		for (String word : wanted) if (!words.contains(word)) return false;
		return true;
	}

	@NonNull
	private static Set<String> words(@NonNull String text) {
		Set<String> words = new HashSet<>();
		for (String word : NOT_WORD.split(normalize(text))) {
			if (!word.isEmpty()) words.add(word);
		}
		return words;
	}

	/** Letters and digits only, normalized: "Ain't" equals "aint". */
	@NonNull
	private static String compact(@NonNull String text) {
		return NOT_WORD.matcher(normalize(text)).replaceAll("");
	}

	@NonNull
	private static String normalize(@NonNull String text) {
		String decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD);
		return MARKS.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT);
	}
}
