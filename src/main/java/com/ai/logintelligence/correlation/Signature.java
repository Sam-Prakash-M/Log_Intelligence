package com.ai.logintelligence.correlation;

/**
 * Identity of an error: events with the same fingerprint are the same problem.
 *
 * @param fingerprint   stable hash of service + exception type + message template
 * @param template      the message with variable parts masked, readable as an incident title
 * @param exceptionType fully qualified exception class when one could be detected, else null
 */
public record Signature(String fingerprint, String template, String exceptionType) {
}
