package com.ai.logintelligence.correlation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ai.logintelligence.analysis.AnalysisRequestPublisher;
import com.ai.logintelligence.incident.Incident;
import com.ai.logintelligence.incident.IncidentService;
import com.ai.logintelligence.incident.Severity;
import com.ai.logintelligence.ingest.LogEvent;
import com.ai.logintelligence.ingest.LogLevel;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class IncidentCorrelatorTest {

	/** In-memory stand-in for Redis with the same claim/release semantics. */
	private static final class InMemorySignatureStore implements SignatureStore {

		final Map<String, UUID> bindings = new HashMap<>();
		int touches;

		@Override
		public Optional<UUID> find(String fingerprint) {
			return Optional.ofNullable(bindings.get(fingerprint));
		}

		@Override
		public boolean claim(String fingerprint, UUID incidentId) {
			return bindings.putIfAbsent(fingerprint, incidentId) == null;
		}

		@Override
		public void touch(String fingerprint) {
			touches++;
		}

		@Override
		public void release(String fingerprint, UUID incidentId) {
			bindings.remove(fingerprint, incidentId);
		}
	}

	private static final Signature SIGNATURE = new Signature("fp-1", "Order <n> failed", null);

	private final InMemorySignatureStore signatures = new InMemorySignatureStore();
	private final IncidentService incidents = mock(IncidentService.class);
	private final AnalysisRequestPublisher analysisRequests = mock(AnalysisRequestPublisher.class);
	private IncidentCorrelator correlator;

	@BeforeEach
	void setUp() {
		correlator = new IncidentCorrelator(signatures, incidents, analysisRequests, new SimpleMeterRegistry());
		when(incidents.open(any(), any(), any())).thenAnswer(call -> Incident.open(call.getArgument(0), "fp-1",
				"order-service", "Order <n> failed", null, LogLevel.ERROR, Severity.MEDIUM, 1, Instant.now(),
				Instant.now()));
	}

	private static List<LogEvent> events(int count) {
		return java.util.stream.IntStream.range(0, count).mapToObj(i -> new LogEvent(UUID.randomUUID(),
				Instant.parse("2026-11-13T10:00:00Z").plusSeconds(i), "order-service", "node-1", LogLevel.ERROR,
				"Order " + i + " failed", null, null)).toList();
	}

	@Test
	void firstEventsOpenAnIncidentAndRequestAnalysisOnce() {
		correlator.correlate(SIGNATURE, events(3));

		UUID id = signatures.bindings.get("fp-1");
		assertThat(id).isNotNull();
		verify(incidents).open(eq(id), eq(SIGNATURE), any());
		verify(analysisRequests).request(id, "fp-1", false);
	}

	@Test
	void laterEventsOnlyIncrementTheOpenIncident() {
		UUID existing = UUID.randomUUID();
		signatures.bindings.put("fp-1", existing);
		when(incidents.recordOccurrences(eq(existing), anyLong(), any())).thenReturn(1);

		correlator.correlate(SIGNATURE, events(4));

		verify(incidents).recordOccurrences(existing, 4, Instant.parse("2026-11-13T10:00:03Z"));
		verify(incidents, never()).open(any(), any(), any());
		verify(analysisRequests, never()).request(any(), any(), anyBoolean());
		assertThat(signatures.touches).isEqualTo(1);
	}

	@Test
	void recurrenceAfterResolutionOpensANewIncident() {
		UUID resolved = UUID.randomUUID();
		signatures.bindings.put("fp-1", resolved);
		when(incidents.recordOccurrences(eq(resolved), anyLong(), any())).thenReturn(0);

		correlator.correlate(SIGNATURE, events(1));

		UUID reopened = signatures.bindings.get("fp-1");
		assertThat(reopened).isNotNull().isNotEqualTo(resolved);
		verify(incidents).open(eq(reopened), eq(SIGNATURE), any());
		verify(analysisRequests).request(reopened, "fp-1", false);
	}

	@Test
	void failedPersistReleasesTheClaimSoARetryCanSucceed() {
		when(incidents.open(any(), any(), any())).thenThrow(new IllegalStateException("database down"));

		assertThatThrownBy(() -> correlator.correlate(SIGNATURE, events(1))).isInstanceOf(IllegalStateException.class);

		assertThat(signatures.bindings).isEmpty();
		verify(analysisRequests, never()).request(any(), any(), anyBoolean());
	}
}
