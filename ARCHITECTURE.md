# Architecture — Real-Time Fraud & Risk Scoring Engine

Distributed Streaming Platform Architecture

---

## 1. System Overview

This system processes high-volume transaction events in real-time to compute risk scores and detect fraud patterns.

It is designed to demonstrate:

- Event-driven architecture
- Stateful stream processing
- Exactly-once semantics (practical implementation)
- Replayable pipelines
- Partitioning + skew mitigation
- Multi-sink consistency
- Production observability

---

## 2. Architecture at a Glance

```
              ┌─────────────────────────────┐
              │  Transaction Generator      │
              │  (Normal + Attack Modes)    │
              └──────────────┬──────────────┘
                             │
                             ▼
                  ┌────────────────────┐
                  │     Apache Kafka    │
                  │  risk.transactions  │
                  │  (N partitions)     │
                  └──────────┬──────────┘
                             │
                             ▼
           ┌────────────────────────────────┐
           │  Flink DataStream API           │
           │  Fraud & Risk Engine            │
           │                                 │
           │  • Velocity Windows             │
           │  • IP Burst Detection           │
           │  • Device Profiling             │
           │  • Stateful Scoring             │
           │  • Deduplication                │
           │  • Watermarking                 │
           └──────────────┬──────────────────┘
                          │
          ┌───────────────┼────────────────────┐
          ▼               ▼                    ▼
 ┌────────────────┐  ┌────────────────┐  ┌────────────────┐
 │  Cassandra     │  │ Redis Cache    │  │ Elasticsearch  │
 │ (high-throughput  │ (hot flags,   │  │ (search+audit) │
 │  event + score │  │  real-time     │  └────────────────┘
 │  durable store)│  │  lookups)      │
 └────────────────┘  └────────────────┘
          │
          ▼
 ┌────────────────┐
 │  PostgreSQL    │
 │  (API layer    │
 │   queries via  │
 │   Spring JPA)  │
 └───────┬────────┘
         │
         ▼
 ┌────────────────┐
 │  Prometheus    │
 │  + Grafana     │
 └────────────────┘
```

---

## 3. Component Boundaries

Clear boundaries prevent incorrect assumptions and improve reasoning about failure modes.

### A. Producer → Kafka

**Responsibility**
- Generates transaction events
- Simulates normal and attack traffic
- Ensures schema compliance

**Boundary**
- Producers do not know about downstream state
- No direct DB writes
- Only publish to Kafka topic

### B. Kafka → Flink

**Responsibility**
- Durable event buffer
- Partitioned parallelism
- Offset management

**Boundary**
- Flink reads from Kafka using the Flink Kafka connector
- No synchronous coupling
- Replay is handled via offset reset

### C. Flink DataStream (Core Processing Layer)

**Responsibility**
- Stateful risk evaluation
- Windowed aggregations
- Deduplication (`event_id`)
- Risk scoring logic
- Watermark-based late event handling
- Checkpointing state

**Boundary**
- Flink does NOT directly call external systems during stream processing
- Only writes to sinks
- Uses checkpoint directory for recovery

### D. Output Sinks

Each sink has a clear, isolated purpose. No sink is used for more than one role.

**Cassandra** — High-throughput durable store for:
- Raw `transaction_events` (partitioned by `user_id`, clustered by `event_ts DESC`)
- Computed `risk_scores` (partitioned by `event_id`)
- Chosen because write throughput at 10K–500K events/sec is its primary design goal
- Partition key design directly supports Phase 3 velocity detection (single-partition reads per user)

**Redis** — Low-latency real-time access to:
- Recently flagged transactions (TTL 24h)
- Top risky IPs and users
- Used by the authorization API for sub-5ms risk lookups at decision time

**Elasticsearch** — Searchable audit trail:
- Full-text search for fraud investigation
- Historical pattern analysis
- Investigation UI queries

**PostgreSQL** — Relational query layer for the API:
- Managed by the `api/` module via Spring Data JPA
- Stores aggregated or materialized views suitable for complex relational queries
- Not written to directly by Flink — this boundary keeps the streaming layer and query layer decoupled

**Boundary**
- No cross-sink transactions
- Each sink write is idempotent
- Failures are isolated per sink
- Flink writes to Cassandra, Redis, and Elasticsearch; the API owns PostgreSQL

### E. Observability Layer

- Kafka consumer lag
- Flink checkpoint duration
- State store size
- Risk flag rate
- Throughput per partition

> This layer only observes — it does not influence processing logic.

---

## 4. Data Flow

### Normal Processing

1. Producer generates transaction event
2. Event published to Kafka
3. Flink consumes event
4. Apply:
   - Deduplication
   - Window aggregations
   - Stateful checks
5. Compute `risk_score`
6. Write result to sinks
7. Metrics emitted

### Replay / Backfill

1. Stop streaming job
2. Reset Kafka offsets
3. Restart job with new rule version
4. Recompute historical scores
5. Upsert results idempotently

---

## 5. Partitioning Strategy

**Default partition key:** `user_id`

Why:
- Velocity rules are user-centric
- Maintains ordering per user

**Alternative experiment:** partition by `ip`
- Demonstrates skew impact

**Skew mitigation:**
- Salting high-frequency keys
- Increasing partition count
- Adaptive aggregation

---

## 6. Stateful Processing Model

### Window Types

| Window            | Duration   | Purpose          |
| ----------------- | ---------- | ---------------- |
| Sliding window    | 5 minutes  | Velocity rules   |
| Sliding window    | 2 minutes  | IP burst         |
| Session window    | Inactivity | Device profiling |

### State Management

- Flink state backend (RocksDB)
- Watermarks for late events
- Checkpoint directory for recovery
- Savepoints for planned upgrades

---

## 7. Failure Handling Strategy

| Failure Type       | Mitigation                                          |
| ------------------ | --------------------------------------------------- |
| Flink TaskManager crash | Restart from checkpoint; resume from last offset |
| Duplicate events   | Deduplicate using `event_id`                        |
| Sink failure       | Retry with exponential backoff; idempotent writes   |
| Kafka backpressure | Flink back-pressure mechanism; monitor lag; scale parallelism |

---

## 8. Exactly-Once Semantics (Practical)

True global exactly-once across multiple sinks is complex. This system achieves:

- Exactly-once read (Kafka offsets + Flink checkpointing)
- Idempotent writes per sink
- Deduplication by `event_id`
- Deterministic risk computation

---

## 9. Scalability Model

**Horizontal scaling via:**
- Increasing Kafka partitions
- Increasing Flink task parallelism
- Scaling TaskManager slots
- Stateless producer scaling

**State grows proportional to:**
- Active users
- Window size
- Cardinality of keys

---

## 10. Deployment Boundaries

| Environment | Stack                                          |
| ----------- | ---------------------------------------------- |
| Development | Docker Compose                                 |
| Production  | Kubernetes, external Kafka, S3/GCS checkpoint  |

---

## 11. Design Principles

- Event-driven, not request-driven
- Stateless producers
- Stateful processors
- Isolated sinks
- Idempotent operations
- Observable everything
- Replayable always
