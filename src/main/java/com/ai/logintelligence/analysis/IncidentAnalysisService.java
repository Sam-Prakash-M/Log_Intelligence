package com.ai.logintelligence.analysis;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.ai.logintelligence.config.PipelineProperties;
import com.ai.logintelligence.incident.Incident;
import com.ai.logintelligence.incident.IncidentService;

import io.micrometer.core.instrument.MeterRegistry;

/** Chooses cache, LLM or rules for an incident and stores the verdict. */
@Service
public class IncidentAnalysisService {

	private static final Logger log = LoggerFactory.getLogger(IncidentAnalysisService.class);

	private final IncidentService incidents;
	private final AnalysisCache cache;
	private final HeuristicIncidentAnalyzer heuristic;
	private final Optional<IncidentAnalyzer> llm;
	private final MeterRegistry metrics;

	@Autowired
	public IncidentAnalysisService(IncidentService incidents, AnalysisCache cache,
			HeuristicIncidentAnalyzer heuristic, PipelineProperties props, MeterRegistry metrics) {
		this(incidents, cache, heuristic,
				props.llm().usable() ? Optional.of(new ClaudeIncidentAnalyzer(props.llm())) : Optional.empty(),
				metrics);
		log.info(llm.isPresent() ? "Incident analysis: Claude ({}) with rule-based fallback"
				: "Incident analysis: rule-based only (set ANTHROPIC_API_KEY to enable Claude; model would be {})",
				props.llm().model());
	}

	IncidentAnalysisService(IncidentService incidents, AnalysisCache cache, HeuristicIncidentAnalyzer heuristic,
			Optional<IncidentAnalyzer> llm, MeterRegistry metrics) {
		this.incidents = incidents;
		this.cache = cache;
		this.heuristic = heuristic;
		this.llm = llm;
		this.metrics = metrics;
	}

	public boolean llmEnabled() {
		return llm.isPresent();
	}

	public void analyze(AnalysisRequest request) {
		Optional<Incident> found = incidents.find(request.incidentId());
		if (found.isEmpty()) {
			log.warn("Analysis requested for unknown incident {}", request.incidentId());
			return;
		}
		Incident incident = found.get();

		Optional<AnalysisResult> cached = request.force() ? Optional.empty() : cache.get(incident.getFingerprint());
		AnalysisResult result;
		if (cached.isPresent()) {
			result = cached.get();
			metrics.counter("logintel.analysis.cache.hits").increment();
		}
		else {
			result = run(incident);
		}
		incidents.applyAnalysis(incident.getId(), result);
		log.info("Incident {} tagged {} / {} by {}", incident.getId(), result.category(), result.severity(),
				result.analyzedBy());
	}

	private AnalysisResult run(Incident incident) {
		IncidentContext context = IncidentContext.of(incident, incidents.samplesOf(incident.getId()));
		long start = System.nanoTime();
		String source = HeuristicIncidentAnalyzer.NAME;
		try {
			if (llm.isPresent()) {
				try {
					AnalysisResult result = llm.get().analyze(context);
					// Only LLM verdicts are cached: a rule-based fallback must not block a later LLM attempt.
					cache.put(incident.getFingerprint(), result);
					source = "llm";
					return result;
				}
				catch (AnalysisUnavailableException ex) {
					log.warn("LLM analysis unavailable for incident {} ({}); using rules", incident.getId(),
							ex.getMessage());
					metrics.counter("logintel.analysis.llm.failures").increment();
				}
			}
			return heuristic.analyze(context);
		}
		finally {
			metrics.timer("logintel.analysis", "source", source).record(Duration.ofNanos(System.nanoTime() - start));
		}
	}
}
