package com.ai.logintelligence.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.ai.logintelligence.ingest.LogEvent;
import com.ai.logintelligence.ingest.LogLevel;

class FingerprinterTest {

	private final Fingerprinter fingerprinter = new Fingerprinter();

	private static LogEvent event(String service, String message, String stackTrace) {
		return new LogEvent(UUID.randomUUID(), Instant.now(), service, "node-1", LogLevel.ERROR, message, stackTrace,
				null);
	}

	@Test
	void sameErrorWithDifferentVariablesSharesAFingerprint() {
		Signature a = fingerprinter.signatureOf(event("payment-service",
				"Failed to charge order 4711 for customer 'bob' after 30000ms (trace 3f2b8c1e-9d4a-4c7e-8b1a-2f6d9e0c5a71)",
				null));
		Signature b = fingerprinter.signatureOf(event("payment-service",
				"Failed to charge order 9 for customer 'amy' after 1250ms (trace a1b2c3d4-0000-4c7e-8b1a-ffffffffffff)",
				null));

		assertThat(a.fingerprint()).isEqualTo(b.fingerprint());
		assertThat(a.template())
				.isEqualTo("Failed to charge order <n> for customer <str> after <n>ms (trace <uuid>)");
	}

	@Test
	void masksAddressesTimestampsUrlsAndHex() {
		String template = fingerprinter.templateOf(
				"2026-11-13T10:15:30.123Z connect to 10.2.14.7:5432 failed, see https://wiki.corp/x?id=5, "
						+ "session 9f86d081884c7d65, ptr 0xDEADBEEF, mail ops@example.com");

		assertThat(template).isEqualTo(
				"<ts> connect to <ip> failed, see <url> session <n>, ptr <hex>, mail <email>");
	}

	@Test
	void hexIdsThatHappenToBeAllDigitsDoNotSplitTheSignature() {
		assertThat(fingerprinter.templateOf("Unhandled exception (request 3f2b8c1e)"))
				.isEqualTo(fingerprinter.templateOf("Unhandled exception (request 40917253)"))
				.isEqualTo(fingerprinter.templateOf("Unhandled exception (request debbdbea)"))
				.isEqualTo("Unhandled exception (request <n>)");
		assertThat(fingerprinter.templateOf("Facade added a decade of bad cache")).doesNotContain("<n>");
	}

	@Test
	void keepsHttpStatusCodesBecauseTheyDistinguishErrors() {
		assertThat(fingerprinter.templateOf("Upstream returned HTTP 503 for GET /items/42"))
				.isEqualTo("Upstream returned HTTP 503 for GET /items/<n>");
		assertThat(fingerprinter.signatureOf(event("gw", "Upstream returned HTTP 503", null)).fingerprint())
				.isNotEqualTo(fingerprinter.signatureOf(event("gw", "Upstream returned HTTP 404", null)).fingerprint());
	}

	@Test
	void differentServicesNeverShareAFingerprint() {
		assertThat(fingerprinter.signatureOf(event("order-service", "Connection refused", null)).fingerprint())
				.isNotEqualTo(fingerprinter.signatureOf(event("billing-service", "Connection refused", null))
						.fingerprint());
	}

	@Test
	void exceptionTypeComesFromTheStackTraceAndSeparatesIncidents() {
		Signature npe = fingerprinter.signatureOf(event("order-service", "Unhandled exception",
				"java.lang.NullPointerException: address is null\n\tat com.shop.Order.place(Order.java:12)"));
		Signature ise = fingerprinter.signatureOf(event("order-service", "Unhandled exception",
				"java.lang.IllegalStateException: closed\n\tat com.shop.Order.place(Order.java:19)"));

		assertThat(npe.exceptionType()).isEqualTo("java.lang.NullPointerException");
		assertThat(npe.fingerprint()).isNotEqualTo(ise.fingerprint());
	}

	@Test
	void onlyTheFirstLineOfAMessageFormsTheTemplate() {
		assertThat(fingerprinter.templateOf("Job 12 failed\n  detail: row 99881 rejected")).isEqualTo("Job <n> failed");
	}
}
