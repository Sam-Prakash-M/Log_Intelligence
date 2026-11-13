package com.ai.logintelligence.incident;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ai.logintelligence.analysis.AnalysisResult;
import com.ai.logintelligence.analysis.HeuristicIncidentAnalyzer;
import com.ai.logintelligence.config.PipelineProperties;
import com.ai.logintelligence.correlation.Signature;
import com.ai.logintelligence.correlation.SignatureStore;
import com.ai.logintelligence.ingest.LogEvent;
import com.ai.logintelligence.ingest.LogLevel;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;

@Service
public class IncidentService {

	private final IncidentRepository incidents;
	private final IncidentSampleRepository samples;
	private final SignatureStore signatures;
	private final EntityManager entityManager;
	private final int sampleLimit;

	public IncidentService(IncidentRepository incidents, IncidentSampleRepository samples,
			SignatureStore signatures, EntityManager entityManager, PipelineProperties props) {
		this.incidents = incidents;
		this.samples = samples;
		this.signatures = signatures;
		this.entityManager = entityManager;
		this.sampleLimit = props.correlation().sampleLimit();
	}

	/** Creates the incident from the first batch of events that carried its signature. */
	@Transactional
	public Incident open(UUID id, Signature signature, List<LogEvent> events) {
		LogEvent first = events.getFirst();
		LogLevel worst = events.stream().map(LogEvent::level).max(Comparator.naturalOrder()).orElseThrow();
		Instant firstSeen = events.stream().map(LogEvent::timestamp).min(Comparator.naturalOrder()).orElseThrow();
		Instant lastSeen = events.stream().map(LogEvent::timestamp).max(Comparator.naturalOrder()).orElseThrow();

		Incident incident = Incident.open(id, signature.fingerprint(), first.service(), signature.template(),
				signature.exceptionType(), worst, HeuristicIncidentAnalyzer.severityFor(worst, events.size()),
				events.size(), firstSeen, lastSeen);
		// persist(), not save(): the id is pre-assigned, and save() would issue a needless SELECT first
		entityManager.persist(incident);
		events.stream().limit(sampleLimit).forEach(e -> entityManager.persist(IncidentSample.of(id, e)));
		return incident;
	}

	@Transactional
	public int recordOccurrences(UUID id, long count, Instant lastSeen) {
		return incidents.addOccurrences(id, count, lastSeen);
	}

	@Transactional
	public void applyAnalysis(UUID id, AnalysisResult result) {
		incidents.findById(id).ifPresent(incident -> incident.applyAnalysis(result, Instant.now()));
	}

	@Transactional
	public Optional<Incident> markAnalysisPending(UUID id) {
		Optional<Incident> incident = incidents.findById(id);
		incident.ifPresent(Incident::markAnalysisPending);
		return incident;
	}

	/**
	 * Resolving also frees the signature, so a recurrence opens a new incident
	 * instead of silently counting against a closed one.
	 */
	@Transactional
	public Optional<Incident> changeStatus(UUID id, IncidentStatus status) {
		Optional<Incident> incident = incidents.findById(id);
		incident.ifPresent(i -> {
			i.changeStatus(status, Instant.now());
			if (status == IncidentStatus.RESOLVED) {
				signatures.release(i.getFingerprint(), i.getId());
			}
		});
		return incident;
	}

	@Transactional
	public int resolveStale(Instant cutoff) {
		return incidents.resolveStale(cutoff, Instant.now());
	}

	@Transactional(readOnly = true)
	public Optional<Incident> find(UUID id) {
		return incidents.findById(id);
	}

	@Transactional(readOnly = true)
	public List<IncidentSample> samplesOf(UUID id) {
		return samples.findByIncidentIdOrderByOccurredAtAsc(id);
	}

	@Transactional(readOnly = true)
	public Page<Incident> search(IncidentStatus status, Severity severity, String service, Pageable pageable) {
		Specification<Incident> spec = (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (status != null) {
				predicates.add(cb.equal(root.get("status"), status));
			}
			if (severity != null) {
				predicates.add(cb.equal(root.get("severity"), severity));
			}
			if (service != null && !service.isBlank()) {
				predicates.add(cb.equal(root.get("service"), service.strip()));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
		return incidents.findAll(spec, pageable);
	}
}
