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
 * Stores watermark-late events for observability and forensic analysis.
 */
public class CassandraLateEventSink extends RichSinkFunction<TransactionEvent> {

    private static final Logger log = LoggerFactory.getLogger(CassandraLateEventSink.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS %s.late_events (
                event_id        text,
                user_id         text,
                event_ts        timestamp,
                merchant_id     text,
                amount          decimal,
                currency        text,
                ip              text,
                device_id       text,
                location        text,
                routed_at       timestamp,
                observed_late   boolean,
                PRIMARY KEY (event_id)
            )
            """;

    private static final String INSERT = """
            INSERT INTO %s.late_events
                (event_id, user_id, event_ts, merchant_id, amount, currency, ip, device_id, location, routed_at, observed_late)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private transient CqlSession session;
    private transient PreparedStatement insertStatement;

    @Override
    public void open(Configuration parameters) {
        String host = AppConfig.cassandraHost();
        int port = AppConfig.cassandraPort();
        String keyspace = AppConfig.cassandraKeyspace();

        session = CqlSession.builder()
                .addContactPoint(new InetSocketAddress(host, port))
                .withLocalDatacenter("datacenter1")
                .build();

        session.execute(String.format(CREATE_TABLE, keyspace));
        insertStatement = session.prepare(String.format(INSERT, keyspace));
        log.info("Cassandra late-event sink ready | keyspace={}", keyspace);
    }

    @Override
    public void invoke(TransactionEvent event, Context context) {
        session.execute(insertStatement.bind(
                event.eventId(),
                event.userId(),
                event.eventTs(),
                event.merchantId(),
                event.amount(),
                event.currency(),
                event.ip(),
                event.deviceId(),
                event.location(),
                Instant.now(),
                true
        ));
    }

    @Override
    public void close() {
        if (session != null && !session.isClosed()) {
            session.close();
        }
    }
}
