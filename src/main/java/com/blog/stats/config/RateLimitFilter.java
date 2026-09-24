package com.blog.stats.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.TimeMeter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Limitation de débit par IP (Bucket4j) : N requêtes / minute sur les routes de {@code matcher}.
 * Deux instances : l'ingestion ({@code /api/events/**}, 60 / min) et la demande de jeton
 * ({@code /api/auth/token}, 10 / min, contre la recherche exhaustive de secrets clients).
 * Fenêtre fixe par IP : N jetons, rechargés d'un coup une minute après la première requête, pour que la
 * (N+1)e requête d'une même minute soit toujours refusée (un remplissage progressif en laisserait passer
 * près de 2N sur la première minute). Un seau par IP, gardé dans un cache Caffeine qui oublie les IP inactives depuis 10 min.
 * L'IP vient de {@code getRemoteAddr()} : Tomcat (RemoteIpValve) ne la remplace par X-Forwarded-For
 * que si la connexion vient d'un proxy de confiance ({@code server.tomcat.remoteip.internal-proxies},
 * variable STATS_TRUSTED_PROXIES). Pas un @Component : instancié par SecurityConfig.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    static final String REMAINING_HEADER = "X-RateLimit-Remaining";

    private static final Duration IDLE_EXPIRY = Duration.ofMinutes(10);
    private static final long MAX_TRACKED_IPS = 100_000;

    private final RequestMatcher matcher;
    private final int requestsPerMinute;
    private final String subject;
    private final TimeMeter timeMeter;
    private final ObjectMapper objectMapper;
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(IDLE_EXPIRY)
            .maximumSize(MAX_TRACKED_IPS)
            .build();

    /**
     * @param requestsPerMinute quota par IP ; 0 ou moins désactive la limitation
     * @param subject           ce qui est compté, repris dans le message du 429 (« events »…)
     * @param clock             source de temps des seaux (remplaçable en test)
     */
    public RateLimitFilter(RequestMatcher matcher, int requestsPerMinute, String subject, Clock clock,
                           ObjectMapper objectMapper) {
        this.matcher = matcher;
        this.requestsPerMinute = requestsPerMinute;
        this.subject = subject;
        this.timeMeter = new ClockTimeMeter(clock);
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        // Les pré-requêtes CORS (OPTIONS) ne consomment pas de jeton
        return requestsPerMinute <= 0
                || HttpMethod.OPTIONS.matches(request.getMethod())
                || !matcher.matches(request);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        Bucket bucket = buckets.get(clientIp(request), ip -> newBucket());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            response.setHeader(REMAINING_HEADER, Long.toString(probe.getRemainingTokens()));
            chain.doFilter(request, response);
            return;
        }
        // Temps restant avant le rechargement de la fenêtre, arrondi à la seconde supérieure
        long retryAfter = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill() + 999_999_999L));
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
        response.setHeader(REMAINING_HEADER, "0");
        SecurityConfig.writeProblem(response, objectMapper, request, HttpStatus.TOO_MANY_REQUESTS,
                "Too many " + subject + ", retry in " + retryAfter + " s");
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(requestsPerMinute)
                        .refillIntervally(requestsPerMinute, Duration.ofMinutes(1)))
                .withCustomTimePrecision(timeMeter)
                .build();
    }

    private static String clientIp(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        return ip == null ? "unknown" : ip;
    }

    /** Adapte l'horloge injectée au format attendu par Bucket4j. */
    private record ClockTimeMeter(Clock clock) implements TimeMeter {

        @Override
        public long currentTimeNanos() {
            Instant now = clock.instant();
            return TimeUnit.SECONDS.toNanos(now.getEpochSecond()) + now.getNano();
        }

        @Override
        public boolean isWallClockBased() {
            return true;
        }
    }
}
