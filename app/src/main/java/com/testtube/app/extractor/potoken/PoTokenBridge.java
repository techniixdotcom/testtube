package com.testtube.app.extractor.potoken;

import android.webkit.JavascriptInterface;

import androidx.annotation.NonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;


/**
 * Bridge between the WebView and PoToken extraction flow.
 */
public final class PoTokenBridge {
	public static final String JS_INTERFACE = "TestTubePoTokenBridge";

	@NonNull
	private final ConcurrentMap<String, CompletableFuture<String>> pendingRequests =
					new ConcurrentHashMap<>();

	public PoTokenBridge() {
	}

	@NonNull
	public CompletableFuture<String> prepare(@NonNull String requestId) {
		CompletableFuture<String> future = new CompletableFuture<>();
		CompletableFuture<String> previous = pendingRequests.put(requestId, future);
		if (previous != null) {
			previous.cancel(true);
		}
		return future;
	}

	@JavascriptInterface
	public void onSuccess(@NonNull String requestId, @NonNull String value) {
		CompletableFuture<String> future = pendingRequests.remove(requestId);
		if (future != null) {
			future.complete(value);
		}
	}

	@JavascriptInterface
	public void onError(@NonNull String requestId, @NonNull String error) {
		CompletableFuture<String> future = pendingRequests.remove(requestId);
		if (future != null) {
			future.completeExceptionally(new IllegalStateException(error));
		}
	}
}
