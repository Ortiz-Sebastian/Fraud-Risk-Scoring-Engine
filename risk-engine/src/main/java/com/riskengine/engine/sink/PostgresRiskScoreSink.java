package com.riskengine.engine.sink;

import com.riskengine.common.config.AppConfig;
import com.riskengine.common.model.RiskScore;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * Upserts {@link RiskScore} rows into PostgreSQL by {@code event_id} (idempotent under replay).
 */
public class PostgresRiskScoreSink extends RichSinkFunction<RiskScore> {

    private static final Logger log = LoggerFactory.getLogger(PostgresRiskScoreSink.class);

    /**
     * Serializes {@code CREATE TABLE} across parallel sink subtasks. Concurrent DDL for the same
     * table name can race on PostgreSQL's composite type for the row shape ({@code pg_type}).
     */
    private static final long RISK_SCORES_DDL_LOCK = 5_928_174_661_023L;

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS risk_scores (
                event_id     TEXT PRIMARY KEY,
                risk_score   INT NOT NULL,
                flagged      BOOLEAN NOT NULL,
                reasons      TEXT[] NOT NULL,
                rule_version TEXT NOT NULL,
                scored_at    TIMESTAMPTZ NOT NULL
            )
            """;

    private static final String UPSERT = """
            INSERT INTO risk_scores
                (event_id, risk_score, flagged, reasons, rule_version, scored_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (event_id) DO UPDATE SET
                risk_score = EXCLUDED.risk_score,
                flagged = EXCLUDED.flagged,
                reasons = EXCLUDED.reasons,
                rule_version = EXCLUDED.rule_version,
                scored_at = EXCLUDED.scored_at
            """;

    private transient Connection connection;
    private transient PreparedStatement upsertStatement;

    @Override
    public void open(Configuration parameters) throws Exception {
        String url = AppConfig.postgresUrl();
        log.info("Connecting to PostgreSQL for risk scores | url={}", url);

        connection = DriverManager.getConnection(url, AppConfig.postgresUser(), AppConfig.postgresPassword());
        connection.setAutoCommit(true);

        try (var st = connection.createStatement()) {
            st.execute("SELECT pg_advisory_lock(" + RISK_SCORES_DDL_LOCK + ")");
            try {
                st.execute(CREATE_TABLE);
            } finally {
                st.execute("SELECT pg_advisory_unlock(" + RISK_SCORES_DDL_LOCK + ")");
            }
        }

        upsertStatement = connection.prepareStatement(UPSERT);
        log.info("PostgreSQL risk-score sink ready");
    }

    @Override
    public void invoke(RiskScore score, Context context) throws Exception {
        List<String> reasons = score.reasons() != null ? score.reasons() : List.of();
        String[] reasonArray = reasons.toArray(String[]::new);
        Array sqlReasons = connection.createArrayOf("text", reasonArray);

        upsertStatement.setString(1, score.eventId());
        upsertStatement.setInt(2, score.riskScore());
        upsertStatement.setBoolean(3, score.flagged());
        upsertStatement.setArray(4, sqlReasons);
        upsertStatement.setString(5, score.ruleVersion());
        upsertStatement.setTimestamp(6, Timestamp.from(Instant.now()));

        try {
            upsertStatement.executeUpdate();
        } finally {
            sqlReasons.free();
        }

        log.debug("Risk score upserted to PostgreSQL | event_id={}", score.eventId());
    }

    @Override
    public void close() throws Exception {
        if (upsertStatement != null) {
            upsertStatement.close();
        }
        if (connection != null && !connection.isClosed()) {
            connection.close();
            log.info("PostgreSQL risk-score connection closed");
        }
    }
}
