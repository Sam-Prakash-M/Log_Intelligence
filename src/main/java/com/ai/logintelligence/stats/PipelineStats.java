package com.ai.logintelligence.stats;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Per-minute pipeline counters kept in Redis, so the dashboard shows cluster-wide
 * throughput no matter which instance serves the request.
 */
@Component
public class PipelineStats {

	public enum Metric {
		INGESTED, CORRELATED
	}

	public record Point(Instant minute, long value) {
	}

	private static final String PREFIX = "logintel:stats:";
	private static final Duration RETENTION = Duration.ofHours(2);

	private final StringRedisTemplate redis;

	public PipelineStats(StringRedisTemplate redis) {
		this.redis = redis;
	}

	public void record(Metric metric, long count) {
		if (count <= 0) {
			return;
		}
		String key = key(metric, Instant.now().getEpochSecond() / 60);
		redis.opsForValue().increment(key, count);
		redis.expire(key, RETENTION);
	}

	/** The last {@code minutes} whole minutes, oldest first, zero-filled. */
	public List<Point> series(Metric metric, int minutes) {
		long current = Instant.now().getEpochSecond() / 60;
		List<String> keys = new ArrayList<>(minutes);
		for (long m = current - minutes + 1; m <= current; m++) {
			keys.add(key(metric, m));
		}
		List<String> values = redis.opsForValue().multiGet(keys);
		List<Point> points = new ArrayList<>(minutes);
		for (int i = 0; i < minutes; i++) {
			String raw = values == null ? null : values.get(i);
			points.add(new Point(Instant.ofEpochSecond((current - minutes + 1 + i) * 60),
					raw == null ? 0 : Long.parseLong(raw)));
		}
		return points;
	}

	private static String key(Metric metric, long epochMinute) {
		return PREFIX + metric.name().toLowerCase() + ':' + epochMinute;
	}
}
