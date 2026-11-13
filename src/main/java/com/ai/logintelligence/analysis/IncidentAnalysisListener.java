package com.ai.logintelligence.analysis;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * Consumer group {@code incident-analyzer}. Small polls: one LLM call can take
 * many seconds, and a poll must finish well inside {@code max.poll.interval.ms}.
 */
@Component
public class IncidentAnalysisListener {

	private final JsonMapper json;
	private final IncidentAnalysisService analysis;

	public IncidentAnalysisListener(JsonMapper json, IncidentAnalysisService analysis) {
		this.json = json;
		this.analysis = analysis;
	}

	@KafkaListener(id = "incident-analyzer", groupId = "incident-analyzer",
			topics = "${pipeline.topics.analysis-requests}", concurrency = "${pipeline.llm.concurrency}",
			properties = "max.poll.records=2")
	public void onRequest(String payload) {
		// A malformed payload throws; the error handler retries, then dead-letters it.
		analysis.analyze(json.readValue(payload, AnalysisRequest.class));
	}
}
