package com.testtube.app.extractor.exception;

import androidx.annotation.NonNull;

public final class LoginRequiredExtractionException extends ExtractionException {
	/** YouTube's bot check is per network, so signing in does not get past it here. */
	public static final String MESSAGE =
					"YouTube is asking this network to confirm it isn't a bot. Try again in a minute, "
									+ "or switch between Wi-Fi and mobile data.";

	public LoginRequiredExtractionException(@NonNull Throwable cause) {
		super(MESSAGE, cause);
	}
}
