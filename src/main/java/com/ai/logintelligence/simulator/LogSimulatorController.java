package com.ai.logintelligence.simulator;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ai.logintelligence.ingest.LogEvent;
import com.ai.logintelligence.ingest.LogLevel;
import com.ai.logintelligence.ingest.LogProducer;

/**
 * Demo traffic: realistic failures with varying ids, hosts and numbers, mixed with
 * INFO noise. Off unless {@code pipeline.simulator.enabled=true}.
 */
@RestController
@RequestMapping("/api/v1/simulate")
@ConditionalOnProperty(name = "pipeline.simulator.enabled", havingValue = "true")
public class LogSimulatorController {

	private record Scenario(String service, LogLevel level, String message, String stackTrace) {
	}

	private static final List<Scenario> FAILURES = List.of(
			new Scenario("payment-service", LogLevel.ERROR,
					"Failed to charge order %d for customer '%s': could not acquire connection from pool within 30000ms",
					"org.springframework.dao.DataAccessResourceFailureException: HikariPool-1 - Connection is not available, request timed out after 30000ms\n\tat com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:696)\n\tat com.shop.payment.ChargeRepository.save(ChargeRepository.java:41)"),
			new Scenario("order-service", LogLevel.ERROR,
					"Unhandled exception while processing order %d (request %s)",
					"java.lang.NullPointerException: Cannot invoke \"com.shop.order.Address.getCountry()\" because \"address\" is null\n\tat com.shop.order.ShippingCalculator.quote(ShippingCalculator.java:58)\n\tat com.shop.order.OrderService.place(OrderService.java:112)"),
			new Scenario("api-gateway", LogLevel.WARN,
					"Upstream inventory-service returned HTTP 503 for GET /items/%d after 3 retries (trace %s)", null),
			new Scenario("auth-service", LogLevel.WARN,
					"Authentication failed for user '%2$s': token expired %1$d seconds ago", null),
			new Scenario("inventory-service", LogLevel.FATAL, "Worker %d terminated: Java heap space (batch %s)",
					"java.lang.OutOfMemoryError: Java heap space\n\tat java.base/java.util.Arrays.copyOf(Arrays.java:3512)\n\tat com.shop.inventory.StockSnapshot.load(StockSnapshot.java:77)"),
			new Scenario("notification-service", LogLevel.ERROR,
					"Could not deliver message %d to smtp host 10.2.14.7:587 (id %s): Connection refused",
					"java.net.ConnectException: Connection refused\n\tat java.base/sun.nio.ch.Net.pollConnect(Native Method)\n\tat com.shop.notify.SmtpSender.send(SmtpSender.java:33)"),
			new Scenario("search-service", LogLevel.ERROR,
					"Rejected query %d from session %s: malformed filter expression",
					"com.shop.search.QueryParseException: malformed filter at position 17\n\tat com.shop.search.FilterParser.parse(FilterParser.java:90)"),
			new Scenario("billing-service", LogLevel.ERROR,
					"Startup check %d failed (%s): could not resolve placeholder 'billing.tax.endpoint'",
					"java.lang.IllegalArgumentException: Could not resolve placeholder 'billing.tax.endpoint' in value \"${billing.tax.endpoint}\"\n\tat org.springframework.util.PropertyPlaceholderHelper.parseStringValue(PropertyPlaceholderHelper.java:180)"));

	private static final List<String> NOISE_SERVICES = List.of("payment-service", "order-service", "api-gateway",
			"auth-service", "inventory-service", "search-service");

	private final LogProducer producer;

	public LogSimulatorController(LogProducer producer) {
		this.producer = producer;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	public Simulated simulate(@RequestParam(defaultValue = "500") int count) {
		int total = Math.clamp(count, 1, 20_000);
		ThreadLocalRandom random = ThreadLocalRandom.current();
		// Each burst exercises only some of the failure modes, like a real outage would.
		List<Scenario> active = FAILURES.stream().filter(s -> random.nextInt(100) < 60).toList();
		if (active.isEmpty()) {
			active = List.of(FAILURES.get(random.nextInt(FAILURES.size())));
		}

		for (int i = 0; i < total; i++) {
			Instant now = Instant.now();
			String host = "node-" + random.nextInt(1, 6);
			String trace = UUID.randomUUID().toString();
			if (random.nextInt(100) < 55) {
				String service = NOISE_SERVICES.get(random.nextInt(NOISE_SERVICES.size()));
				producer.publish(new LogEvent(UUID.randomUUID(), now, service, host, LogLevel.INFO,
						"Handled request " + trace + " in " + random.nextInt(3, 250) + "ms", null, trace));
			}
			else {
				Scenario s = active.get(random.nextInt(active.size()));
				String message = s.message().formatted(random.nextInt(1, 100_000), trace.substring(0, 8));
				producer.publish(
						new LogEvent(UUID.randomUUID(), now, s.service(), host, s.level(), message, s.stackTrace(), trace));
			}
		}
		return new Simulated(total, active.stream().map(Scenario::service).toList());
	}

	public record Simulated(int published, List<String> failingServices) {
	}
}
