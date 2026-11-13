package com.ai.logintelligence.ingest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Request body of {@code POST /api/v1/logs}. */
public record LogBatch(@NotEmpty @Size(max = 1000) List<@Valid @NotNull Entry> events) {

	public record Entry(
			@NotBlank @Size(max = 100) String service,
			@Size(max = 200) String host,
			@NotNull LogLevel level,
			@NotBlank @Size(max = 8000) String message,
			@Size(max = 32000) String stackTrace,
			@Size(max = 64) String traceId,
			Instant timestamp) {

		LogEvent toEvent(Instant receivedAt) {
			return new LogEvent(UUID.randomUUID(), timestamp != null ? timestamp : receivedAt, service.strip(), host,
					level, message, stackTrace, traceId);
		}
	}
}
