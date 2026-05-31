package com.riskengine.api.config;

import com.riskengine.common.config.AppConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPooled;

@Configuration
public class RedisConfig {

    @Bean(destroyMethod = "close")
    public JedisPooled jedisPooled() {
        return new JedisPooled(AppConfig.redisHost(), AppConfig.redisPort());
    }
}
