package com.ai.logintelligence.analysis;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.ai.logintelligence.config.PipelineProperties;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * LLM verdicts by signature. A recurring error that opens a new incident tomorrow
 * reuses today's verdict instead of paying for the same analysis again.
 */
@Component
public class AnalysisCache {

	private static final Logger log = LoggerFactory.getLogger(AnalysisCache.class);
	private static final String PREFIX = "logintel:analysis:";

	private final StringRedisTemplate redis;
	private final JsonMapper json;
	private final Duration ttl;

	public AnalysisCache(StringRedisTemplate redis, JsonMapper json, PipelineProperties props) {
		this.redis = redis;
		this.json = json;
		this.ttl = props.llm().cacheTtl();
	}

	public Optional<AnalysisResult> get(String fingerprint) {
		String raw = redis.opsForValue().get(PREFIX + fingerprint);
		if (raw == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(json.readValue(raw, AnalysisResult.class));
		}
		catch (JacksonException ex) {
			log.warn("Dropping unreadable cached verdict for {}", fingerprint, ex);
			redis.delete(PREFIX + fingerprint);
			return Optional.empty();
		}
	}

	public void put(String fingerprint, AnalysisResult result) {
		redis.opsForValue().set(PREFIX + fingerprint, json.writeValueAsString(result), ttl);
	}
}
