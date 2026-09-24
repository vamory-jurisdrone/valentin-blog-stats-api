package com.blog.stats.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.transaction.TransactionAwareCacheManagerProxy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Cache Caffeine de /top et /trends : les stats peuvent avoir jusqu'à stats.cache-ttl-minutes de retard. */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String TOP_CACHE = "top";
    public static final String TRENDS_CACHE = "trends";

    @Bean
    public CacheManager cacheManager(StatsProperties properties) {
        CaffeineCacheManager manager = new CaffeineCacheManager(TOP_CACHE, TRENDS_CACHE);
        manager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(properties.cacheTtlMinutes()))
                .maximumSize(1_000));
        // Les évictions faites dans une transaction (PUT/DELETE article) n'ont lieu qu'après le commit :
        // une requête concurrente ne peut pas remettre l'ancien état en cache.
        return new TransactionAwareCacheManagerProxy(manager);
    }
}
