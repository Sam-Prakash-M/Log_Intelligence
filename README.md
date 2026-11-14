# Distributed Log Intelligence Pipeline

An event-driven service that ingests application logs through Kafka, collapses repeated errors into
incidents, and tags each incident with a root cause and a severity - using Claude when an API key is
configured and keyword rules otherwise.

**Stack:** Java 25 · Spring Boot 4.1 · Apache Kafka 4 (KRaft) · PostgreSQL 18 · Redis 8 · Docker Compose ·
Anthropic Java SDK · Micrometer / Prometheus / Grafana

## How it works

```
                 POST /api/v1/logs
                        │
                  ┌─────▼──────┐   logs.raw (6 partitions, key = service)
                  │ Ingest API ├──────────────────────────────┐
                  └────────────┘                              │
                                                 ┌────────────▼─────────────┐
   Redis                                         │ log-correlator group     │
   ├─ signature → open incident (TTL = window) ◄─┤ batch → fingerprint →    │
   ├─ per-minute throughput counters             │ group → dedup            │
   └─ LLM verdict cache (per signature)          └──────┬─────────────┬─────┘
                                                        │             │ new incident only
                                           PostgreSQL ◄─┘             ▼
                                           incidents + samples   incidents.analysis
                                                 ▲                    │
                                                 │       ┌────────────▼─────────────┐
                                                 └───────┤ incident-analyzer group  │
                                                         │ cache → Claude → rules   │
                                                         └──────────────────────────┘
```

1. **Ingest.** `POST /api/v1/logs` validates up to 1000 events and publishes them to `logs.raw`,
   keyed by service name. The call returns `202` as soon as the events are handed to the producer.
2. **Fingerprint.** Each event at `WARN` or above gets a signature: its message with the variable
   parts masked (ids, numbers, IPs, timestamps, quoted values), hashed together with the service and
   the exception type. `Order 4711 failed for 'bob'` and `Order 9 failed for 'amy'` are one signature.
3. **Correlate.** The consumer handles a whole poll at a time and groups it by signature first. The
   first events of a signature open an incident; later ones inside the sliding window (10 min) only
   increment its counter with an atomic `UPDATE`. The signature → incident binding lives in Redis.
4. **Analyse.** Opening an incident publishes one message to `incidents.analysis`. A separate consumer
   group picks it up, so a slow LLM never slows ingestion. A verdict is looked up in the Redis cache by
   signature, then requested from Claude, then - if there is no key or the call fails - produced by rules.
5. **Serve.** REST endpoints and a built-in dashboard read incidents from PostgreSQL and throughput
   from Redis.

### Why it scales horizontally

No instance keeps pipeline state in memory. Starting another instance joins both consumer groups;
Kafka rebalances the partitions and the new instance correlates against the same Redis and PostgreSQL
state. Opening an incident is a Redis `SET NX`, so two instances can never open the same one, and
counters are incremented in the database rather than read-modify-written.

### Failure handling

| Failure | What happens |
|---|---|
| Unparseable log record | Sent to `logs.raw.DLT` immediately, counted in `logintel_logs_malformed_total` |
| Processing error (DB/Redis down) | Retried with backoff (1s, 2s, 4s), then dead-lettered to `<topic>.DLT` |
| Incident insert fails after the Redis claim | Claim is released, so the retry can open the incident |
| Claude rate-limited, down, refuses or times out | Rule-based verdict is used; not cached, so a re-analysis can still use Claude |
| Error stops occurring | Incident auto-resolves after 2h without events |

Delivery is at-least-once: if a batch is redelivered after a crash, its events are counted again.

## Run it

Requires Docker.

```bash
docker compose up -d --build
```

| URL | What |
|---|---|
| http://localhost:8080 | Dashboard (click **Simulate 2,000 logs** to generate traffic) |
| http://localhost:3000 | Grafana - pipeline dashboard, no login needed |
| http://localhost:9090 | Prometheus |

Scale the pipeline and watch the per-instance panel in Grafana even out:

```bash
docker compose up -d --scale app=3
```

Optional settings go in a `.env` file - see [.env.example](.env.example). The ones you may need:

- `ANTHROPIC_API_KEY` - turns on Claude analysis. Without it everything still works, tagged by rules.
- `APP_PORT`, `POSTGRES_PORT`, ... - if a default host port is already in use.
- `MAVEN_CENTRAL_MIRROR` - if the image build cannot reach Maven Central.

### Run the app from your IDE

```bash
docker compose up -d kafka postgres redis
./mvnw spring-boot:run
```

The defaults in [application.yaml](src/main/resources/application.yaml) point at those containers
(Kafka on `localhost:29092`, PostgreSQL on `localhost:5433`). Set `SIMULATOR_ENABLED=true` to get the simulate button.

## API

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/logs` | Ingest `{ "events": [ ... ] }`, 1-1000 events → `202` |
| `GET` | `/api/v1/incidents?status=&severity=&service=&page=&size=` | List, newest activity first |
| `GET` | `/api/v1/incidents/{id}` | Incident with its sample events |
| `PATCH` | `/api/v1/incidents/{id}/status` | `{ "status": "ACKNOWLEDGED" \| "RESOLVED" \| "OPEN" }` |
| `POST` | `/api/v1/incidents/{id}/reanalyze` | Queue a fresh analysis, bypassing the cache |
| `GET` | `/api/v1/stats?minutes=30` | Counts and per-minute throughput for the dashboard |
| `POST` | `/api/v1/simulate?count=500` | Demo traffic (only when the simulator is enabled) |
| `GET` | `/actuator/prometheus` | Metrics |

Errors are returned as RFC 9457 problem details.

```bash
curl -X POST http://localhost:8080/api/v1/logs -H "Content-Type: application/json" -d '{
  "events": [{
    "service": "payment-service",
    "host": "node-2",
    "level": "ERROR",
    "message": "Failed to charge order 4711: could not acquire connection from pool within 30000ms",
    "stackTrace": "org.springframework.dao.DataAccessResourceFailureException: HikariPool-1 - Connection is not available"
  }]
}'
```

`service`, `level` and `message` are required; `timestamp` defaults to the time of receipt.

## LLM analysis

[ClaudeIncidentAnalyzer](src/main/java/com/ai/logintelligence/analysis/ClaudeIncidentAnalyzer.java)
sends the incident's signature, counts and up to five sample events, and receives the verdict through
structured outputs - the response is guaranteed to parse into the `Verdict` record, with the category
and severity restricted to the application's enums.

- **Cost control.** One call per signature, not per log line: dedup happens before the LLM, and
  verdicts are cached in Redis for 24h. Effort is set to `low`, which suits a classification task.
- **Untrusted input.** Log text is delimited and declared as data in the system prompt, and the
  schema leaves no free-form field that could change application behaviour.
- **Refusals.** Server-side fallbacks are enabled, so a request declined by a safety classifier
  (attack traffic in logs can trigger one) is retried on a fallback model by the API.
- **Model.** `claude-opus-5-5` by default; change it with `LLM_MODEL`.

## Configuration

All pipeline settings are under `pipeline.*` in
[application.yaml](src/main/resources/application.yaml): correlation window, minimum level, consumer
concurrency, sample limit, auto-resolve age, topic names and partition count, LLM model and cache TTL.

## Tests

```bash
./mvnw test
```

Unit tests cover fingerprinting, the correlation decisions (open / increment / reopen / release on
failure), the rule-based analyzer and the LLM prompt. They need no running infrastructure.

## Layout

```
src/main/java/com/ai/logintelligence
├── ingest        REST ingestion, Kafka producer
├── correlation   fingerprinting, Redis signature store, batch consumer
├── incident      JPA model, service, REST API, auto-resolve job
├── analysis      Claude + rule-based analyzers, verdict cache, analysis consumer
├── stats         Redis throughput counters, dashboard stats endpoint
├── simulator     demo traffic generator
└── config        typed properties, topics, retry/dead-letter policy
docker/           nginx load balancer, Prometheus, Grafana provisioning
```
