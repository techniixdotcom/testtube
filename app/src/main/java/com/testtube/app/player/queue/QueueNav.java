package com.testtube.app.player.queue;

/** Which next/previous buttons work right now. */
public record QueueNav(boolean next,
                       boolean queuePrev,
                       boolean prev) {
	public static final QueueNav INACTIVE = new QueueNav(false, false, false);

	public boolean isNextActionEnabled() {
		return next;
	}

	public boolean isPreviousActionEnabled() {
		return queuePrev || prev;
	}
}
