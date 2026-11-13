package com.ai.logintelligence.correlation;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.ai.logintelligence.config.PipelineProperties;

@Component
public class RedisSignatureStore implements SignatureStore {

	private static final String PREFIX = "logintel:sig:";

	/** Compare-and-delete, so a late release can never drop a newer incident's binding. */
	private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>(
			"if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
			Long.class);

	private final StringRedisTemplate redis;
	private final Duration window;

	public RedisSignatureStore(StringRedisTemplate redis, PipelineProperties props) {
		this.redis = redis;
		this.window = props.correlation().window();
	}

	@Override
	public Optional<UUID> find(String fingerprint) {
		return Optional.ofNullable(redis.opsForValue().get(PREFIX + fingerprint)).map(UUID::fromString);
	}

	@Override
	public boolean claim(String fingerprint, UUID incidentId) {
		return Boolean.TRUE
				.equals(redis.opsForValue().setIfAbsent(PREFIX + fingerprint, incidentId.toString(), window));
	}

	@Override
	public void touch(String fingerprint) {
		redis.expire(PREFIX + fingerprint, window);
	}

	@Override
	public void release(String fingerprint, UUID incidentId) {
		redis.execute(RELEASE, List.of(PREFIX + fingerprint), incidentId.toString());
	}
}
