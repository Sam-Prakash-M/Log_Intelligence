package com.ai.logintelligence.correlation;

import java.util.Optional;
import java.util.UUID;

/**
 * Shared "which incident is currently open for this signature" state. It lives
 * outside the JVM so any number of consumer instances correlate consistently.
 */
public interface SignatureStore {

	Optional<UUID> find(String fingerprint);

	/** Atomically binds the fingerprint to the incident; false when another consumer won. */
	boolean claim(String fingerprint, UUID incidentId);

	/** Slides the correlation window forward. */
	void touch(String fingerprint);

	/** Unbinds the fingerprint, but only while it still points at this incident. */
	void release(String fingerprint, UUID incidentId);
}
