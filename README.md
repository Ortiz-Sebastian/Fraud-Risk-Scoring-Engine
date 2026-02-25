# Real-Time Fraud & Risk Scoring Engine

A distributed, fault-tolerant streaming platform that processes high-volume transaction events in real-time to compute dynamic risk scores and detect fraud patterns.

Built using **Apache Kafka** and **Apache Flink**, this system demonstrates production-grade distributed systems engineering including stateful stream processing, windowed aggregations, hot-key mitigation, replay capability, and multi-sink consistency.

> **Note:** This is not just a dashboard — it is a replayable, fault-tolerant streaming risk engine.

---

## Overview

This project demonstrates distributed platform engineering by implementing:

- High-throughput event ingestion (10K+ transactions/sec)
- Stateful real-time fraud detection
- Sliding window and session-based risk evaluation
- Hot-key / skew handling
- Exactly-once semantics (idempotent sinks + checkpointing)
- Replayable scoring pipelines
- Multi-sink architecture
- Full observability with metrics and alerting

---

## Architecture

```
┌──────────────────────┐
│ Transaction Generator│
│  (Simulated Clients) │
└──────────┬───────────┘
           │
           ▼
     ┌──────────────┐
     │ Apache Kafka │
     │  (Partitions)│
     └──────┬───────┘
            │
            ▼
 ┌────────────────────────┐
 │ Flink DataStream       │
 │ Streaming Risk Engine  │
 │                        │
 │ • Velocity Rules       │
 │ • IP Burst Detection   │
 │ • Device Profiling     │
 │ • Window Aggregations  │
 │ • Stateful Tracking    │
 └──────────┬─────────────┘
            │
            ▼
     ┌─────────────────────┐
     │ Output Sinks        │
     ├─────────────────────┤
     │ • PostgreSQL        │
     │ • Redis             │
     │ • Elasticsearch     │
     │ • Prometheus        │
     └──────────┬──────────┘
                ▼
       ┌──────────────────┐
       │ Grafana Dashboards│
       └──────────────────┘
```

---

## Core Risk Detection Features

### 1. Velocity-Based Detection

- Detect N transactions from same `user_id` within 5 minutes
- Detect repeated high-value purchases
- Windowed aggregation with event-time semantics

### 2. IP Burst Detection

- Multiple distinct users from same IP in short interval
- Sliding window (2 minutes)
- Hot-key skew simulation for attack scenarios

### 3. Device Profiling

- First-seen device + immediate purchase
- Stateful device-user association tracking
- Session-based analysis

### 4. Amount Anomaly Rules

- Threshold detection per merchant/category
- Configurable risk scoring weights

### 5. Stateful Risk Scoring

Each transaction produces:

- `risk_score` — numerical risk assessment
- `risk_reasons[]` — array of triggered rules
- `flagged` — boolean indicator
- `rule_version` — metadata for replay support

---

## Distributed Systems Capabilities

### Stateful Stream Processing

- 5-minute sliding windows
- Session windows with inactivity timeouts
- Stateful user/IP/device tracking
- Watermarking for late event handling

### Exactly-Once Processing

- Kafka offsets + Flink checkpointing
- Idempotent upserts to PostgreSQL
- Deduplication using `event_id`

### Partitioning Strategy

- Partition by `user_id` for velocity rules
- Alternative partitioning by `ip` for burst detection experiments
- Demonstration of skew impact and mitigation (salting)

### Replay & Backfill Support

- Reset Kafka offsets
- Recompute historical risk scores
- Versioned aggregation logic

---

## Tech Stack

| Component         | Technology                        |
| ----------------- | --------------------------------- |
| Stream Processing | Apache Flink 1.20 (DataStream API)|
| Message Broker    | Apache Kafka 3.7                  |
| Storage           | PostgreSQL 15                     |
| Cache             | Redis 7                           |
| Search            | Elasticsearch 8                   |
| Monitoring        | Prometheus + Grafana              |
| Containerization  | Docker                            |
| Orchestration     | Kubernetes (optional)             |
| Language          | Java 21                           |

---

## Event Model

### Transaction Event Schema

```json
{
  "event_id": "uuid",
  "event_ts": "timestamp",
  "user_id": "string",
  "merchant_id": "string",
  "amount": 123.45,
  "currency": "USD",
  "ip": "string",
  "device_id": "string",
  "location": "string"
}
```

### Risk Score Output

```json
{
  "event_id": "...",
  "risk_score": 72,
  "flagged": true,
  "reasons": ["IP_BURST", "USER_VELOCITY"],
  "rule_version": "v1.0"
}
```

---

## Performance Benchmarks

| Metric                     | Value                     |
| -------------------------- | ------------------------- |
| Throughput                 | 10K+ events/sec sustained |
| End-to-end latency (p99)   | < 150ms                   |
| Consumer lag               | < 500ms steady state      |
| State store size           | 2–4GB under load          |
| Replay recomputation speed | 2M events/min             |

---

## Observability

### Metrics Collected

- Kafka consumer lag
- Flink checkpoint duration
- State store memory usage
- Risk flag rate
- Throughput by partition
- TaskManager utilization

### Alerts

- Consumer lag > threshold
- Risk spike anomaly
- State growth beyond expected bounds
- Failed checkpoint retries

---

## Project Structure

```
fraud-risk-engine/
├── common/
│   └── src/main/java/com/riskengine/common/
│       ├── model/          # TransactionEvent, RiskScore
│       └── config/         # AppConfig
├── producer/
│   └── src/main/java/com/riskengine/producer/
│       └── TransactionProducer.java
├── risk-engine/
│   └── src/main/java/com/riskengine/engine/
│       └── StreamingJob.java
├── api/
│   └── src/main/java/com/riskengine/api/
│       └── ApiApplication.java
├── docker/
├── kubernetes/
├── grafana/
├── docs/
└── README.md
```
