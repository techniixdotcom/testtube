package com.testtube.app.player.controller.gesture;

/** Moves the open video towards the bar while dragged. */
public interface WatchDrag {
	/** @return false if the video can't go to the bar right now */
	boolean begin();

	/** @param dy px moved down since {@link #begin()} */
	void moveBy(float dy);

	/** @param velocityY px/s, positive down */
	void release(float velocityY);

	/**
	 * Grows the minimized video back with the same animation.
	 * @return false if that can't happen now (caller restores without it)
	 */
	boolean expand();
}
