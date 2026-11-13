package com.ai.logintelligence.correlation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.ai.logintelligence.ingest.LogEvent;

/**
 * Turns a log event into a {@link Signature} by masking everything that varies
 * between occurrences of the same error (ids, numbers, addresses, quoted values),
 * so "Order 4711 failed for user 'bob'" and "Order 9 failed for user 'amy'"
 * collapse into one incident.
 */
@Component
public class Fingerprinter {

	private static final int MAX_TEMPLATE_LENGTH = 240;

	private record Mask(Pattern pattern, String replacement) {
	}

	/** Order matters: specific shapes first, bare numbers last. */
	private static final List<Mask> MASKS = List.of(
			new Mask(Pattern.compile(
					"\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b"), "<uuid>"),
			new Mask(Pattern.compile(
					"\\b\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?"), "<ts>"),
			new Mask(Pattern.compile("\\b[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+\\b"), "<email>"),
			new Mask(Pattern.compile("\\bhttps?://[^\\s\"'<>]+"), "<url>"),
			new Mask(Pattern.compile("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d+)?\\b"), "<ip>"),
			new Mask(Pattern.compile("\\b0x[0-9a-fA-F]+\\b"), "<hex>"),
			// Bare hex ids (request ids, hashes) get the same placeholder as plain numbers: a random
			// hex id is sometimes all digits, and the two must not produce different signatures.
			// Letters-only tokens need 8+ chars so words like "facade" or "decade" survive.
			new Mask(Pattern.compile(
					"\\b(?=[0-9a-fA-F]*\\d)(?=\\d*[a-fA-F])[0-9a-fA-F]{6,}\\b|\\b[a-fA-F]{8,}\\b"), "<n>"),
			new Mask(Pattern.compile("\"[^\"]*\"|(?<![A-Za-z])'[^']*'(?![A-Za-z])"), "<str>"),
			// Keep numbers that carry meaning as a class of error (HTTP 503 vs HTTP 404).
			new Mask(Pattern.compile(
					"(?<![\\dA-Za-z_])(?<!(?:HTTP|http|status|Status|code|Code)[ =:/])\\d+(?:\\.\\d+)?"),
					"<n>"));

	private static final Pattern QUALIFIED_EXCEPTION = Pattern
			.compile("\\b((?:[a-zA-Z_$][\\w$]*\\.)+[A-Z][\\w$]*(?:Exception|Error))\\b");
	private static final Pattern SIMPLE_EXCEPTION = Pattern.compile("\\b([A-Z][\\w$]*(?:Exception|Error))\\b");
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	public Signature signatureOf(LogEvent event) {
		String template = templateOf(event.message());
		String exceptionType = exceptionTypeOf(event);
		String fingerprint = hash(event.service() + '\n' + (exceptionType == null ? "" : exceptionType) + '\n'
				+ template);
		return new Signature(fingerprint, template, exceptionType);
	}

	String templateOf(String message) {
		String line = message.strip().lines().findFirst().orElse("");
		for (Mask mask : MASKS) {
			line = mask.pattern().matcher(line).replaceAll(mask.replacement());
		}
		line = WHITESPACE.matcher(line).replaceAll(" ").strip();
		return line.length() > MAX_TEMPLATE_LENGTH ? line.substring(0, MAX_TEMPLATE_LENGTH) : line;
	}

	private static String exceptionTypeOf(LogEvent event) {
		for (Pattern pattern : List.of(QUALIFIED_EXCEPTION, SIMPLE_EXCEPTION)) {
			for (String source : new String[] { firstLine(event.stackTrace()), event.message() }) {
				if (source == null) {
					continue;
				}
				Matcher m = pattern.matcher(source);
				if (m.find()) {
					return m.group(1);
				}
			}
		}
		return null;
	}

	private static String firstLine(String text) {
		return text == null ? null : text.strip().lines().findFirst().orElse(null);
	}

	private static String hash(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest, 0, 16);
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required by the Java platform", e);
		}
	}
}
