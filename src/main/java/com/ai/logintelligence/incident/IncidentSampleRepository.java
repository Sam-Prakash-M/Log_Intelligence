package com.ai.logintelligence.incident;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface IncidentSampleRepository extends JpaRepository<IncidentSample, Long> {

	List<IncidentSample> findByIncidentIdOrderByOccurredAtAsc(UUID incidentId);
}
