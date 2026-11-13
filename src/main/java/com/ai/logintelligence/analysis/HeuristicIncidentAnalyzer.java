package com.ai.logintelligence.analysis;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.ai.logintelligence.incident.RootCauseCategory;
import com.ai.logintelligence.incident.Severity;
import com.ai.logintelligence.ingest.LogLevel;

/**
 * Keyword rules. Used when no LLM is configured and as the safety net when the
 * LLM call fails, so every incident always ends up with a tag and a severity.
 */
@Component
public class HeuristicIncidentAnalyzer implements IncidentAnalyzer {

	public static final String NAME = "heuristic";

	private record Rule(RootCauseCategory category, String action, List<String> keywords) {
	}

	/** First match wins, so the more specific categories come first. */
	private static final List<Rule> RULES = List.of(
			new Rule(RootCauseCategory.RESOURCE_EXHAUSTION,
					"Check memory, disk, file-descriptor and pool limits on the affected hosts; scale or raise limits.",
					List.of("outofmemory", "heap space", "gc overhead", "no space left", "disk full",
							"too many open files", "pool exhausted", "rejectedexecution", "queue full",
							"quota exceeded")),
			new Rule(RootCauseCategory.DATABASE,
					"Inspect database health, connection-pool saturation, locks and slow queries.",
					List.of("sqlexception", "jdbc", "hikari", "deadlock", "postgres", "mysql", "constraint violation",
							"duplicate key", "could not acquire connection", "connection pool", "transaction rolled back",
							"dataaccess")),
			new Rule(RootCauseCategory.AUTHENTICATION,
					"Verify credentials, token expiry and permissions for the calling identity.",
					List.of("unauthorized", "forbidden", "http 401", "http 403", "access denied", "token expired",
							"invalid credentials", "authentication failed", "invalid signature", "jwt")),
			new Rule(RootCauseCategory.DEPENDENCY_FAILURE,
					"Check the health and recent deploys of the upstream service; confirm circuit-breaker state.",
					List.of("upstream", "downstream", "circuit breaker", "circuitbreaker", "http 502", "http 503",
							"http 504", "bad gateway", "service unavailable", "feign", "fallback", "broker not available")),
			new Rule(RootCauseCategory.NETWORK,
					"Check connectivity, DNS and firewall rules between the service and its target.",
					List.of("connection refused", "connection reset", "timed out", "timeout", "unknownhost",
							"no route to host", "sockettimeout", "connectexception", "broken pipe", "dns", "ssl",
							"handshake")),
			new Rule(RootCauseCategory.CONFIGURATION,
					"Compare configuration and environment variables against the last working deployment.",
					List.of("missing property", "not configured", "misconfigur", "nosuchbean", "could not resolve placeholder",
							"classnotfound", "noclassdeffound", "invalid configuration", "environment variable",
							"unsupported version")),
			new Rule(RootCauseCategory.DATA_VALIDATION,
					"Inspect the offending payload; tighten validation at the boundary that produced it.",
					List.of("validation failed", "malformed", "parse error", "unparseable", "jsonparse", "deserializ",
							"numberformat", "invalid format", "illegalargument", "schema")),
			new Rule(RootCauseCategory.CODE_DEFECT,
					"Read the stack trace at the first application frame; this looks like a bug in the service code.",
					List.of("nullpointer", "indexoutofbounds", "classcast", "illegalstate", "arithmeticexception",
							"concurrentmodification", "stackoverflow", "assertionerror")));

	@Override
	public AnalysisResult analyze(IncidentContext incident) {
		String haystack = haystackOf(incident);
		for (Rule rule : RULES) {
			for (String keyword : rule.keywords()) {
				if (haystack.contains(keyword)) {
					return new AnalysisResult(rule.category(), severityFor(incident, rule.category()),
							"Matches the known failure pattern \"" + keyword + "\" (rule-based verdict, no LLM).",
							rule.action(), 0.5, NAME);
				}
			}
		}
		return new AnalysisResult(RootCauseCategory.UNKNOWN, severityFor(incident, RootCauseCategory.UNKNOWN),
				"No known failure pattern matched (rule-based verdict, no LLM).",
				"Review the sample events and stack traces manually.", 0.2, NAME);
	}

	/** Severity before any analysis has run - from log level and volume alone. */
	public static Severity severityFor(LogLevel level, long occurrences) {
		return switch (level) {
			case FATAL -> Severity.CRITICAL;
			case ERROR -> occurrences >= 100 ? Severity.HIGH : Severity.MEDIUM;
			default -> occurrences >= 500 ? Severity.MEDIUM : Severity.LOW;
		};
	}

	private static Severity severityFor(IncidentContext incident, RootCauseCategory category) {
		Severity base = severityFor(incident.level(), incident.occurrenceCount());
		boolean systemic = category == RootCauseCategory.RESOURCE_EXHAUSTION || category == RootCauseCategory.DATABASE;
		if (systemic && incident.level().compareTo(LogLevel.ERROR) >= 0 && base.compareTo(Severity.HIGH) < 0) {
			return Severity.HIGH;
		}
		return base;
	}

	private static String haystackOf(IncidentContext incident) {
		String samples = incident.samples().stream()
				.map(s -> s.message() + ' ' + (s.stackTrace() == null ? "" : s.stackTrace()))
				.collect(Collectors.joining(" "));
		return (incident.title() + ' ' + (incident.exceptionType() == null ? "" : incident.exceptionType()) + ' '
				+ samples).toLowerCase(Locale.ROOT);
	}
}
