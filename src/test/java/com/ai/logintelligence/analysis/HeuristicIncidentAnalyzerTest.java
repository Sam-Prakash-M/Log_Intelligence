package com.ai.logintelligence.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.ai.logintelligence.incident.RootCauseCategory;
import com.ai.logintelligence.incident.Severity;
import com.ai.logintelligence.ingest.LogLevel;

class HeuristicIncidentAnalyzerTest {

	private final HeuristicIncidentAnalyzer analyzer = new HeuristicIncidentAnalyzer();

	private static IncidentContext incident(String title, String exceptionType, LogLevel level, long occurrences,
			String stackTrace) {
		Instant now = Instant.parse("2026-11-13T10:00:00Z");
		return new IncidentContext("order-service", title, exceptionType, level, occurrences, now, now,
				List.of(new IncidentContext.Sample(now, level, "node-1", title, stackTrace)));
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			could not acquire connection from pool within <n>ms   | DATABASE
			Worker <n> terminated: Java heap space                | RESOURCE_EXHAUSTION
			Authentication failed for user <str>: token expired   | AUTHENTICATION
			Upstream inventory-service returned HTTP 503          | DEPENDENCY_FAILURE
			Could not deliver message <n>: Connection refused     | NETWORK
			could not resolve placeholder <str>                   | CONFIGURATION
			Rejected query <n>: malformed filter expression       | DATA_VALIDATION
			Something nobody has seen before                      | UNKNOWN
			""")
	void tagsByKeyword(String title, RootCauseCategory expected) {
		assertThat(analyzer.analyze(incident(title, null, LogLevel.ERROR, 1, null)).category()).isEqualTo(expected);
	}

	@Test
	void readsTheStackTraceWhenTheMessageSaysNothing() {
		AnalysisResult result = analyzer.analyze(incident("Unhandled exception while processing order <n>",
				"java.lang.NullPointerException", LogLevel.ERROR, 3, "java.lang.NullPointerException: address is null"));

		assertThat(result.category()).isEqualTo(RootCauseCategory.CODE_DEFECT);
		assertThat(result.analyzedBy()).isEqualTo(HeuristicIncidentAnalyzer.NAME);
	}

	@Test
	void severityFollowsLevelAndVolume() {
		assertThat(HeuristicIncidentAnalyzer.severityFor(LogLevel.FATAL, 1)).isEqualTo(Severity.CRITICAL);
		assertThat(HeuristicIncidentAnalyzer.severityFor(LogLevel.ERROR, 5)).isEqualTo(Severity.MEDIUM);
		assertThat(HeuristicIncidentAnalyzer.severityFor(LogLevel.ERROR, 100)).isEqualTo(Severity.HIGH);
		assertThat(HeuristicIncidentAnalyzer.severityFor(LogLevel.WARN, 5)).isEqualTo(Severity.LOW);
		assertThat(HeuristicIncidentAnalyzer.severityFor(LogLevel.WARN, 500)).isEqualTo(Severity.MEDIUM);
	}

	@Test
	void systemicCategoriesAreAtLeastHighOnErrors() {
		assertThat(analyzer.analyze(incident("SQLException: deadlock detected", null, LogLevel.ERROR, 2, null))
				.severity()).isEqualTo(Severity.HIGH);
		assertThat(analyzer.analyze(incident("SQLException: deadlock detected", null, LogLevel.WARN, 2, null))
				.severity()).isEqualTo(Severity.LOW);
	}
}
