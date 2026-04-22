package com.riskengine.engine.sink;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.riskengine.common.config.AppConfig;
import com.riskengine.common.model.RiskScore;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Indexes each {@link RiskScore} into Elasticsearch for search and audit.
 */
public class ElasticsearchRiskScoreSink extends RichSinkFunction<RiskScore> {

    private static final Logger log = LoggerFactory.getLogger(ElasticsearchRiskScoreSink.class);

    private transient RestClientTransport transport;
    private transient ElasticsearchClient client;

    @Override
    public void open(Configuration parameters) {
        String url = AppConfig.elasticsearchUrl();
        log.info("Connecting to Elasticsearch for risk scores | url={}", url);

        RestClient restClient = RestClient.builder(HttpHost.create(url)).build();
        transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        client = new ElasticsearchClient(transport);
        log.info("Elasticsearch risk-score sink ready | index={}", AppConfig.elasticsearchRiskScoresIndex());
    }

    @Override
    public void invoke(RiskScore score, Context context) throws Exception {
        String index = AppConfig.elasticsearchRiskScoresIndex();
        client.index(i -> i.index(index).id(score.eventId()).document(score));
        log.debug("Risk score indexed | index={} event_id={}", index, score.eventId());
    }

    @Override
    public void close() throws Exception {
        if (transport != null) {
            transport.close();
        }
        log.info("Elasticsearch risk-score client closed");
    }
}
