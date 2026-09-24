package com.blog.stats.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.blog.stats.config.ProblemErrorController;
import com.blog.stats.config.SecurityConfig;
import com.blog.stats.service.TokenService;
import com.blog.stats.support.MutableClock;
import com.jayway.jsonpath.JsonPath;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import securitytest.SecurityStubController;

/**
 * {@code POST /api/auth/token} derrière la vraie configuration de sécurité : authentification du client
 * (HTTP Basic ou formulaire), erreurs RFC 6749 au format RFC 9457, scopes, limitation de débit, et
 * utilisation du jeton obtenu sur les routes protégées (controllers factices).
 */
@WebMvcTest(
        controllers = {TokenController.class, SecurityStubController.class},
        properties = {
                "stats.auth.jwt-secret=token-controller-test-secret-0123456789abcdef",
                "stats.auth.token-ttl-minutes=15",
                "stats.auth.clients[0].id=symfony-blog",
                "stats.auth.clients[0].secret=" + TokenControllerTest.SECRET,
                "stats.auth.clients[0].scopes=stats:read,articles:write",
                "stats.auth.clients[1].id=reader",
                "stats.auth.clients[1].secret=reader-secret-0123456789",
                "stats.auth.clients[1].scopes=stats:read",
                "stats.auth.clients[2].id=app:special",
                "stats.auth.clients[2].secret=" + TokenControllerTest.SPECIAL_SECRET,
                "stats.auth.clients[2].scopes=stats:read",
                "stats.rate-limit.token-requests-per-minute=10"
        })
@Import({SecurityConfig.class, TokenService.class, SecurityStubController.class, ProblemErrorController.class,
        TokenControllerTest.ClockConfig.class})
class TokenControllerTest {

    static final String SECRET = "symfony-secret-0123456789";
    /** Caractères réservés : doivent être encodés en URL avant le Base64 (RFC 6749 §2.3.1). */
    static final String SPECIAL_SECRET = "p@ss word+%:0123456789";
    static final Instant NOW = Instant.parse("2026-09-24T09:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(NOW);
        }
    }

    /** Une IP par requête : le quota de 10 / min ne doit gêner que le test qui le vise. */
    private static final AtomicInteger NEXT_IP = new AtomicInteger(1);

    @Autowired
    MockMvc mvc;

    @Autowired
    JwtDecoder decoder;

    private static String basic(String id, String secret) {
        return "Basic " + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    private static MockHttpServletRequestBuilder tokenRequest() {
        int n = NEXT_IP.getAndIncrement();
        return post("/api/auth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .with(request -> {
                    request.setRemoteAddr("10.1." + (n / 250) + "." + (n % 250 + 1));
                    return request;
                });
    }

    private ResultActions expectOAuthError(ResultActions result, int status, String error) throws Exception {
        return result
                .andExpect(status().is(status))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.error").value(error))
                .andExpect(jsonPath("$.instance").value("/api/auth/token"));
    }

    private String accessToken(String authorization, String scope) throws Exception {
        MockHttpServletRequestBuilder request = tokenRequest()
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .param("grant_type", "client_credentials");
        if (scope != null) {
            request.param("scope", scope);
        }
        String body = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.access_token");
    }

    @Test
    void basicAuthenticationReturnsASignedJwt() throws Exception {
        String body = mvc.perform(tokenRequest()
                        .header(HttpHeaders.AUTHORIZATION, basic("symfony-blog", SECRET))
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andExpect(jsonPath("$.expires_in").value(900))
                .andExpect(jsonPath("$.scope").value("stats:read articles:write"))
                .andReturn().getResponse().getContentAsString();

        String token = JsonPath.read(body, "$.access_token");
        assertThat(token.split("\\.")).hasSize(3);
        String header = new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]), StandardCharsets.UTF_8);
        assertThat(header).contains("\"alg\":\"HS256\"");

        Jwt jwt = decoder.decode(token);
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("stats-api");
        assertThat(jwt.getAudience()).containsExactly("stats-api");
        assertThat(jwt.getSubject()).isEqualTo("symfony-blog");
        assertThat(jwt.getClaimAsString("scope")).isEqualTo("stats:read articles:write");
        assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(UUID.fromString(jwt.getId())).isNotNull();
    }

    @Test
    void everyTokenHasItsOwnJti() throws Exception {
        String first = accessToken(basic("symfony-blog", SECRET), null);
        String second = accessToken(basic("symfony-blog", SECRET), null);
        assertThat(decoder.decode(first).getId()).isNotEqualTo(decoder.decode(second).getId());
    }

    @Test
    void formCredentialsAreAccepted() throws Exception {
        mvc.perform(tokenRequest()
                        .param("grant_type", "client_credentials")
                        .param("client_id", "symfony-blog")
                        .param("client_secret", SECRET))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("stats:read articles:write"));
    }

    @Test
    void basicCredentialsAreUrlDecoded() throws Exception {
        String id = URLEncoder.encode("app:special", StandardCharsets.UTF_8);
        String secret = URLEncoder.encode(SPECIAL_SECRET, StandardCharsets.UTF_8);
        mvc.perform(tokenRequest()
                        .header(HttpHeaders.AUTHORIZATION, basic(id, secret))
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("stats:read"));
    }

    @Test
    void basicWithMatchingClientIdParameterIsAccepted() throws Exception {
        mvc.perform(tokenRequest()
                        .header(HttpHeaders.AUTHORIZATION, basic("symfony-blog", SECRET))
                        .param("grant_type", "client_credentials")
                        .param("client_id", "symfony-blog"))
                .andExpect(status().isOk());
    }

    @Test
    void wrongSecretIsInvalidClient() throws Exception {
        expectOAuthError(mvc.perform(tokenRequest()
                        .header(HttpHeaders.AUTHORIZATION, basic("symfony-blog", SECRET + "x"))
                        .param("grant_type", "client_credentials")), 401, "invalid_client")
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"stats-api\""))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.detail").value("Client authentication failed"))
                .andExpect(jsonPath("$.access_token").doesNotExist());

        // Secret d'un autre client
        expectOAuthError(mvc.perform(tokenRequest()
                .header(HttpHeaders.AUTHORIZATION, basic("symfony-blog", "reader-secret-0123456789"))
                .param("grant_type", "client_credentials")), 401, "invalid_client");
        // Formulaire
        expectOAuthError(mvc.perform(tokenRequest()
                .param("grant_type", "client_credentials")
                .param("client_id", "symfony-blog")
                .param("client_secret", "")), 401, "invalid_client");
    }

    @Test
    void unknownClientIsInvalidClientWithTheSameMessage() throws Exception {
        expectOAuthError(mvc.perform(tokenRequest()
                        .header(HttpHeaders.AUTHORIZATION, basic("intruder", SECRET))
                        .param("grant_type", "client_credentials")), 401, "invalid_client")
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"stats-api\""))
                .andExpect(jsonPath("$.detail").value("Client authentication failed"));
    }

    @Test
    void missingOrMalformedCredentialsAreInvalidClient() throws Exception {
        expectOAuthError(mvc.perform(tokenRequest().param("grant_type", "client_credentials")),
                401, "invalid_client");
        expectOAuthError(mvc.perform(tokenRequest()
                .param("grant_type", "client_credentials").param("client_id", "symfony-blog")), 401, "invalid_client");
        for (String authorization : List.of("Basic !!!not-base64!!!",
                "Basic " + Base64.getEncoder().encodeToString("no-colon".getBytes(StandardCharsets.UTF_8)),
                "Basic " + Base64.getEncoder().encodeToString("symfony-blog:%zz".getBytes(StandardCharsets.UTF_8)),
                "Bearer some-token")) {
            expectOAuthError(mvc.perform(tokenRequest()
                    .header(HttpHeaders.AUTHORIZATION, authorization)
                    .param("grant_type", "client_credentials")), 401, "invalid_client");
        }
    }

    @Test
    void twoAuthenticationMethodsAreAnInvalidRequest() throws Exception {
        expectOAuthError(mvc.perform(tokenRequest()
                .header(HttpHeaders.AUTHORIZATION, basic("symfony-blog", SECRET))
                .param("grant_type", "client_credentials")
                .param("client_id", "symfony-blog")
                .param("client_secret", SECRET)), 400, "invalid_request");
        expectOAuthError(mvc.perform(tokenRequest()
                .header(HttpHeaders.AUTHORIZATION, basic("symfony-blog", SECRET))
                .param("grant_type", "client_credentials")
                .param("client_id", "reader")), 400, "invalid_request");
    }

    @Test
    void missingGrantTypeIsInvalidRequest() throws Exception {
        expectOAuthError(mvc.perform(tokenRequest()
                        .header(HttpHeaders.AUTHORIZATION, basic("symfony-blog", SECRET))), 400, "invalid_request")
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.detail").value("Missing grant_type parameter"));
    }

    @Test
    void repeatedParameterIsInvalidRequest() throws Exception {
        expectOAuthError(mvc.perform(tokenRequest()
                .header(HttpHeaders.AUTHORIZATION, basic("symfony-blog", SECRET))
                .param("grant_type", "client_credentials", "client_credentials")), 400, "invalid_request");
    }

    @Test
    void otherGrantTypesAreUnsupported() throws Exception {
        for (String grant : List.of("password", "authorization_code", "refresh_token")) {
            expectOAuthError(mvc.perform(tokenRequest()
                            .header(HttpHeaders.AUTHORIZATION, basic("symfony-blog", SECRET))
                            .param("grant_type", grant)), 400, "unsupported_grant_type")
                    .andExpect(jsonPath("$.detail").value("Only the client_credentials grant type is supported"));
        }
    }

    @Test
    void unknownOrForbiddenScopeIsInvalidScope() throws Exception {
        expectOAuthError(mvc.perform(tokenRequest()
                .header(HttpHeaders.AUTHORIZATION, basic("symfony-blog", SECRET))
                .param("grant_type", "client_credentials")
                .param("scope", "stats:read admin")), 400, "invalid_scope");
        // reader n'a droit qu'à stats:read
        expectOAuthError(mvc.perform(tokenRequest()
                .header(HttpHeaders.AUTHORIZATION, basic("reader", "reader-secret-0123456789"))
                .param("grant_type", "client_credentials")
                .param("scope", "articles:write")), 400, "invalid_scope");
    }

    @Test
    void requestedScopeSubsetIsHonoured() throws Exception {
        mvc.perform(tokenRequest()
                        .header(HttpHeaders.AUTHORIZATION, basic("symfony-blog", SECRET))
                        .param("grant_type", "client_credentials")
                        .param("scope", "stats:read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("stats:read"));
        // Ordre de la configuration, doublons et espaces multiples ignorés
        mvc.perform(tokenRequest()
                        .header(HttpHeaders.AUTHORIZATION, basic("symfony-blog", SECRET))
                        .param("grant_type", "client_credentials")
                        .param("scope", " articles:write  stats:read articles:write "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("stats:read articles:write"));
        // scope vide = tous les scopes du client
        mvc.perform(tokenRequest()
                        .header(HttpHeaders.AUTHORIZATION, basic("reader", "reader-secret-0123456789"))
                        .param("grant_type", "client_credentials")
                        .param("scope", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("stats:read"));
    }

    @Test
    void scopedTokenOpensOnlyItsRoutes() throws Exception {
        String readOnly = accessToken(basic("symfony-blog", SECRET), "stats:read");
        mvc.perform(get("/api/stats/top").header(HttpHeaders.AUTHORIZATION, "Bearer " + readOnly))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/articles/42").header(HttpHeaders.AUTHORIZATION, "Bearer " + readOnly))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("Insufficient scope"));

        String full = accessToken(basic("symfony-blog", SECRET), null);
        mvc.perform(delete("/api/articles/42").header(HttpHeaders.AUTHORIZATION, "Bearer " + full))
                .andExpect(status().isNoContent());
    }

    @Test
    void onlyFormEncodedPostIsAccepted() throws Exception {
        mvc.perform(tokenRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.AUTHORIZATION, basic("symfony-blog", SECRET))
                        .content("{\"grant_type\":\"client_credentials\"}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(get("/api/auth/token")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void tenTokenRequestsPerMinutePerIp() throws Exception {
        MockHttpServletRequestBuilder fromSameIp = post("/api/auth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header(HttpHeaders.AUTHORIZATION, basic("symfony-blog", SECRET))
                .param("grant_type", "client_credentials")
                .with(request -> {
                    request.setRemoteAddr("203.0.113.77");
                    return request;
                });
        for (int i = 0; i < 10; i++) {
            mvc.perform(fromSameIp).andExpect(status().isOk());
        }
        mvc.perform(fromSameIp)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.detail", startsWith("Too many token requests")))
                .andExpect(jsonPath("$.instance").value("/api/auth/token"));
        // Les échecs comptent aussi : pas de recherche exhaustive du secret depuis une IP
        mvc.perform(post("/api/auth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .header(HttpHeaders.AUTHORIZATION, basic("symfony-blog", "guess"))
                        .param("grant_type", "client_credentials")
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.77");
                            return request;
                        }))
                .andExpect(status().isTooManyRequests());
        // Autre IP : son propre quota
        mvc.perform(tokenRequest()
                        .header(HttpHeaders.AUTHORIZATION, basic("symfony-blog", SECRET))
                        .param("grant_type", "client_credentials"))
                .andExpect(status().isOk());
    }
}
