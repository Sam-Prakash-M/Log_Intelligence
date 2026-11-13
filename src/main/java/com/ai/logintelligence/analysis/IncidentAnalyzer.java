package com.ai.logintelligence.analysis;

public interface IncidentAnalyzer {

	/** @throws AnalysisUnavailableException when this analyzer cannot produce a verdict right now */
	AnalysisResult analyze(IncidentContext incident);
}
