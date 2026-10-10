package com.testtube.app.extractor;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Test;
import org.schabi.newpipe.extractor.utils.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Counts and dates as YouTube writes them in the content language. */
public class ContentLanguageTest {
	@After
	public void backToEnglish() {
		Utils.setNumberWords(Map.of());
	}

	@Test
	public void readsEnglishCounts() throws Exception {
		assertEquals(1_200L, Utils.mixedNumberWordToLong("1.2K views"));
		assertEquals(3_400_000L, Utils.mixedNumberWordToLong("3.4M views"));
		assertEquals(1_234L, Utils.mixedNumberWordToLong("1,234 views"));
	}

	@Test
	public void readsSpanishCounts() throws Exception {
		Utils.setNumberWords(Map.of("mil", 1_000L, "M", 1_000_000L, "mil M", 1_000_000_000L,
						"B", 1_000_000_000_000L));
		assertEquals(1_200_000L, Utils.mixedNumberWordToLong("1,2 M de visualizaciones"));
		assertEquals(345_000L, Utils.mixedNumberWordToLong("345 mil visualizaciones"));
		assertEquals(1_200_000_000L, Utils.mixedNumberWordToLong("1,2 mil M de visualizaciones"));
		assertEquals(1_234L, Utils.mixedNumberWordToLong("1.234 visualizaciones"));
	}

	@Test
	public void readsWordsThatDifferPerLanguage() throws Exception {
		Utils.setNumberWords(Map.of("B", 1_000L, "Mn", 1_000_000L)); // Turkish: B is a thousand
		assertEquals(1_200L, Utils.mixedNumberWordToLong("1,2 B görüntüleme"));
		Utils.setNumberWords(Map.of("万", 10_000L, "億", 100_000_000L));
		assertEquals(12_000L, Utils.mixedNumberWordToLong("1.2万 回視聴"));
	}

	@Test
	public void sortsByPublishedDate() {
		List<FeedItem> items = new ArrayList<>(List.of(video("a", "3 days ago"), video("b", "2 hours ago")));
		FeedItem.sortNewestFirst(items);
		assertEquals("b", items.get(0).videoId());
	}

	@Test
	public void keepsTheOrderWhenADateCannotBeRead() {
		List<FeedItem> items = new ArrayList<>(List.of(video("a", "3 days ago"), video("b", "soon")));
		FeedItem.sortNewestFirst(items);
		assertEquals("a", items.get(0).videoId());
	}

	private static FeedItem video(String id, String published) {
		return new FeedItem(FeedItem.Kind.VIDEO, "https://www.youtube.com/watch?v=" + id, id, id, null, null,
						null, 60L, 1L, published, false);
	}
}
