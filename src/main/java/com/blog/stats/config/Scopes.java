package com.blog.stats.config;

import java.util.List;

/** Scopes OAuth2 que l'API accorde aux applications clientes (claim {@code scope} du JWT). */
public final class Scopes {

    /** Lecture des statistiques ({@code /api/stats/**}) et des métriques ({@code /actuator/metrics}). */
    public static final String STATS_READ = "stats:read";
    /** Synchronisation des articles ({@code /api/articles/**}). */
    public static final String ARTICLES_WRITE = "articles:write";

    public static final List<String> ALL = List.of(STATS_READ, ARTICLES_WRITE);

    /** Préfixe des autorités Spring Security déduites du claim {@code scope}. */
    static final String AUTHORITY_PREFIX = "SCOPE_";

    private Scopes() {
    }
}
