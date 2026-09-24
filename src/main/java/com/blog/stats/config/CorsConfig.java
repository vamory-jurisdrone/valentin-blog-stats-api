package com.blog.stats.config;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * CORS appliqué par Spring Security ({@code http.cors(...)}).
 * Seule l'ingestion est appelée depuis le navigateur : POST depuis l'origine du blog, sans cookie.
 * Les stats et la synchro d'articles sont appelées serveur à serveur : aucune règle CORS.
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(StatsProperties properties) {
        List<String> origins = properties.cors() == null || properties.cors().allowedOrigins() == null
                ? List.of()
                : properties.cors().allowedOrigins().stream().map(String::trim).filter(o -> !o.isEmpty()).toList();

        CorsConfiguration events = new CorsConfiguration();
        events.setAllowedOrigins(origins);
        events.setAllowedMethods(List.of("POST", "OPTIONS"));
        events.setAllowedHeaders(List.of("Content-Type"));
        events.setAllowCredentials(false);
        events.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/events/**", events);
        return source;
    }
}
