package com.ai.logintelligence.ingest;

/** Ordered by severity, so {@code compareTo} can be used for threshold checks. */
public enum LogLevel {
	TRACE, DEBUG, INFO, WARN, ERROR, FATAL
}
