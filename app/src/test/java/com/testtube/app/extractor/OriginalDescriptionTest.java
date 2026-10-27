package com.testtube.app.extractor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.schabi.newpipe.extractor.services.youtube.OriginalDescription;

import java.util.Map;

public class OriginalDescriptionTest {
	private static final String URL = "https://www.youtube.com/watch?v=abc";

	@Test
	public void linksAreRestored() {
		String html = OriginalDescription.toHtml("Mira https://ejemplo.com/a?b=1&c=2.\n#Economía @LosEcomonos 1:05", URL);
		assertTrue(html.contains("<a href=\"https://ejemplo.com/a?b=1&amp;c=2\">"));
		assertTrue(html.contains("</a>.<br>"));
		assertTrue(html.contains("<a href=\"https://www.youtube.com/hashtag/Econom%C3%ADa\">#Economía</a>"));
		assertTrue(html.contains("<a href=\"https://www.youtube.com/@LosEcomonos\">@LosEcomonos</a>"));
		assertTrue(html.contains("<a href=\"" + URL + "&amp;t=65s\">1:05</a>"));
	}

	@Test
	public void textIsEscaped() {
		assertEquals("a &lt;b&gt; &amp; &quot;c&quot;", OriginalDescription.toHtml("a <b> & \"c\"", URL));
	}

	@Test
	public void timesThatArentTimestampsStayText() {
		assertEquals("10:30am and 3:4", OriginalDescription.toHtml("10:30am and 3:4", URL));
	}

	@Test
	public void chaptersAreReadFromTheLines() {
		Map<Integer, String> chapters = OriginalDescription.chapters(
						"Chapters:\n0:00 - Intro\n1:05 Part one\nThe end (1:02:03)\nno time here");
		assertEquals(Map.of(0, "Intro", 65, "Part one", 3723, "The end"), chapters);
	}
}
