package com.ai.logintelligence.stats;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ai.logintelligence.analysis.IncidentAnalysisService;
import com.ai.logintelligence.config.PipelineProperties;
import com.ai.logintelligence.incident.IncidentRepository;
import com.ai.logintelligence.incident.IncidentStatus;
import com.ai.logintelligence.incident.RootCauseCategory;
import com.ai.logintelligence.incident.Severity;
import com.ai.logintelligence.stats.PipelineStats.Metric;
import com.ai.logintelligence.stats.PipelineStats.Point;

/** One call that feeds the whole dashboard. */
@RestController
@RequestMapping("/api/v1/stats")
public class StatsController {

	private final IncidentRepository incidents;
	private final PipelineStats stats;
	private final IncidentAnalysisService analysis;
	private final PipelineProperties props;
	private final String instance;

	public StatsController(IncidentRepository incidents, PipelineStats stats, IncidentAnalysisService analysis,
			PipelineProperties props) {
		this.incidents = incidents;
		this.stats = stats;
		this.analysis = analysis;
		this.props = props;
		this.instance = hostname();
	}

	@GetMapping
	public Stats stats(@RequestParam(defaultValue = "30") int minutes) {
		int window = Math.clamp(minutes, 5, 120);

		Map<IncidentStatus, Long> byStatus = new EnumMap<>(IncidentStatus.class);
		for (IncidentStatus status : IncidentStatus.values()) {
			byStatus.put(status, incidents.countByStatus(status));
		}
		Map<Severity, Long> bySeverity = new EnumMap<>(Severity.class);
		for (Severity severity : Severity.values()) {
			bySeverity.put(severity, 0L);
		}
		incidents.countActiveBySeverity().forEach(row -> bySeverity.put((Severity) row[0], (Long) row[1]));

		Map<RootCauseCategory, Long> byCategory = new EnumMap<>(RootCauseCategory.class);
		incidents.countActiveByCategory().forEach(row -> byCategory.put((RootCauseCategory) row[0], (Long) row[1]));

		List<ServiceLoad> services = incidents.activeByService().stream().limit(8)
				.map(row -> new ServiceLoad((String) row[0], (Long) row[1], ((Number) row[2]).longValue())).toList();

		return new Stats(byStatus, bySeverity, byCategory, services, stats.series(Metric.INGESTED, window),
				stats.series(Metric.CORRELATED, window),
				new Analyzer(analysis.llmEnabled(), analysis.llmEnabled() ? props.llm().model() : "heuristic"),
				props.simulator().enabled(), instance);
	}

	private static String hostname() {
		try {
			return InetAddress.getLocalHost().getHostName();
		}
		catch (UnknownHostException e) {
			return "unknown";
		}
	}

	public record ServiceLoad(String service, long incidents, long occurrences) {
	}

	public record Analyzer(boolean llm, String model) {
	}

	/**
	 * @param ingested   well-formed events consumed per minute, cluster-wide
	 * @param correlated events at or above the correlation level per minute, cluster-wide
	 * @param servedBy   host name of the instance that answered - shows load balancing at work
	 */
	public record Stats(Map<IncidentStatus, Long> byStatus, Map<Severity, Long> activeBySeverity,
			Map<RootCauseCategory, Long> activeByCategory, List<ServiceLoad> activeByService, List<Point> ingested,
			List<Point> correlated, Analyzer analyzer, boolean simulatorEnabled, String servedBy) {
	}
}
