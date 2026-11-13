package com.ai.logintelligence.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.ai.logintelligence.ingest.LogLevel;

class ClaudeIncidentAnalyzerPromptTest {

	@Test
	void promptCarriesTheIncidentFactsAndBoundsEachSample() {
		Instant now = Instant.parse("2026-11-13T10:00:00Z");
		String longStack = IntStream.range(0, 60).mapToObj(i -> "\tat com.shop.Frame" + i + ".call(Frame.java)")
				.collect(Collectors.joining("\n", "java.lang.IllegalStateException: boom\n", ""));
		var incident = new IncidentContext("order-service", "Order <n> failed", "java.lang.IllegalStateException",
				LogLevel.ERROR, 42, now, now.plusSeconds(90),
				List.of(new IncidentContext.Sample(now, LogLevel.ERROR, "node-3", "x".repeat(5_000), longStack)));

		String prompt = ClaudeIncidentAnalyzer.describe(incident);

		assertThat(prompt).contains("service: order-service", "signature: Order <n> failed", "occurrences: 42",
				"exception: java.lang.IllegalStateException", "host=node-3", "<log_samples>", "</log_samples>");
		assertThat(prompt).contains("…[truncated]").contains("Frame18").doesNotContain("Frame19");
		assertThat(prompt.length()).isLessThan(3_500);
	}
}
