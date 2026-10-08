package com.testtube.app.player.queue;

@FunctionalInterface
public interface QueueInvalidationListener {
	void onQueueInvalidated();
}
