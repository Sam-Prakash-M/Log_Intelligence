package com.ai.logintelligence.ingest;

import java.time.Instant;
import java.util.UUID;

/** The message carried on the raw-logs topic (JSON, keyed by service name). */
public record LogEvent(UUID eventId, Instant timestamp, String service, String host, LogLevel level,
		String message, String stackTrace, String traceId) {

	public boolean isWellFormed() {
		return timestamp != null && service != null && !service.isBlank() && level != null && message != null
				&& !message.isBlank();
	}
}
