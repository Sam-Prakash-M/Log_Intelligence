package com.ai.logintelligence.incident;

import java.time.Instant;
import java.util.UUID;

import com.ai.logintelligence.ingest.LogEvent;
import com.ai.logintelligence.ingest.LogLevel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One of the first raw events of an incident, kept as evidence for analysis and the UI. */
@Entity
@Table(name = "incident_sample")
public class IncidentSample {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private UUID incidentId;

	private UUID eventId;

	@Column(nullable = false)
	private Instant occurredAt;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private LogLevel level;

	@Column(length = 200)
	private String host;

	@Column(length = 64)
	private String traceId;

	@Column(nullable = false)
	private String message;

	private String stackTrace;

	protected IncidentSample() {
	}

	public static IncidentSample of(UUID incidentId, LogEvent event) {
		IncidentSample s = new IncidentSample();
		s.incidentId = incidentId;
		s.eventId = event.eventId();
		s.occurredAt = event.timestamp();
		s.level = event.level();
		s.host = event.host();
		s.traceId = event.traceId();
		s.message = event.message();
		s.stackTrace = event.stackTrace();
		return s;
	}

	public UUID getIncidentId() {
		return incidentId;
	}

	public UUID getEventId() {
		return eventId;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}

	public LogLevel getLevel() {
		return level;
	}

	public String getHost() {
		return host;
	}

	public String getTraceId() {
		return traceId;
	}

	public String getMessage() {
		return message;
	}

	public String getStackTrace() {
		return stackTrace;
	}
}
