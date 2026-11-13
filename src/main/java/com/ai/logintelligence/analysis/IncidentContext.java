package com.ai.logintelligence.analysis;

import java.time.Instant;
import java.util.List;

import com.ai.logintelligence.incident.Incident;
import com.ai.logintelligence.incident.IncidentSample;
import com.ai.logintelligence.ingest.LogLevel;

/** Everything an analyzer may look at, detached from persistence. */
public record IncidentContext(String service, String title, String exceptionType, LogLevel level,
		long occurrenceCount, Instant firstSeen, Instant lastSeen, List<Sample> samples) {

	public record Sample(Instant timestamp, LogLevel level, String host, String message, String stackTrace) {
	}

	public static IncidentContext of(Incident incident, List<IncidentSample> samples) {
		return new IncidentContext(incident.getService(), incident.getTitle(), incident.getExceptionType(),
				incident.getLevel(), incident.getOccurrenceCount(), incident.getFirstSeen(), incident.getLastSeen(),
				samples.stream().map(s -> new Sample(s.getOccurredAt(), s.getLevel(), s.getHost(), s.getMessage(),
						s.getStackTrace())).toList());
	}
}
