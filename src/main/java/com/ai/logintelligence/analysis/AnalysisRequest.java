package com.ai.logintelligence.analysis;

import java.util.UUID;

/**
 * Message on the analysis-requests topic.
 *
 * @param force skip the verdict cache (manual re-analysis)
 */
public record AnalysisRequest(UUID incidentId, boolean force) {
}
