package com.ai.logintelligence.correlation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.ai.logintelligence.config.PipelineProperties;
import com.ai.logintelligence.ingest.LogEvent;
import com.ai.logintelligence.ingest.LogLevel;
import com.ai.logintelligence.stats.PipelineStats;
import com.ai.logintelligence.stats.PipelineStats.Metric;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consumer group {@code log-correlator}. Scale out by starting more instances:
 * Kafka rebalances the partitions, and all shared state is in Redis/PostgreSQL.
 */
@Component
public class LogCorrelationListener {

	private static final Logger log = LoggerFactory.getLogger(LogCorrelationListener.class);

	private record Group(Signature signature, List<LogEvent> events) {
	}

	private final JsonMapper json;
	private final Fingerprinter fingerprinter;
	private final IncidentCorrelator correlator;
	private final PipelineStats stats;
	private final KafkaTemplate<String, String> kafka;
	private final String deadLetterTopic;
	private final LogLevel minLevel;
	private final Counter consumed;
	private final Counter correlated;
	private final Counter malformed;

	public LogCorrelationListener(JsonMapper json, Fingerprinter fingerprinter, IncidentCorrelator correlator,
			PipelineStats stats, KafkaTemplate<String, String> kafka, PipelineProperties props,
			MeterRegistry metrics) {
		this.json = json;
		this.fingerprinter = fingerprinter;
		this.correlator = correlator;
		this.stats = stats;
		this.kafka = kafka;
		this.deadLetterTopic = props.topics().rawLogsDeadLetter();
		this.minLevel = props.correlation().minLevel();
		this.consumed = metrics.counter("logintel.logs.consumed");
		this.correlated = metrics.counter("logintel.logs.correlated");
		this.malformed = metrics.counter("logintel.logs.malformed");
	}

	/**
	 * Works a whole poll at once: events are grouped by signature first, so a burst
	 * of 500 identical errors costs one Redis lookup and one UPDATE, not 500.
	 */
	@KafkaListener(id = "log-correlator", groupId = "log-correlator", topics = "${pipeline.topics.raw-logs}",
			batch = "true", concurrency = "${pipeline.correlation.concurrency}")
	public void onBatch(List<ConsumerRecord<String, String>> records) {
		Map<String, Group> groups = new LinkedHashMap<>();
		int wellFormed = 0;
		int relevant = 0;

		for (ConsumerRecord<String, String> record : records) {
			LogEvent event = parse(record);
			if (event == null) {
				continue;
			}
			wellFormed++;
			if (event.level().compareTo(minLevel) < 0) {
				continue;
			}
			relevant++;
			Signature signature = fingerprinter.signatureOf(event);
			groups.computeIfAbsent(signature.fingerprint(), k -> new Group(signature, new ArrayList<>())).events()
					.add(event);
		}

		groups.values().forEach(group -> correlator.correlate(group.signature(), group.events()));

		consumed.increment(wellFormed);
		correlated.increment(relevant);
		stats.record(Metric.INGESTED, wellFormed);
		stats.record(Metric.CORRELATED, relevant);
	}

	/** Unreadable records go straight to the dead-letter topic; retrying them cannot help. */
	private LogEvent parse(ConsumerRecord<String, String> record) {
		try {
			LogEvent event = json.readValue(record.value(), LogEvent.class);
			if (event != null && event.isWellFormed()) {
				return event;
			}
		}
		catch (JacksonException ex) {
			log.debug("Unparseable log record at {}-{}@{}", record.topic(), record.partition(), record.offset(), ex);
		}
		malformed.increment();
		kafka.send(deadLetterTopic, record.key(), record.value());
		return null;
	}
}
