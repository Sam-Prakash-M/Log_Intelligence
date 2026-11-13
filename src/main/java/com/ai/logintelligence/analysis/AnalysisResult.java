package com.ai.logintelligence.analysis;

import com.ai.logintelligence.incident.RootCauseCategory;
import com.ai.logintelligence.incident.Severity;

/**
 * @param confidence 0..1
 * @param analyzedBy model id for LLM verdicts, {@code "heuristic"} for rule-based ones
 */
public record AnalysisResult(RootCauseCategory category, Severity severity, String summary,
		String suggestedAction, double confidence, String analyzedBy) {
}
