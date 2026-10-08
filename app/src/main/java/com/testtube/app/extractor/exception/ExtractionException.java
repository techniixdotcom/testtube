package com.testtube.app.extractor.exception;

import androidx.annotation.NonNull;

public class ExtractionException extends RuntimeException {
	public ExtractionException(@NonNull String message, @NonNull Throwable cause) {
		super(message, cause);
	}
}
