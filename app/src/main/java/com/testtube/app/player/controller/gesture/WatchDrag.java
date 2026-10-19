package com.testtube.app.player.controller.gesture;

/** Moves the open video down towards the bottom bar while a finger drags it. */
public interface WatchDrag {
	/** @return false when the video can't go to the bottom bar right now */
	boolean begin();

	/** @param dy pixels the finger moved down since {@link #begin()} */
	void moveBy(float dy);

	/** @param velocityY pixels per second, positive downwards */
	void release(float velocityY);
}
