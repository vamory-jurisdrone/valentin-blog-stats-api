package com.blog.stats.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Réglages métier lus depuis le préfixe {@code stats.*} de application.yml. */
@ConfigurationProperties(prefix = "stats")
public record StatsProperties(
        Auth auth,
        Cors cors,
        RateLimit rateLimit,
        int dedupWindowMinutes,
        int retentionMonths,
        int cacheTtlMinutes) {

    /**
     * Authentification des applications clientes (OAuth2 client credentials).
     *
     * @param jwtSecret       clé HMAC (HS256) de signature des jetons, au moins 32 octets
     * @param tokenTtlMinutes durée de vie d'un jeton
     * @param clients         applications autorisées à demander un jeton
     */
    public record Auth(String jwtSecret, int tokenTtlMinutes, List<Client> clients) {}

    /** Application cliente (par exemple le backend Symfony) et les scopes qu'elle peut obtenir. */
    public record Client(String id, String secret, List<String> scopes) {}

    public record Cors(List<String> allowedOrigins) {}

    /**
     * @param eventsPerMinute        quota d'événements par IP sur /api/events/** (0 = désactivé)
     * @param tokenRequestsPerMinute quota de demandes de jeton par IP sur /api/auth/token (0 = désactivé)
     */
    public record RateLimit(int eventsPerMinute, int tokenRequestsPerMinute) {}
}
