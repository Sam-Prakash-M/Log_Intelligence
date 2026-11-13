package com.ai.logintelligence.analysis;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.ai.logintelligence.config.PipelineProperties;

import tools.jackson.databind.json.JsonMapper;

/**
 * Analysis is decoupled from correlation through its own topic: a slow or
 * rate-limited LLM backs up this queue, never log ingestion.
 */
@Component
public class AnalysisRequestPublisher {

	private static final Logger log = LoggerFactory.getLogger(AnalysisRequestPublisher.class);

	private final KafkaTemplate<String, String> kafka;
	private final JsonMapper json;
	private final String topic;

	public AnalysisRequestPublisher(KafkaTemplate<String, String> kafka, JsonMapper json, PipelineProperties props) {
		this.kafka = kafka;
		this.json = json;
		this.topic = props.topics().analysisRequests();
	}

	public void request(UUID incidentId, String fingerprint, boolean force) {
		kafka.send(topic, fingerprint, json.writeValueAsString(new AnalysisRequest(incidentId, force)))
				.whenComplete((result, ex) -> {
					if (ex != null) {
						log.error("Could not queue analysis for incident {}; trigger it via POST "
								+ "/api/v1/incidents/{}/reanalyze", incidentId, incidentId, ex);
					}
				});
	}
}
