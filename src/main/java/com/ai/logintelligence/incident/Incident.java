package com.ai.logintelligence.incident;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.DynamicUpdate;

import com.ai.logintelligence.analysis.AnalysisResult;
import com.ai.logintelligence.ingest.LogLevel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A group of log events sharing one error signature inside the correlation window.
 * <p>
 * {@code @DynamicUpdate} matters here: the correlator bumps {@code occurrenceCount}
 * with a bulk UPDATE while the analyzer saves verdict columns through the entity.
 * Writing only the changed columns keeps the two from overwriting each other.
 */
@Entity
@Table(name = "incident")
@DynamicUpdate
public class Incident {

	@Id
	private UUID id;

	@Column(nullable = false, updatable = false, length = 64)
	private String fingerprint;

	@Column(nullable = false, updatable = false, length = 100)
	private String service;

	@Column(nullable = false, updatable = false, length = 300)
	private String title;

	@Column(updatable = false, length = 300)
	private String exceptionType;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false, length = 10)
	private LogLevel level;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private IncidentStatus status;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Severity severity;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 40)
	private RootCauseCategory rootCauseCategory;

	private String rootCauseSummary;

	private String suggestedAction;

	private Double confidence;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AnalysisStatus analysisStatus;

	@Column(length = 100)
	private String analyzedBy;

	private Instant analyzedAt;

	/** Maintained by {@link IncidentRepository#addOccurrences}; never written through the entity. */
	@Column(nullable = false, updatable = false)
	private long occurrenceCount;

	@Column(nullable = false, updatable = false)
	private Instant firstSeen;

	/** Maintained by {@link IncidentRepository#addOccurrences}; never written through the entity. */
	@Column(nullable = false, updatable = false)
	private Instant lastSeen;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	private Instant resolvedAt;

	protected Incident() {
	}

	public static Incident open(UUID id, String fingerprint, String service, String title, String exceptionType,
			LogLevel level, Severity severity, long occurrences, Instant firstSeen, Instant lastSeen) {
		Incident i = new Incident();
		i.id = id;
		i.fingerprint = fingerprint;
		i.service = service;
		i.title = title;
		i.exceptionType = exceptionType;
		i.level = level;
		i.status = IncidentStatus.OPEN;
		i.severity = severity;
		i.rootCauseCategory = RootCauseCategory.UNKNOWN;
		i.analysisStatus = AnalysisStatus.PENDING;
		i.occurrenceCount = occurrences;
		i.firstSeen = firstSeen;
		i.lastSeen = lastSeen;
		i.createdAt = Instant.now();
		return i;
	}

	public void applyAnalysis(AnalysisResult result, Instant at) {
		this.rootCauseCategory = result.category();
		this.severity = result.severity();
		this.rootCauseSummary = result.summary();
		this.suggestedAction = result.suggestedAction();
		this.confidence = result.confidence();
		this.analyzedBy = result.analyzedBy();
		this.analysisStatus = AnalysisStatus.COMPLETED;
		this.analyzedAt = at;
	}

	public void markAnalysisPending() {
		this.analysisStatus = AnalysisStatus.PENDING;
	}

	public void changeStatus(IncidentStatus newStatus, Instant at) {
		this.status = newStatus;
		this.resolvedAt = newStatus == IncidentStatus.RESOLVED ? at : null;
	}

	public UUID getId() {
		return id;
	}

	public String getFingerprint() {
		return fingerprint;
	}

	public String getService() {
		return service;
	}

	public String getTitle() {
		return title;
	}

	public String getExceptionType() {
		return exceptionType;
	}

	public LogLevel getLevel() {
		return level;
	}

	public IncidentStatus getStatus() {
		return status;
	}

	public Severity getSeverity() {
		return severity;
	}

	public RootCauseCategory getRootCauseCategory() {
		return rootCauseCategory;
	}

	public String getRootCauseSummary() {
		return rootCauseSummary;
	}

	public String getSuggestedAction() {
		return suggestedAction;
	}

	public Double getConfidence() {
		return confidence;
	}

	public AnalysisStatus getAnalysisStatus() {
		return analysisStatus;
	}

	public String getAnalyzedBy() {
		return analyzedBy;
	}

	public Instant getAnalyzedAt() {
		return analyzedAt;
	}

	public long getOccurrenceCount() {
		return occurrenceCount;
	}

	public Instant getFirstSeen() {
		return firstSeen;
	}

	public Instant getLastSeen() {
		return lastSeen;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getResolvedAt() {
		return resolvedAt;
	}
}
