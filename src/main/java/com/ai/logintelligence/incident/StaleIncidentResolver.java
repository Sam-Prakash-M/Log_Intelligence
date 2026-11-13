package com.ai.logintelligence.incident;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ai.logintelligence.config.PipelineProperties;

/** Closes incidents whose error stopped occurring. Runs on every instance; the update is idempotent. */
@Component
public class StaleIncidentResolver {

	private static final Logger log = LoggerFactory.getLogger(StaleIncidentResolver.class);

	private final IncidentService incidents;
	private final Duration autoResolveAfter;

	public StaleIncidentResolver(IncidentService incidents, PipelineProperties props) {
		this.incidents = incidents;
		this.autoResolveAfter = props.correlation().autoResolveAfter();
	}

	@Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
	public void resolveStale() {
		int resolved = incidents.resolveStale(Instant.now().minus(autoResolveAfter));
		if (resolved > 0) {
			log.info("Auto-resolved {} incident(s) with no events for {}", resolved, autoResolveAfter);
		}
	}
}
