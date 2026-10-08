package com.testtube.app.player.queue;

/** Works out which queue actions (next, previous, shuffle) are available right now. */
public record QueueNav(boolean queue,
                       boolean next,
                       boolean shuffle,
                       boolean queuePrev,
                       boolean prev) {
	public static final QueueNav INACTIVE = new QueueNav(false, false, false, false, false);

	public boolean isNextActionEnabled() {
		return next;
	}

	public boolean isPreviousActionEnabled() {
		return queuePrev || prev;
	}
}
