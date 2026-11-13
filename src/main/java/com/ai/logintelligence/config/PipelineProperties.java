package com.ai.logintelligence.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.ai.logintelligence.ingest.LogLevel;

@ConfigurationProperties("pipeline")
public record PipelineProperties(Topics topics, Correlation correlation, Llm llm, Simulator simulator) {

	public record Topics(String rawLogs, String analysisRequests, int partitions, short replicas) {

		public static final String DEAD_LETTER_SUFFIX = ".DLT";

		public String rawLogsDeadLetter() {
			return rawLogs + DEAD_LETTER_SUFFIX;
		}

		public String analysisRequestsDeadLetter() {
			return analysisRequests + DEAD_LETTER_SUFFIX;
		}
	}

	public record Correlation(Duration window, LogLevel minLevel, int sampleLimit, int concurrency,
			Duration autoResolveAfter) {
	}

	public record Llm(boolean enabled, String apiKey, String model, long maxTokens, Duration timeout,
			Duration cacheTtl, int concurrency) {

		public boolean usable() {
			return enabled && apiKey != null && !apiKey.isBlank();
		}
	}

	public record Simulator(boolean enabled) {
	}
}
