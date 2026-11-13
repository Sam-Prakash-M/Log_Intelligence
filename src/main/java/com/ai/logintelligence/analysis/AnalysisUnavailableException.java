package com.ai.logintelligence.analysis;

public class AnalysisUnavailableException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	public AnalysisUnavailableException(String message) {
		super(message);
	}

	public AnalysisUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
