# Epic A — Query & Integration API

Feature backlog for exposing risk outcomes over HTTP: list/filter, fetch by `event_id`, Redis-first live lookup, OpenAPI contract, and smoke verification.

---

## Epic goal

Expose risk outcomes over HTTP so **operators and integrators** can list, filter, and fetch scores **without** hitting Cassandra, Redis, or PostgreSQL ad hoc — with a **stable contract** (OpenAPI) and a **repeatable smoke** path.

**Definition of done (epic):** With infra + engine + producer running, a client can:

1. List filtered risk scores (paginated).
2. Fetch one score by `event_id`, including a Redis-first “live” path where applicable.
3. Discover all operations via OpenAPI / Swagger UI.
4. Prove the path with an automated or scripted smoke.

---

## A1 — Risk score read API (PostgreSQL-backed)

| ID | Feature | Description |
|----|---------|-------------|
| **A1.1** | Schema alignment | Confirm API entity/DTOs match `RiskScore` fields persisted by `PostgresRiskScoreSink` (`event_id`, `risk_score`, `flagged`, `reasons`, `rule_version`, `scored_at` — adjust if the table differs). |
| **A1.2** | List risk scores (paginated) | `GET /api/v1/risk-scores` with cursor or offset/limit pagination. |
| **A1.3** | Filters (query params) | Support at minimum: `from` / `to` on `scored_at`, `flagged`, `minScore` / `maxScore`, `reason` (single or repeated). Optional `user_id` only if stored or joinable — if not in PG, document “not supported” or defer to A1.7. |
| **A1.4** | Sort | Default `scored_at DESC`; optional `sort=scoredAt,riskScore` + `order=asc\|desc`. |
| **A1.5** | Get by event_id (relational) | `GET /api/v1/risk-scores/{eventId}` reading PostgreSQL — canonical full record for audit. |
| **A1.6** | Error model | Consistent JSON errors: 400 validation, 404 not found, 500 with safe body (no stack traces in prod). |
| **A1.7** | Optional: user-scoped list | Only if `user_id` is available on stored rows or via a join; otherwise explicit “out of scope for v1” in OpenAPI. |

**Acceptance criteria (rollup):** Pagination works under load; filters match DB semantics; missing `event_id` returns 404; invalid ranges return 400.

---

## A2 — Real-time flag lookup (Redis-first, PostgreSQL fallback)

| ID | Feature | Description |
|----|---------|-------------|
| **A2.1** | Key contract | Document and implement the same key pattern as Flink’s `RedisRiskScoreSink` (e.g. `fraud:flagged:<event_id>` / HSET fields). |
| **A2.2** | Lookup endpoint | `GET /api/v1/risk-scores/{eventId}/live` (or `.../cache`) — try Redis first; on miss, load from PostgreSQL if present. |
| **A2.3** | Response metadata | Include `source: REDIS \| POSTGRES \| MISS` (or `cached: true/false`) so callers know freshness path. |
| **A2.4** | Miss behavior | 404 if neither Redis nor PG has the score (or 200 with empty body — **pick one** and document). |
| **A2.5** | Resilience | Redis down: degrade to PG-only with optional header `X-Cache-Status: BYPASS` / log warning — no hard failure of the whole request if PG succeeds. |

**Acceptance criteria:** When a record exists only in Redis (TTL not expired), endpoint returns it without requiring PG; when only PG has it, endpoint returns PG payload and indicates non-cache hit.

---

## A3 — OpenAPI + DTOs

| ID | Feature | Description |
|----|---------|-------------|
| **A3.1** | Springdoc (or equivalent) | `/v3/api-docs` + Swagger UI at `/swagger-ui.html` (or project-standard paths). |
| **A3.2** | Request/response DTOs | No internal entities leaked; explicit types for `reasons` (array of string), timestamps (ISO-8601). |
| **A3.3** | Documented query params | Every filter on list endpoint described with examples. |
| **A3.4** | Version prefix | All routes under `/api/v1/...` reflected in OpenAPI server base path or tags. |
| **A3.5** | Optional: codegen note | One-line README note for generating a TS client — optional, no codegen in repo unless desired. |

**Acceptance criteria:** Swagger UI can execute list + get + live lookup against running stack; schemas match actual JSON.

---

## A4 — E2E smoke & developer ergonomics

| ID | Feature | Description |
|----|---------|-------------|
| **A4.1** | Smoke script or test | After seed path (producer + engine), assert: ≥1 row in `risk_scores` (PG), optional Redis key for a flagged event, optional ES doc — **minimum bar: PG + one HTTP 200** on list or get. |
| **A4.2** | Make target | e.g. `make smoke-api` or `make e2e-api` documented in root README snippet. |
| **A4.3** | CI hook (optional) | GitHub Action job: build API, spin compose services, run smoke (may be `continue-on-error` if flaky). |
| **A4.4** | Seed timing | Document wait/retry for eventual consistency (Flink → sinks) so smoke isn’t flaky. |

**Acceptance criteria:** A new contributor can run one command after `infra-up` + engine + producer and see green smoke.

---

## Suggested build order

1. **A1.1 → A1.5 → A1.6** — core read path  
2. **A3.1 → A3.2** — contract while endpoints are still few  
3. **A1.2–A1.4** — list + filters + pagination  
4. **A2.x** — live lookup  
5. **A3.3–A3.5** — polish + docs  
6. **A4.x** — smoke last (locks in behavior)

---

## Out-of-scope guardrails

- **Authn/authz:** Out of scope for Epic A unless a separate mini-epic (e.g. Spring Security) is added.  
- **Elasticsearch-backed list:** Epic B; do not fold into A unless explicitly merged.  
- **Write APIs:** Manual overrides, false-positive marking — separate epic.

---

## Related docs

- [`RISK_ENGINE_PHASES.md`](RISK_ENGINE_PHASES.md) — Flink pipeline and sinks  
- Root [`README.md`](../README.md) — architecture and stack
