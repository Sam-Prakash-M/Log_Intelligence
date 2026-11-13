package com.ai.logintelligence.correlation;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.ai.logintelligence.analysis.AnalysisRequestPublisher;
import com.ai.logintelligence.incident.Incident;
import com.ai.logintelligence.incident.IncidentService;
import com.ai.logintelligence.ingest.LogEvent;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Signature dedup: the first events of a signature open an incident and request
 * analysis; every later event inside the window only increments its counter.
 */
@Service
public class IncidentCorrelator {

	private static final Logger log = LoggerFactory.getLogger(IncidentCorrelator.class);

	private final SignatureStore signatures;
	private final IncidentService incidents;
	private final AnalysisRequestPublisher analysisRequests;
	private final MeterRegistry metrics;

	public IncidentCorrelator(SignatureStore signatures, IncidentService incidents,
			AnalysisRequestPublisher analysisRequests, MeterRegistry metrics) {
		this.signatures = signatures;
		this.incidents = incidents;
		this.analysisRequests = analysisRequests;
		this.metrics = metrics;
	}

	/** @param events one or more events that all produced {@code signature} */
	public void correlate(Signature signature, List<LogEvent> events) {
		String fingerprint = signature.fingerprint();
		Instant lastSeen = events.stream().map(LogEvent::timestamp).max(Comparator.naturalOrder()).orElseThrow();

		Optional<UUID> bound = signatures.find(fingerprint);
		if (bound.isPresent()) {
			if (incidents.recordOccurrences(bound.get(), events.size(), lastSeen) > 0) {
				signatures.touch(fingerprint);
				return;
			}
			// Bound to an incident that was resolved or no longer exists: start over.
			signatures.release(fingerprint, bound.get());
		}

		UUID id = UUID.randomUUID();
		if (!signatures.claim(fingerprint, id)) {
			// Another consumer opened the incident between our lookup and our claim.
			signatures.find(fingerprint)
					.ifPresent(winner -> incidents.recordOccurrences(winner, events.size(), lastSeen));
			return;
		}

		Incident incident;
		try {
			incident = incidents.open(id, signature, events);
		}
		catch (RuntimeException ex) {
			signatures.release(fingerprint, id);
			throw ex;
		}
		metrics.counter("logintel.incidents.opened", "severity", incident.getSeverity().name()).increment();
		log.info("Opened incident {} [{}] {}: {}", id, incident.getSeverity(), incident.getService(),
				incident.getTitle());
		analysisRequests.request(id, fingerprint, false);
	}
}
