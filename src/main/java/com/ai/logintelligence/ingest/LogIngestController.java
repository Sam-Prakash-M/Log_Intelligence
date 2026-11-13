package com.ai.logintelligence.ingest;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/logs")
public class LogIngestController {

	private final LogProducer producer;

	public LogIngestController(LogProducer producer) {
		this.producer = producer;
	}

	/** Accepts up to 1000 events per call and hands them to Kafka; processing is asynchronous. */
	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	public Accepted ingest(@Valid @RequestBody LogBatch batch) {
		Instant now = Instant.now();
		batch.events().forEach(entry -> producer.publish(entry.toEvent(now)));
		return new Accepted(batch.events().size());
	}

	public record Accepted(int accepted) {
	}
}
