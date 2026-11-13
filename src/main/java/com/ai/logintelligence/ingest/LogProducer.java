package com.ai.logintelligence.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.ai.logintelligence.config.PipelineProperties;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.json.JsonMapper;

@Component
public class LogProducer {

	private static final Logger log = LoggerFactory.getLogger(LogProducer.class);

	private final KafkaTemplate<String, String> kafka;
	private final JsonMapper json;
	private final String topic;
	private final Counter accepted;
	private final Counter failed;

	public LogProducer(KafkaTemplate<String, String> kafka, JsonMapper json, PipelineProperties props,
			MeterRegistry metrics) {
		this.kafka = kafka;
		this.json = json;
		this.topic = props.topics().rawLogs();
		this.accepted = metrics.counter("logintel.logs.accepted");
		this.failed = metrics.counter("logintel.logs.publish.failed");
	}

	/**
	 * Non-blocking publish. Keyed by service so one service's events stay ordered
	 * on a single partition while different services spread across consumers.
	 */
	public void publish(LogEvent event) {
		kafka.send(topic, event.service(), json.writeValueAsString(event)).whenComplete((result, ex) -> {
			if (ex != null) {
				failed.increment();
				log.error("Failed to publish log event {} for service {}", event.eventId(), event.service(), ex);
			}
		});
		accepted.increment();
	}
}
