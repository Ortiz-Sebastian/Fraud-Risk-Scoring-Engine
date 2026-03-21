package com.riskengine.engine.sink;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.riskengine.common.config.AppConfig;
import com.riskengine.common.model.RiskScore;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.time.Instant;

/**
 * Flink sink that writes {@link RiskScore} records to Cassandra.
 *
 * <p>Table design:
 * <pre>
 *   PARTITION KEY → event_id
 * </pre>
 * One row per scored event. Cassandra last-write-wins semantics make re-scoring
 * the same event_id naturally idempotent — essential for Flink checkpoint replay.
 *
 * <p>The keyspace is shared with {@link CassandraTransactionSink} ({@code fraud_engine})
 * so a single Cassandra cluster serves both raw events and computed scores.
 *
 * <p>{@link CqlSession} and {@link PreparedStatement} are created once per Flink subtask
 * in {@link #open} and reused for the lifetime of the subtask.
 */
public class CassandraRiskScoreSink extends RichSinkFunction<RiskScore> {

    private static final Logger log = LoggerFactory.getLogger(CassandraRiskScoreSink.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS %s.risk_scores (
                event_id     text,
                risk_score   int,
                flagged      boolean,
                reasons      list<text>,
                rule_version text,
                scored_at    timestamp,
                PRIMARY KEY (event_id)
            )
            """;

    private static final String INSERT = """
            INSERT INTO %s.risk_scores
                (event_id, risk_score, flagged, reasons, rule_version, scored_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private transient CqlSession session;
    private transient PreparedStatement insertStatement;

    @Override
    public void open(Configuration parameters) {
        String host = AppConfig.cassandraHost();
        int port = AppConfig.cassandraPort();
        String keyspace = AppConfig.cassandraKeyspace();

        log.info("Connecting to Cassandra for risk scores | host={} port={} keyspace={}", host, port, keyspace);

        session = CqlSession.builder()
                .addContactPoint(new InetSocketAddress(host, port))
                .withLocalDatacenter("datacenter1")
                .build();

        session.execute(String.format(CREATE_TABLE, keyspace));
        insertStatement = session.prepare(String.format(INSERT, keyspace));

        log.info("Cassandra risk-score sink ready | keyspace={}", keyspace);
    }

    @Override
    public void invoke(RiskScore score, Context context) {
        session.execute(insertStatement.bind(
                score.eventId(),
                score.riskScore(),
                score.flagged(),
                score.reasons(),
                score.ruleVersion(),
                Instant.now()
        ));

        log.debug("Risk score written | event_id={} score={} flagged={} reasons={}",
                score.eventId(), score.riskScore(), score.flagged(), score.reasons());
    }

    @Override
    public void close() {
        if (session != null && !session.isClosed()) {
            session.close();
            log.info("Cassandra risk-score session closed");
        }
    }
}
