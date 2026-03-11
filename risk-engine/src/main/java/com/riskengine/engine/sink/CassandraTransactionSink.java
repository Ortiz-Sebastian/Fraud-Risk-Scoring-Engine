package com.riskengine.engine.sink;

import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.cql.PreparedStatement;
import com.riskengine.common.config.AppConfig;
import com.riskengine.common.model.TransactionEvent;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.time.Instant;

/**
 * Flink sink that writes TransactionEvent records to Cassandra.
 *
 * Primary key design:
 *   PARTITION KEY  → user_id   (all events for a user on the same node; enables fast velocity lookups)
 *   CLUSTERING KEY → event_ts DESC, event_id (newest events first; event_id breaks timestamp ties)
 *
 * The CqlSession and PreparedStatement are created once per Flink subtask in open() and
 * reused across all invoke() calls. Creating a session per record would be catastrophically slow.
 *
 * The keyspace and table are created on startup if they do not already exist, which makes
 * the engine self-bootstrapping in development.
 */
public class CassandraTransactionSink extends RichSinkFunction<TransactionEvent> {

    private static final Logger log = LoggerFactory.getLogger(CassandraTransactionSink.class);

    private static final String CREATE_KEYSPACE = """
            CREATE KEYSPACE IF NOT EXISTS %s
            WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1}
            """;

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS %s.transaction_events (
                user_id     text,
                event_ts    timestamp,
                event_id    text,
                merchant_id text,
                amount      decimal,
                currency    text,
                ip          text,
                device_id   text,
                location    text,
                ingested_at timestamp,
                PRIMARY KEY (user_id, event_ts, event_id)
            ) WITH CLUSTERING ORDER BY (event_ts DESC, event_id ASC)
            """;

    private static final String INSERT = """
            INSERT INTO %s.transaction_events
                (user_id, event_ts, event_id, merchant_id, amount, currency, ip, device_id, location, ingested_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private transient CqlSession session;
    private transient PreparedStatement insertStatement;

    @Override
    public void open(Configuration parameters) {
        String host = AppConfig.cassandraHost();
        int port = AppConfig.cassandraPort();
        String keyspace = AppConfig.cassandraKeyspace();

        log.info("Connecting to Cassandra | host={} port={} keyspace={}", host, port, keyspace);

        session = CqlSession.builder()
                .addContactPoint(new InetSocketAddress(host, port))
                .withLocalDatacenter("datacenter1")
                .build();

        session.execute(String.format(CREATE_KEYSPACE, keyspace));
        session.execute(String.format(CREATE_TABLE, keyspace));

        insertStatement = session.prepare(String.format(INSERT, keyspace));

        log.info("Cassandra sink ready | keyspace={}", keyspace);
    }

    @Override
    public void invoke(TransactionEvent event, Context context) {
        session.execute(insertStatement.bind(
                event.userId(),
                event.eventTs(),
                event.eventId(),
                event.merchantId(),
                event.amount(),
                event.currency(),
                event.ip(),
                event.deviceId(),
                event.location(),
                Instant.now()
        ));
    }

    @Override
    public void close() {
        if (session != null && !session.isClosed()) {
            session.close();
            log.info("Cassandra session closed");
        }
    }
}
