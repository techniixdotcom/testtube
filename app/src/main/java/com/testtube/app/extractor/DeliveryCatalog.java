package com.testtube.app.extractor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.stream.StreamType;

import java.util.ArrayList;
import java.util.List;


/**
 * Catalog of playback delivery candidates.
 */
public class DeliveryCatalog {
	@NonNull
	private StreamType streamType = StreamType.VIDEO_STREAM;
	@NonNull
	private List<Delivery> items = new ArrayList<>();

	@Nullable
	public Delivery first(@NonNull PlaybackMode mode) {
		for (Delivery delivery : items) {
			if (delivery.getMode() == mode) {
				return delivery;
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
	public List<Delivery> getItems() {
		return items;
	}

	public void setItems(@NonNull List<Delivery> items) {
		this.items = items;
	}
}
