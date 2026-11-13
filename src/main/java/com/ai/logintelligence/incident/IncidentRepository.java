package com.ai.logintelligence.incident;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IncidentRepository extends JpaRepository<Incident, UUID>, JpaSpecificationExecutor<Incident> {

	/**
	 * Atomic in-database increment, so concurrent consumers never lose counts.
	 * Returns 0 when the incident is gone or already resolved - the caller then
	 * opens a fresh incident instead of reviving a closed one.
	 */
	@Modifying
	@Query("""
			update Incident i
			   set i.occurrenceCount = i.occurrenceCount + :count,
			       i.lastSeen = case when i.lastSeen < :lastSeen then :lastSeen else i.lastSeen end
			 where i.id = :id and i.status <> com.ai.logintelligence.incident.IncidentStatus.RESOLVED
			""")
	int addOccurrences(@Param("id") UUID id, @Param("count") long count, @Param("lastSeen") Instant lastSeen);

	/** Idempotent, so it is safe to run from every instance without a distributed lock. */
	@Modifying
	@Query("""
			update Incident i
			   set i.status = com.ai.logintelligence.incident.IncidentStatus.RESOLVED, i.resolvedAt = :now
			 where i.status <> com.ai.logintelligence.incident.IncidentStatus.RESOLVED and i.lastSeen < :cutoff
			""")
	int resolveStale(@Param("cutoff") Instant cutoff, @Param("now") Instant now);

	long countByStatus(IncidentStatus status);

	@Query("""
			select i.severity, count(i) from Incident i
			 where i.status <> com.ai.logintelligence.incident.IncidentStatus.RESOLVED
			 group by i.severity
			""")
	List<Object[]> countActiveBySeverity();

	@Query("""
			select i.rootCauseCategory, count(i) from Incident i
			 where i.status <> com.ai.logintelligence.incident.IncidentStatus.RESOLVED
			 group by i.rootCauseCategory
			""")
	List<Object[]> countActiveByCategory();

	@Query("""
			select i.service, count(i), sum(i.occurrenceCount) from Incident i
			 where i.status <> com.ai.logintelligence.incident.IncidentStatus.RESOLVED
			 group by i.service
			 order by count(i) desc
			""")
	List<Object[]> activeByService();
}
