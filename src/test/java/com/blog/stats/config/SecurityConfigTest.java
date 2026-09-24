package com.blog.stats.config;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import java.net.URI;
import com.blog.stats.support.MutableClock;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import securitytest.SecurityStubController;

/**
 * Règles de sécurité sur des controllers factices montés sur les vraies routes :
 * JWT applicatif (signature, iss, aud, expiration, scopes), routes publiques, CORS, limitation de débit
 * et format des refus. Les jetons sont signés avec le vrai JwtEncoder (ou une autre clé pour les cas refusés).
 */
@WebMvcTest(
        controllers = SecurityStubController.class,
        properties = {
                "stats.auth.jwt-secret=" + SecurityConfigTest.SECRET,
                "stats.cors.allowed-origins=https://blog.example,http://localhost:8000",
                "stats.rate-limit.events-per-minute=60"
        })
@Import({SecurityConfig.class, SecurityStubController.class, ProblemErrorController.class,
        SecurityConfigTest.ClockConfig.class})
class SecurityConfigTest {

    static final String SECRET = "security-config-test-secret-0123456789abcdef";
    static final Instant NOW = Instant.parse("2026-09-24T09:00:00Z");
    private static final String BLOG = "https://blog.example";

    /** Horloge réglable ; figée par défaut : aucun jeton de débit ne se recharge, même sur une CI lente. */
    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(NOW);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    MutableClock clock;

    @Autowired
    JwtEncoder encoder;

    @BeforeEach
    void resetClock() {
        clock.set(NOW);
    }

    private static MockHttpServletRequestBuilder fromIp(MockHttpServletRequestBuilder builder, String ip) {
        return builder.with(request -> {
            request.setRemoteAddr(ip);
            return request;
        });
    }

    private static MockHttpServletRequestBuilder viewEvent(String ip) {
        return fromIp(post("/api/events/view"), ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"articleId\":42,\"sessionId\":\"3f1c2a9e-7b1d-4c55-9a51-2f0e8f3b6d10\"}");
    }

    /** Claims d'un jeton tel que l'émet /api/auth/token, émis à NOW pour 15 min. */
    private static JwtClaimsSet.Builder claims(String scope) {
        return JwtClaimsSet.builder()
                .issuer("stats-api")
                .audience(List.of("stats-api"))
                .subject("symfony-blog")
                .claim("scope", scope)
                .issuedAt(NOW)
                .expiresAt(NOW.plus(Duration.ofMinutes(15)))
                .id(UUID.randomUUID().toString());
    }

    private static String sign(JwtEncoder encoder, JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private String token(String scope) {
        return sign(encoder, claims(scope).build());
    }

    private static RequestPostProcessor bearer(String token) {
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return request;
        };
    }

    /** Authentification simulée (post-processor spring-security-test) : pour les règles d'autorisation seules. */
    private static RequestPostProcessor scopes(String... scopes) {
        return jwt().authorities(Arrays.stream(scopes)
                .map(s -> new SimpleGrantedAuthority("SCOPE_" + s)).toArray(SimpleGrantedAuthority[]::new));
    }

    @Nested
    class BearerToken {

        @Test
        void missingTokenReturns401ProblemDetailWithBearerChallenge() throws Exception {
            mvc.perform(get("/api/stats/top"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("realm=\"stats-api\"")))
                    .andExpect(jsonPath("$.type").value("about:blank"))
                    .andExpect(jsonPath("$.title").value("Unauthorized"))
                    .andExpect(jsonPath("$.status").value(401))
                    .andExpect(jsonPath("$.detail").value("Missing access token"))
                    .andExpect(jsonPath("$.instance").value("/api/stats/top"))
                    .andExpect(jsonPath("$.error").doesNotExist())
                    .andExpect(jsonPath("$.properties").doesNotExist());
        }

        @Test
        void validTokenOpensStatsEndpoints() throws Exception {
            String token = token("stats:read articles:write");
            mvc.perform(get("/api/stats/top").with(bearer(token))).andExpect(status().isOk());
            mvc.perform(get("/api/stats/trends").with(bearer(token))).andExpect(status().isOk());
            mvc.perform(get("/api/stats/articles/42").with(bearer(token))).andExpect(status().isOk());
        }

        @Test
        void articlesNeedTheArticlesWriteScope() throws Exception {
            String body = "{\"title\":\"Débuter\",\"createdAt\":\"2026-09-01T08:00:00+02:00\"}";
            mvc.perform(put("/api/articles/42").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isUnauthorized());
            mvc.perform(delete("/api/articles/42")).andExpect(status().isUnauthorized());

            String readOnly = token("stats:read");
            mvc.perform(put("/api/articles/42").with(bearer(readOnly))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                            containsString("error=\"insufficient_scope\"")))
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.title").value("Forbidden"))
                    .andExpect(jsonPath("$.detail").value("Insufficient scope"))
                    .andExpect(jsonPath("$.error").value("insufficient_scope"))
                    .andExpect(jsonPath("$.instance").value("/api/articles/42"));
            mvc.perform(delete("/api/articles/42").with(bearer(readOnly))).andExpect(status().isForbidden());

            String writer = token("articles:write");
            mvc.perform(put("/api/articles/42").with(bearer(writer))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isNoContent());
            mvc.perform(delete("/api/articles/42").with(bearer(writer))).andExpect(status().isNoContent());
            // articles:write ne donne pas accès aux stats
            mvc.perform(get("/api/stats/top").with(bearer(writer))).andExpect(status().isForbidden());
        }

        @Test
        void authorizationRulesByScope() throws Exception {
            mvc.perform(get("/api/stats/top").with(scopes("stats:read"))).andExpect(status().isOk());
            mvc.perform(get("/api/stats/top").with(scopes("articles:write"))).andExpect(status().isForbidden());
            mvc.perform(get("/api/stats/top").with(scopes())).andExpect(status().isForbidden());
            mvc.perform(delete("/api/articles/1").with(scopes("stats:read"))).andExpect(status().isForbidden());
            mvc.perform(delete("/api/articles/1").with(scopes("articles:write"))).andExpect(status().isNoContent());
        }

        @Test
        void expiredTokenIsRejectedOnceTheClockSkewIsExhausted() throws Exception {
            String token = token("stats:read");
            // exp = NOW + 15 min ; 30 s de tolérance
            clock.set(NOW.plus(Duration.ofMinutes(15)).plusSeconds(29));
            mvc.perform(get("/api/stats/top").with(bearer(token))).andExpect(status().isOk());

            clock.set(NOW.plus(Duration.ofMinutes(15)).plusSeconds(31));
            mvc.perform(get("/api/stats/top").with(bearer(token)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("error=\"invalid_token\"")))
                    .andExpect(jsonPath("$.detail").value("Invalid or expired access token"))
                    .andExpect(jsonPath("$.error").value("invalid_token"));
        }

        @Test
        void tokenNotYetValidIsRejected() throws Exception {
            String token = sign(encoder, claims("stats:read").notBefore(NOW.plus(Duration.ofMinutes(5))).build());
            mvc.perform(get("/api/stats/top").with(bearer(token))).andExpect(status().isUnauthorized());
        }

        @Test
        void tamperedTokenIsRejected() throws Exception {
            String[] parts = token("stats:read").split("\\.");
            // Signature modifiée au milieu (le dernier caractère base64url porte des bits ignorés)
            char[] signature = parts[2].toCharArray();
            signature[10] = signature[10] == 'A' ? 'B' : 'A';
            String badSignature = parts[0] + "." + parts[1] + "." + new String(signature);
            mvc.perform(get("/api/stats/top").with(bearer(badSignature))).andExpect(status().isUnauthorized());

            // Charge utile modifiée (scope élargi) avec la signature d'origine
            String[] readOnly = token("stats:read").split("\\.");
            String[] elevated = token("stats:read articles:write").split("\\.");
            String forged = readOnly[0] + "." + elevated[1] + "." + readOnly[2];
            mvc.perform(delete("/api/articles/1").with(bearer(forged)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("invalid_token"));
        }

        @Test
        void tokenSignedWithAnotherSecretIsRejected() throws Exception {
            JwtEncoder other = new NimbusJwtEncoder(new ImmutableSecret<>(new SecretKeySpec(
                    "another-secret-of-at-least-32-bytes-000000".getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
            String token = sign(other, claims("stats:read").build());
            mvc.perform(get("/api/stats/top").with(bearer(token))).andExpect(status().isUnauthorized());
        }

        @Test
        void unsignedTokenIsRejected() throws Exception {
            String[] parts = token("stats:read").split("\\.");
            String none = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
            mvc.perform(get("/api/stats/top").with(bearer(none + "." + parts[1] + ".")))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void wrongAudienceOrIssuerIsRejected() throws Exception {
            String wrongAud = sign(encoder, claims("stats:read").audience(List.of("another-api")).build());
            mvc.perform(get("/api/stats/top").with(bearer(wrongAud))).andExpect(status().isUnauthorized());

            String wrongIss = sign(encoder, claims("stats:read").issuer("https://evil.example").build());
            mvc.perform(get("/api/stats/top").with(bearer(wrongIss))).andExpect(status().isUnauthorized());

            JwtClaimsSet noAudience = JwtClaimsSet.builder().issuer("stats-api").subject("symfony-blog")
                    .claim("scope", "stats:read").issuedAt(NOW).expiresAt(NOW.plusSeconds(900)).build();
            mvc.perform(get("/api/stats/top").with(bearer(sign(encoder, noAudience))))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void tokenWithoutExpirationIsRejected() throws Exception {
            JwtClaimsSet noExp = JwtClaimsSet.builder().issuer("stats-api").audience(List.of("stats-api"))
                    .subject("symfony-blog").claim("scope", "stats:read").issuedAt(NOW).build();
            mvc.perform(get("/api/stats/top").with(bearer(sign(encoder, noExp)))).andExpect(status().isUnauthorized());
        }

        @Test
        void garbageTokenIsRejected() throws Exception {
            mvc.perform(get("/api/stats/top").with(bearer("not-a-jwt")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
            // Autre schéma que Bearer : traité comme une absence de jeton
            mvc.perform(get("/api/stats/top").header(HttpHeaders.AUTHORIZATION, "Basic c3ltZm9ueTpzZWNyZXQ="))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.detail").value("Missing access token"));
        }

        @Test
        void metricsNeedStatsReadButHealthIsPublic() throws Exception {
            mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
            mvc.perform(get("/actuator/metrics").with(bearer(token("articles:write")))).andExpect(status().isForbidden());
            mvc.perform(get("/actuator/metrics").with(bearer(token("stats:read")))).andExpect(status().isOk());
            mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        }

        @Test
        void eventsArePublicEvenWithABogusBearerToken() throws Exception {
            mvc.perform(viewEvent("192.0.2.1")).andExpect(status().isAccepted());
            mvc.perform(viewEvent("192.0.2.1").with(bearer("not-a-jwt"))).andExpect(status().isAccepted());
            mvc.perform(get("/actuator/health").with(bearer("not-a-jwt"))).andExpect(status().isOk());
        }

        @Test
        void unknownRoutesAnswer404EvenWithAToken() throws Exception {
            mvc.perform(get("/internal/secret"))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
            mvc.perform(get("/internal/secret").with(bearer(token("stats:read articles:write"))))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/internal/secret").with(bearer("not-a-jwt"))).andExpect(status().isNotFound());
            mvc.perform(get("/internal/secret").with(scopes("stats:read", "articles:write")))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        }

        @Test
        void noSessionCookieIsCreated() throws Exception {
            mvc.perform(get("/api/stats/top").with(bearer(token("stats:read"))))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
        }
    }

    @Nested
    class Cors {

        @Test
        void preflightFromAllowedOriginIsAccepted() throws Exception {
            mvc.perform(options("/api/events/view")
                            .header(HttpHeaders.ORIGIN, BLOG)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Content-Type"))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, BLOG))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("POST")))
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        }

        @Test
        void preflightFromUnknownOriginIsRejected() throws Exception {
            mvc.perform(options("/api/events/view")
                            .header(HttpHeaders.ORIGIN, "https://evil.example")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                    .andExpect(header().stringValues(HttpHeaders.VARY, org.hamcrest.Matchers.hasItem("Origin")))
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(403))
                    .andExpect(jsonPath("$.title").value("Forbidden"))
                    .andExpect(jsonPath("$.detail").value("Origin not allowed"))
                    .andExpect(jsonPath("$.instance").value("/api/events/view"));
        }

        @Test
        void actualPostFromUnknownOriginIsRejectedWithProblemDetail() throws Exception {
            mvc.perform(viewEvent("192.0.2.3").header(HttpHeaders.ORIGIN, "https://evil.example"))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.detail").value("Origin not allowed"));
        }

        @Test
        void preflightForUnexpectedMethodIsRejected() throws Exception {
            mvc.perform(options("/api/events/view")
                            .header(HttpHeaders.ORIGIN, BLOG)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "DELETE"))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.detail").value("CORS method or header not allowed"));
        }

        @Test
        void actualPostFromAllowedOriginGetsCorsHeader() throws Exception {
            mvc.perform(viewEvent("192.0.2.2").header(HttpHeaders.ORIGIN, BLOG))
                    .andExpect(status().isAccepted())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, BLOG));
        }

        @Test
        void statsEndpointsHaveNoCors() throws Exception {
            mvc.perform(get("/api/stats/top").with(bearer(token("stats:read"))).header(HttpHeaders.ORIGIN, BLOG))
                    .andExpect(status().isOk())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }
    }

    /** Refus hors Spring MVC : pare-feu de Spring Security et page d'erreur du conteneur. */
    @Nested
    class ErrorFormat {

        @Test
        void firewallRejectionsAreProblemDetails() throws Exception {
            for (String url : new String[] {
                    "/api/stats/top;jsessionid=ABC",
                    "/api/events/../stats/top",
                    "/api/%2e%2e/stats/top",
                    "/api//stats/top"}) {
                mvc.perform(get(URI.create(url)).with(bearer(token("stats:read"))))
                        .andExpect(status().isBadRequest())
                        .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.status").value(400))
                        .andExpect(jsonPath("$.detail").value("Malformed request URL or header"))
                        .andExpect(jsonPath("$.timestamp").doesNotExist());
            }
        }

        @Test
        void containerErrorDispatchIsRenderedAsProblemDetail() throws Exception {
            mvc.perform(get("/error").with(request -> {
                        request.setDispatcherType(DispatcherType.ERROR);
                        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 503);
                        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/events/view");
                        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("db password=x"));
                        return request;
                    }))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(503))
                    .andExpect(jsonPath("$.detail").value("Service Unavailable"))
                    .andExpect(jsonPath("$.instance").value("/api/events/view"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(containsString("password"))));
        }

        @Test
        void errorDispatchWithoutStatusIsA500() throws Exception {
            mvc.perform(get("/error").with(request -> {
                        request.setDispatcherType(DispatcherType.ERROR);
                        return request;
                    }))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.detail").value("Internal error"))
                    .andExpect(jsonPath("$.instance").doesNotExist());
        }

        @Test
        void errorPageIsNotReachableDirectly() throws Exception {
            mvc.perform(get("/error"))
                    .andExpect(status().isNotFound())
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
            mvc.perform(get("/error").with(bearer(token("stats:read"))))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    class RateLimit {

        @Test
        void sixtyFirstEventInAMinuteIsRejectedPerIp() throws Exception {
            for (int i = 0; i < 60; i++) {
                mvc.perform(viewEvent("198.51.100.1")).andExpect(status().isAccepted());
            }
            mvc.perform(viewEvent("198.51.100.1"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                    .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(HttpStatus.TOO_MANY_REQUESTS.value()))
                    .andExpect(jsonPath("$.instance").value("/api/events/view"));

            // Le quota est par IP : une autre IP passe toujours
            mvc.perform(viewEvent("198.51.100.2")).andExpect(status().isAccepted());
            // read partage le même quota que view
            mvc.perform(fromIp(post("/api/events/read"), "198.51.100.1")
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isTooManyRequests());
        }

        @Test
        void optionsRequestsDoNotConsumeTokens() throws Exception {
            String ip = "198.51.100.3";
            for (int i = 0; i < 10; i++) {
                // Pré-requête CORS…
                mvc.perform(fromIp(options("/api/events/view"), ip)
                                .header(HttpHeaders.ORIGIN, BLOG)
                                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                        .andExpect(status().isOk());
                // …et OPTIONS simple (sans Origin)
                mvc.perform(fromIp(options("/api/events/view"), ip)).andExpect(status().isOk());
            }
            for (int i = 0; i < 60; i++) {
                mvc.perform(viewEvent(ip)).andExpect(status().isAccepted());
            }
            mvc.perform(viewEvent(ip)).andExpect(status().isTooManyRequests());
        }

        @Test
        void statsEndpointsAreNotRateLimited() throws Exception {
            for (int i = 0; i < 70; i++) {
                mvc.perform(fromIp(get("/api/stats/top"), "198.51.100.4").with(bearer(token("stats:read"))))
                        .andExpect(status().isOk());
            }
        }
    }
}
