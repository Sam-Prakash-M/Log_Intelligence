package com.ai.logintelligence.incident;

/** The closed set of root-cause tags an incident can carry. */
public enum RootCauseCategory {
	DATABASE,
	NETWORK,
	RESOURCE_EXHAUSTION,
	AUTHENTICATION,
	CONFIGURATION,
	DEPENDENCY_FAILURE,
	DATA_VALIDATION,
	CODE_DEFECT,
	UNKNOWN
}
