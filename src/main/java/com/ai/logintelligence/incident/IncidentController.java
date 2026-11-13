package com.ai.logintelligence.incident;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.ai.logintelligence.analysis.AnalysisRequestPublisher;
import com.ai.logintelligence.ingest.LogLevel;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentController {

	private static final int MAX_PAGE_SIZE = 200;

	private final IncidentService incidents;
	private final AnalysisRequestPublisher analysisRequests;

	public IncidentController(IncidentService incidents, AnalysisRequestPublisher analysisRequests) {
		this.incidents = incidents;
		this.analysisRequests = analysisRequests;
	}

	@GetMapping
	public PageView<IncidentView> list(@RequestParam(required = false) IncidentStatus status,
			@RequestParam(required = false) Severity severity, @RequestParam(required = false) String service,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
		var pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
				Sort.by(Sort.Direction.DESC, "lastSeen"));
		Page<Incident> result = incidents.search(status, severity, service, pageable);
		return new PageView<>(result.map(IncidentView::of).getContent(), result.getNumber(), result.getSize(),
				result.getTotalElements(), result.getTotalPages());
	}

	@GetMapping("/{id}")
	public IncidentDetail get(@PathVariable UUID id) {
		Incident incident = incidents.find(id).orElseThrow(() -> notFound(id));
		return new IncidentDetail(IncidentView.of(incident),
				incidents.samplesOf(id).stream().map(SampleView::of).toList());
	}

	@PatchMapping("/{id}/status")
	public IncidentView changeStatus(@PathVariable UUID id, @Valid @RequestBody StatusChange change) {
		return incidents.changeStatus(id, change.status()).map(IncidentView::of).orElseThrow(() -> notFound(id));
	}

	/** Queues a fresh analysis that bypasses the verdict cache. */
	@PostMapping("/{id}/reanalyze")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public IncidentView reanalyze(@PathVariable UUID id) {
		Incident incident = incidents.markAnalysisPending(id).orElseThrow(() -> notFound(id));
		analysisRequests.request(id, incident.getFingerprint(), true);
		return IncidentView.of(incident);
	}

	private static ResponseStatusException notFound(UUID id) {
		return new ResponseStatusException(HttpStatus.NOT_FOUND, "No incident with id " + id);
	}

	public record StatusChange(@NotNull IncidentStatus status) {
	}

	public record PageView<T>(List<T> items, int page, int size, long totalItems, int totalPages) {
	}

	public record IncidentView(UUID id, String fingerprint, String service, String title, String exceptionType,
			LogLevel level, IncidentStatus status, Severity severity, RootCauseCategory rootCauseCategory,
			String rootCauseSummary, String suggestedAction, Double confidence, AnalysisStatus analysisStatus,
			String analyzedBy, Instant analyzedAt, long occurrenceCount, Instant firstSeen, Instant lastSeen,
			Instant resolvedAt) {

		static IncidentView of(Incident i) {
			return new IncidentView(i.getId(), i.getFingerprint(), i.getService(), i.getTitle(),
					i.getExceptionType(), i.getLevel(), i.getStatus(), i.getSeverity(), i.getRootCauseCategory(),
					i.getRootCauseSummary(), i.getSuggestedAction(), i.getConfidence(), i.getAnalysisStatus(),
					i.getAnalyzedBy(), i.getAnalyzedAt(), i.getOccurrenceCount(), i.getFirstSeen(), i.getLastSeen(),
					i.getResolvedAt());
		}
	}

	public record SampleView(UUID eventId, Instant timestamp, LogLevel level, String host, String traceId,
			String message, String stackTrace) {

		static SampleView of(IncidentSample s) {
			return new SampleView(s.getEventId(), s.getOccurredAt(), s.getLevel(), s.getHost(), s.getTraceId(),
					s.getMessage(), s.getStackTrace());
		}
	}

	public record IncidentDetail(IncidentView incident, List<SampleView> samples) {
	}
}
