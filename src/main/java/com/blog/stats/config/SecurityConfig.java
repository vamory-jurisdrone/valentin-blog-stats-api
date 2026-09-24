package com.blog.stats.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.BearerTokenErrorCodes;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Sécurité de l'API :
 * <ul>
 *   <li>{@code /api/stats/**} et {@code /actuator/metrics} : JWT applicatif ({@code Authorization: Bearer})
 *       portant le scope {@code stats:read} ; {@code /api/articles/**} : scope {@code articles:write} ;</li>
 *   <li>{@code /api/auth/token} : public (le client s'y authentifie par son secret), 10 requêtes / min / IP ;</li>
 *   <li>{@code /api/events/**} : public, CORS limité au blog, limitation de débit par IP ;</li>
 *   <li>health, info et Swagger : publics ; tout le reste répond 404.</li>
 * </ul>
 * Tous les refus (401, 403, 404, 400 du pare-feu, 429) sont écrits au format RFC 9457.
 * Les filtres sont instanciés ici (pas de @Component) pour ne pas être enregistrés une seconde fois
 * dans le conteneur de servlets.
 */
@Slf4j
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(StatsProperties.class)
@Import({CorsConfig.class, JwtConfig.class})
public class SecurityConfig {

    /** Royaume annoncé dans les en-têtes WWW-Authenticate (Bearer ici, Basic sur /api/auth/token). */
    public static final String REALM = "stats-api";

    private static final PathPatternRequestMatcher.Builder PATHS = PathPatternRequestMatcher.withDefaults();

    /** Routes lisibles avec le scope stats:read. */
    static final RequestMatcher STATS_READ = new OrRequestMatcher(
            PATHS.matcher("/api/stats/**"),
            PATHS.matcher("/actuator/metrics"),
            PATHS.matcher("/actuator/metrics/**"));

    /** Routes modifiables avec le scope articles:write. */
    static final RequestMatcher ARTICLES_WRITE = PATHS.matcher("/api/articles/**");

    /** Routes réservées aux applications clientes (JWT) : seules routes où un jeton Bearer est lu. */
    static final RequestMatcher PROTECTED = new OrRequestMatcher(STATS_READ, ARTICLES_WRITE);

    /** Ingestion appelée par le navigateur. */
    static final RequestMatcher EVENTS = PATHS.matcher("/api/events/**");

    /** Émission des jetons (client credentials). */
    static final RequestMatcher TOKEN = PATHS.matcher("/api/auth/token");

    private static final RequestMatcher PUBLIC = new OrRequestMatcher(
            PATHS.matcher("/actuator/health"),
            PATHS.matcher("/actuator/health/**"),
            PATHS.matcher("/actuator/info"),
            PATHS.matcher("/swagger-ui.html"),
            PATHS.matcher("/swagger-ui/**"),
            PATHS.matcher("/v3/api-docs"),
            PATHS.matcher("/v3/api-docs/**"));

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            StatsProperties properties,
            CorsConfigurationSource corsConfigurationSource,
            ObjectMapper objectMapper,
            JwtDecoder jwtDecoder,
            ObjectProvider<Clock> clockProvider) throws Exception {

        Clock clock = clockProvider.getIfAvailable(Clock::systemUTC);
        StatsProperties.RateLimit limits = properties.rateLimit();
        RateLimitFilter eventsRateLimit = new RateLimitFilter(
                EVENTS, limits == null ? 0 : limits.eventsPerMinute(), "events", clock, objectMapper);
        RateLimitFilter tokenRateLimit = new RateLimitFilter(
                TOKEN, limits == null ? 0 : limits.tokenRequestsPerMinute(), "token requests", clock, objectMapper);
        // CorsFilter posé à la main (et non via http.cors) pour lui donner un refus au format RFC 9457
        CorsFilter corsFilter = new CorsFilter(corsConfigurationSource);
        corsFilter.setCorsProcessor(new ProblemCorsProcessor(objectMapper));

        AuthenticationEntryPoint entryPoint = authenticationEntryPoint(objectMapper);
        AccessDeniedHandler accessDeniedHandler = accessDeniedHandler(objectMapper);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .cors(AbstractHttpConfigurer::disable)
                .addFilter(corsFilter)
                .authorizeHttpRequests(auth -> auth
                        // Seul le forward interne du conteneur vers /error est ouvert ; un GET /error direct répond 404
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(EVENTS).permitAll()
                        .requestMatchers(TOKEN).permitAll()
                        .requestMatchers(PUBLIC).permitAll()
                        .requestMatchers(STATS_READ).hasAuthority(Scopes.AUTHORITY_PREFIX + Scopes.STATS_READ)
                        .requestMatchers(ARTICLES_WRITE).hasAuthority(Scopes.AUTHORITY_PREFIX + Scopes.ARTICLES_WRITE)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(rs -> rs
                        .bearerTokenResolver(bearerTokenResolver())
                        .jwt(jwt -> jwt.decoder(jwtDecoder))
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .addFilterBefore(eventsRateLimit, AnonymousAuthenticationFilter.class)
                .addFilterBefore(tokenRateLimit, AnonymousAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Le jeton n'est lu que dans l'en-tête Authorization et seulement sur les routes protégées :
     * un en-tête Bearer quelconque ne fait pas échouer une route publique (événements, health…).
     */
    static BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver header = new DefaultBearerTokenResolver();
        return request -> PROTECTED.matches(request) ? header.resolve(request) : null;
    }

    /**
     * Pas d'authentification valide : 401 + {@code WWW-Authenticate: Bearer ...} (RFC 6750) sur une route
     * protégée, 404 ailleurs (on ne révèle rien).
     */
    static AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
        BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
        bearer.setRealmName(REALM);
        return (request, response, ex) -> {
            if (!PROTECTED.matches(request)) {
                writeProblem(response, objectMapper, request, HttpStatus.NOT_FOUND, "No resource found");
                return;
            }
            bearer.commence(request, response, ex); // statut et WWW-Authenticate
            if (ex instanceof OAuth2AuthenticationException oauth) {
                writeProblem(response, objectMapper, request, HttpStatus.UNAUTHORIZED,
                        "Invalid or expired access token", oauth.getError().getErrorCode());
            } else {
                writeProblem(response, objectMapper, request, HttpStatus.UNAUTHORIZED, "Missing access token");
            }
        };
    }

    /** Jeton valide mais scope insuffisant : 403 + {@code WWW-Authenticate: Bearer error="insufficient_scope"}. */
    static AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        BearerTokenAccessDeniedHandler bearer = new BearerTokenAccessDeniedHandler();
        bearer.setRealmName(REALM);
        return (request, response, ex) -> {
            if (!PROTECTED.matches(request)) {
                // Client authentifié sur une route non prévue
                writeProblem(response, objectMapper, request, HttpStatus.NOT_FOUND, "No resource found");
                return;
            }
            bearer.handle(request, response, ex);
            writeProblem(response, objectMapper, request, HttpStatus.FORBIDDEN, "Insufficient scope",
                    BearerTokenErrorCodes.INSUFFICIENT_SCOPE);
        };
    }

    /**
     * Requêtes rejetées par le pare-feu de Spring Security ({@code ..}, {@code ;jsessionid}, {@code //},
     * {@code %2e}…) : 400 RFC 9457 au lieu de la page d'erreur par défaut. Détecté par WebSecurity.
     */
    @Bean
    public RequestRejectedHandler requestRejectedHandler(ObjectMapper objectMapper) {
        return (request, response, ex) -> {
            log.debug("Request rejected by the firewall: {}", ex.getMessage());
            writeProblem(response, objectMapper, request, HttpStatus.BAD_REQUEST, "Malformed request URL or header");
        };
    }

    /** Aucun utilisateur : évite le mot de passe généré par Spring Boot au démarrage. */
    @Bean
    public InMemoryUserDetailsManager userDetailsService() {
        return new InMemoryUserDetailsManager();
    }

    /** Écrit une erreur RFC 9457 hors de Spring MVC (les filtres passent avant le @RestControllerAdvice). */
    static void writeProblem(HttpServletResponse response, ObjectMapper objectMapper, HttpServletRequest request,
                             HttpStatus status, String detail) throws IOException {
        writeProblem(response, objectMapper, request.getRequestURI(), status, detail);
    }

    /** Variante avec le code d'erreur OAuth2 (RFC 6749 / 6750) dans la propriété {@code error}. */
    static void writeProblem(HttpServletResponse response, ObjectMapper objectMapper, HttpServletRequest request,
                             HttpStatus status, String detail, String error) throws IOException {
        writeProblem(response, objectMapper, request.getRequestURI(), status, detail, error);
    }

    /** Variante avec l'URI d'origine (dispatch vers /error) ; {@code instance} omis si elle est invalide. */
    static void writeProblem(HttpServletResponse response, ObjectMapper objectMapper, String instance,
                             HttpStatus status, String detail) throws IOException {
        writeProblem(response, objectMapper, instance, status, detail, null);
    }

    private static void writeProblem(HttpServletResponse response, ObjectMapper objectMapper, String instance,
                                     HttpStatus status, String detail, String error) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        if (error != null) {
            problem.setProperty("error", error);
        }
        if (instance != null) {
            try {
                problem.setInstance(URI.create(instance));
            } catch (IllegalArgumentException ignored) {
                // URL rejetée justement parce qu'elle est malformée : on ne la renvoie pas
            }
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
