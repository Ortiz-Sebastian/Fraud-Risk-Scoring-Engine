# Risk Engine — Implementation Phases

This document is the authoritative implementation roadmap for the `risk-engine` module.
It is written for AI agents and developers picking up this work mid-stream.

---

## Project Context

**Module:** `risk-engine/`
**Entry point:** `com.riskengine.engine.StreamingJob`
**Run command:** `./gradlew :risk-engine:run` or `make run-engine`

The risk engine is an **Apache Flink 1.20** streaming job that:
1. Consumes `TransactionEvent` records from the Kafka topic `risk.transactions`
2. Applies fraud detection rules (windowed + stateful)
3. Produces `RiskScore` outputs
4. Writes results to PostgreSQL, Redis, and Elasticsearch

The **producer** (`producer/` module) is fully implemented and publishes `TransactionEvent`
JSON to Kafka, keyed by `user_id`. It supports two modes:
- `NORMAL` — 500 users, 50 merchants, realistic amount distribution
- `ATTACK` — velocity bursts, IP bursts, and new-device large purchases

All shared models and config utilities live in `common/`:
- `com.riskengine.common.model.TransactionEvent` — inbound event schema
- `com.riskengine.common.model.RiskScore` — outbound risk score schema
- `com.riskengine.common.config.AppConfig` — reads all connection params from env vars

Infrastructure (Kafka, PostgreSQL, Redis, Elasticsearch, Prometheus, Grafana)
is defined in `docker-compose.yml`. Start it with `make infra-up`.

---

## Key Technical Decisions Already Made

| Decision | Value | Rationale |
|---|---|---|
| Flink version | `1.20.1` | Stable LTS release |
| Kafka connector version | `3.4.0-1.20` | Connectors are versioned separately from Flink core since 1.17 |
| Kafka topic | `risk.transactions` | 6 partitions, keyed by `user_id` |
| Consumer group ID | `risk-engine` | Kafka tracks offsets per group |
| Starting offset | `OffsetsInitializer.latest()` | Skip history on first run; checkpoints take over after that |
| Checkpoint interval | 60 seconds | Balances recovery granularity vs. overhead |
| Serialization | Jackson + `JavaTimeModule` | Matches producer serialization exactly |
| Deserializer | `TransactionEventDeserializer` | Custom `DeserializationSchema`; `ObjectMapper` is `transient` (lazily initialized) to survive Flink operator graph serialization |

---

## Phases

### Phase 1 — Kafka Source + Deserialization ✅ COMPLETE

**Goal:** Prove the full read path works end-to-end before adding any logic.

**What was built:**
- `TransactionEventDeserializer.java` — Flink `DeserializationSchema` using Jackson to
  deserialize Kafka value bytes into `TransactionEvent` records
- `StreamingJob.java` — Full Flink pipeline: env setup → Kafka source → `events.print()` → `env.execute()`

**Files changed:**
- `risk-engine/src/main/java/com/riskengine/engine/StreamingJob.java`
- `risk-engine/src/main/java/com/riskengine/engine/TransactionEventDeserializer.java`
- `risk-engine/build.gradle` — added `logback-classic` runtime dep and fixed Kafka connector version
- `gradle.properties` — added `flinkKafkaConnectorVersion=3.4.0-1.20`

**How to verify:**
1. `make infra-up`
2. `make run-engine` — engine connects and waits
3. `make run-producer` — events should print to engine stdout, one per line, prefixed with subtask index (e.g. `1> TransactionEvent[...]`)

---

### Phase 2 — Pass-Through Cassandra Sink ✅ COMPLETE

**Goal:** Write every `TransactionEvent` that flows through directly into Cassandra,
with no transformation or scoring logic yet. Proves the full end-to-end write path
(Kafka → Flink → Cassandra) before adding complexity.

**Why Cassandra instead of PostgreSQL:**
Cassandra is the industry standard for high-throughput streaming sinks at companies like
Uber, Netflix, and Discord. PostgreSQL remains in the stack for the API layer (relational
queries via Spring JPA) — each database is used for what it is designed for. This also
mirrors real production architectures where a streaming engine writes to Cassandra and a
query API reads from a relational store or Redis.

**What to build:**
- A Cassandra keyspace `fraud_engine` and table `transaction_events` (CQL schema below)
- `CassandraTransactionSink.java` — a Flink `RichSinkFunction<TransactionEvent>` that opens
  a `CqlSession` in `open()`, executes a prepared INSERT on each event, and closes the
  session in `close()`
- Replace `events.print()` in `StreamingJob` with `events.addSink(new CassandraTransactionSink())`

**Cassandra connection details** (from `AppConfig`):
```
AppConfig.cassandraHost()     → localhost (env: CASSANDRA_HOST)
AppConfig.cassandraPort()     → 9042      (env: CASSANDRA_PORT)
AppConfig.cassandraKeyspace() → fraud_engine (env: CASSANDRA_KEYSPACE)
```

**Cassandra data model:**

The primary key design is intentional and important:
- **Partition key:** `user_id` — all events for a user live on the same node.
  This is what makes the Phase 3 velocity detection fast: a single-partition read gives
  all recent events for a user, already sorted by time.
- **Clustering key:** `event_ts DESC, event_id` — sorted newest-first within a partition.
  `event_id` is included to guarantee uniqueness when two events share the same millisecond.

```cql
CREATE KEYSPACE IF NOT EXISTS fraud_engine
    WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

CREATE TABLE IF NOT EXISTS fraud_engine.transaction_events (
    user_id     TEXT,
    event_ts    TIMESTAMP,
    event_id    TEXT,
    merchant_id TEXT,
    amount      DECIMAL,
    currency    TEXT,
    ip          TEXT,
    device_id   TEXT,
    location    TEXT,
    ingested_at TIMESTAMP,
    PRIMARY KEY (user_id, event_ts, event_id)
) WITH CLUSTERING ORDER BY (event_ts DESC, event_id ASC);
```

**Implementation notes:**
- Use the DataStax OSS Java driver `com.datastax.oss:java-driver-core` (version in
  `gradle.properties` as `cassandraDriverVersion`)
- `CqlSession` is thread-safe and expensive to create — open it once in `open()` and
  reuse it across all `invoke()` calls. Close it in `close()`.
- Use a `PreparedStatement` (prepared once in `open()`) for all inserts — never build
  CQL strings dynamically inside `invoke()`. Prepared statements are cached on the
  Cassandra server and are significantly faster.
- Cassandra INSERT is naturally idempotent (last-write-wins on the same primary key),
  so no special conflict handling is needed.
- The `RichSinkFunction` lifecycle methods (`open` / `close`) are called by Flink once
  per parallel subtask, not once per record.

**How to verify:**
```bash
# Wait ~30 seconds after first start for Cassandra to be ready, then:
docker exec -it cassandra cqlsh -e \
  "SELECT COUNT(*) FROM fraud_engine.transaction_events;"

# See a sample of records:
docker exec -it cassandra cqlsh -e \
  "SELECT user_id, event_ts, amount FROM fraud_engine.transaction_events LIMIT 10;"
```

---

### Phase 3 — Velocity Detection (5-min Sliding Window per `user_id`) ✅ COMPLETE

**Goal:** Detect when a single user submits more than N transactions within a 5-minute window.
This directly catches the `ATTACK` mode's velocity burst pattern (3 hardcoded user IDs
firing at high rates).

**What was built:**
- `WatermarkStrategy` switched from `noWatermarks()` to event-time watermarks based on `event_ts`,
  with a bounded-out-of-orderness of 5 seconds
- `keyBy(TransactionEvent::userId)` to partition the stream by user
- A 5-minute sliding window (slide every 1 minute) using `SlidingEventTimeWindows`
- `VelocityDetector.CountAggregator` — lightweight O(1) per-record accumulation (count + last event_id)
- `VelocityDetector.WindowEvaluator` — post-window threshold check; emits `RiskScore` when triggered
- Threshold is configurable via `VELOCITY_THRESHOLD` env var (default 10)
- `RiskScore` stream written to `fraud_engine.risk_scores` Cassandra table

**Why Cassandra (not PostgreSQL) for `risk_scores`:**
Consistent with the architecture decision in `ARCHITECTURE.md`: Flink writes directly to
Cassandra (high-throughput streaming sink); PostgreSQL is the API layer's responsibility
and must not be written to by Flink directly. The `event_id` partition key makes re-scoring
idempotent via Cassandra's last-write-wins semantics.

**Files changed:**
- `risk-engine/src/main/java/com/riskengine/engine/StreamingJob.java` — watermark strategy, velocity pipeline
- `risk-engine/src/main/java/com/riskengine/engine/fraud/VelocityDetector.java` — new file
- `risk-engine/src/main/java/com/riskengine/engine/sink/CassandraRiskScoreSink.java` — new file
- `common/src/main/java/com/riskengine/common/config/AppConfig.java` — added `velocityThreshold()`

**Cassandra table created automatically on startup:**
```cql
CREATE TABLE IF NOT EXISTS fraud_engine.risk_scores (
    event_id     text,
    risk_score   int,
    flagged      boolean,
    reasons      list<text>,
    rule_version text,
    scored_at    timestamp,
    PRIMARY KEY (event_id)
);
```

**Expected `RiskScore` output for a velocity hit:**
```json
{
  "event_id": "<last event_id in window>",
  "risk_score": 75,
  "flagged": true,
  "reasons": ["USER_VELOCITY"],
  "rule_version": "v1.0"
}
```

**How to verify:**
```bash
# Run in attack mode to trigger velocity bursts quickly
make run-producer-attack

# After ~5 minutes of attack traffic, query Cassandra:
docker exec -it cassandra cqlsh -e \
  "SELECT event_id, risk_score, flagged, reasons FROM fraud_engine.risk_scores LIMIT 20;"

# Confirm USER_VELOCITY scores are present
docker exec -it cassandra cqlsh -e \
  "SELECT COUNT(*) FROM fraud_engine.risk_scores WHERE flagged = true ALLOW FILTERING;"
```

---

### Phase 4 — IP Burst Detection (2-min Sliding Window per IP) ✅ COMPLETE

**Goal:** Detect when multiple distinct users transact from the same IP address within
2 minutes. Catches the `ATTACK` mode's IP burst pattern (3 hardcoded "hot" IPs routing
normal users' traffic).

**What was built:**
- A second keyed stream: `keyBy(TransactionEvent::ip)` in `StreamingJob`
- A 2-minute sliding window (slide every 30 seconds) counting distinct `user_id` values per IP
- `IpBurstDetector.IpBurstAccumulator` — maintains a `HashSet<String>` of distinct user IDs
  and the last event_id seen in the window
- `IpBurstDetector.DistinctUserAggregator` — `AggregateFunction` that adds user_ids to the set
  and tracks the last event_id per record
- `IpBurstDetector.WindowEvaluator` — post-window threshold check; emits `RiskScore` when the
  number of distinct users exceeds the threshold
- Threshold is configurable via `IP_BURST_THRESHOLD` env var (default 5)
- IP burst `RiskScore` stream is merged with velocity scores via `union()` before a single
  `CassandraRiskScoreSink`

**Files changed:**
- `risk-engine/src/main/java/com/riskengine/engine/StreamingJob.java` — IP burst pipeline, union of score streams
- `risk-engine/src/main/java/com/riskengine/engine/fraud/IpBurstDetector.java` — new file
- `common/src/main/java/com/riskengine/common/config/AppConfig.java` — added `ipBurstThreshold()`

**Expected `RiskScore` output for an IP burst hit:**
```json
{
  "event_id": "<last event_id in window>",
  "risk_score": 80,
  "flagged": true,
  "reasons": ["IP_BURST"],
  "rule_version": "v1.0"
}
```

**How to verify:**
```bash
# Run in attack mode to trigger IP bursts quickly
make run-producer-attack

# After ~2 minutes of attack traffic, query Cassandra:
docker exec -it cassandra cqlsh -e \
  "SELECT event_id, risk_score, flagged, reasons FROM fraud_engine.risk_scores LIMIT 20;"

# Confirm IP_BURST scores are present
docker exec -it cassandra cqlsh -e \
  "SELECT COUNT(*) FROM fraud_engine.risk_scores WHERE flagged = true ALLOW FILTERING;"
```

---

### Phase 5 — Device Profiling (New Device + Large Purchase) ⬜ NOT STARTED

**Goal:** Flag when a brand-new device ID immediately makes a large purchase.
Catches the `ATTACK` mode's new-device attack pattern (fresh UUID device ID, $500–$3000 purchase).

**What to build:**
- Stateful `KeyedProcessFunction` keyed by `device_id`
- `ValueState<Boolean>` tracking whether this device has been seen before
- On first event: store device ID in state and record the amount
- If the first-ever transaction for a device exceeds a threshold (e.g. $500), emit a flag
- Session window with inactivity timeout to eventually clear state for dormant devices

---

### Phase 6 — Deduplication + Watermarking ⬜ NOT STARTED

**Goal:** Harden the pipeline against duplicate events and late-arriving records.

**What to build:**
- Deduplication by `event_id` using Flink's `KeyedProcessFunction` with `ValueState<Boolean>`
  and a TTL (e.g. 1 hour) to bound state size
- Tune `WatermarkStrategy` with appropriate `forBoundedOutOfOrderness` duration
- A side output for events that arrive after the watermark has passed (late events)
  — log them or write to a `late_events` table for analysis

---

### Phase 7 — Multi-Sink (PostgreSQL + Redis + Elasticsearch) ⬜ NOT STARTED

**Goal:** Fan out `RiskScore` results to all three sinks simultaneously.

**What to build:**
- Use Flink's `split` or multiple `addSink` calls on the `DataStream<RiskScore>` output stream
- **PostgreSQL** — upsert `risk_scores` table by `event_id`
- **Redis** — store `HSET fraud:flagged:<event_id>` with a TTL of 24 hours for real-time
  lookups by the API; use Jedis (already in `build.gradle`)
- **Elasticsearch** — index each `RiskScore` document into `risk-scores` index
  for full-text search and audit; use the `co.elastic.clients:elasticsearch-java` client
  (already in `build.gradle`)

---

## Current File Map

```
risk-engine/
└── src/main/java/com/riskengine/engine/
    ├── StreamingJob.java                        ← Phase 1+2+3+4 ✅ — Flink env, Kafka source, velocity + IP burst pipelines
    ├── TransactionEventDeserializer.java        ← Phase 1 ✅ — Jackson deserializer for TransactionEvent
    ├── fraud/
    │   ├── VelocityDetector.java               ← Phase 3 ✅ — CountAggregator + WindowEvaluator
    │   └── IpBurstDetector.java                ← Phase 4 ✅ — DistinctUserAggregator + WindowEvaluator
    └── sink/
        ├── CassandraTransactionSink.java        ← Phase 2 ✅ — RichSinkFunction writing raw events
        └── CassandraRiskScoreSink.java          ← Phase 3 ✅ — RichSinkFunction writing risk scores

common/
└── src/main/java/com/riskengine/common/
    ├── config/AppConfig.java                   ← velocityThreshold() (Phase 3), ipBurstThreshold() (Phase 4)
    └── model/
        ├── TransactionEvent.java
        └── RiskScore.java
```

As phases are completed, update this section with new files.

---

## Running the Engine

```bash
# Start infrastructure
make infra-up

# Start the Flink job
make run-engine

# Start the producer (separate terminal)
make run-producer          # 100/sec, normal mode
make run-producer-attack   # 500/sec, attack mode
make run-producer-burst    # 2000/sec for 30 seconds
```

## Environment Variables

All connection config is read by `AppConfig` from environment variables.
The `.env` file at the project root contains the defaults.

| Variable | Default | Used by |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Phases 1–7 |
| `KAFKA_TOPIC` | `risk.transactions` | Phases 1–7 |
| `CASSANDRA_HOST` | `localhost` | Phases 2–7 |
| `CASSANDRA_PORT` | `9042` | Phases 2–7 |
| `CASSANDRA_KEYSPACE` | `fraud_engine` | Phases 2–7 |
| `POSTGRES_HOST` | `localhost` | API only |
| `POSTGRES_PORT` | `5432` | API only |
| `POSTGRES_USER` | `fraud_user` | API only |
| `POSTGRES_PASSWORD` | `fraud_pass` | API only |
| `POSTGRES_DB` | `fraud_db` | API only |
| `REDIS_HOST` | `localhost` | Phase 7 |
| `REDIS_PORT` | `6379` | Phase 7 |
| `ELASTICSEARCH_HOST` | `localhost` | Phase 7 |
| `ELASTICSEARCH_PORT` | `9200` | Phase 7 |
| `FLINK_CHECKPOINT_DIR` | `./checkpoint` | Phases 1–7 |
| `VELOCITY_THRESHOLD` | `10` | Phase 3–7 |
| `IP_BURST_THRESHOLD` | `5` | Phase 4–7 |
