package com.blog.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.apache.catalina.Valve;
import org.apache.catalina.valves.RemoteIpValve;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * Démarrage complet (H2, profil local) sur un vrai Tomcat : parcours client credentials de bout en bout
 * (jeton obtenu sur /api/auth/token puis présenté aux vraies routes), health, OpenAPI, Swagger UI,
 * actuator, proxys de confiance, erreurs. Client et clé passent par les variables d'environnement.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "STATS_CLIENT_ID=smoke-client",
                "STATS_CLIENT_SECRET=" + ApplicationSmokeTest.CLIENT_SECRET,
                "STATS_JWT_SECRET=smoke-test-jwt-secret-0123456789abcdefghij",
                "STATS_TOKEN_TTL_MINUTES=5"
        })
@ActiveProfiles("local")
class ApplicationSmokeTest {

    static final String CLIENT_SECRET = "smoke-client-secret-0123456789";

    @Autowired
    TestRestTemplate rest;

    @Autowired
    UserDetailsService userDetailsService;

    @Autowired
    ServletWebServerApplicationContext context;

    @Test
    void healthIsUpWithoutKey() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(response.getBody(), "$.status")).isEqualTo("UP");
    }

    @Test
    void infoIsPublic() {
        assertThat(rest.getForEntity("/actuator/info", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /** Demande un jeton en HTTP Basic, comme le fera Symfony. */
    private ResponseEntity<String> requestToken(String clientId, String secret, String scope) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setBasicAuth(clientId, secret);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        if (scope != null) {
            form.add("scope", scope);
        }
        return rest.postForEntity("/api/auth/token", new HttpEntity<>(form, headers), String.class);
    }

    private String accessToken(String scope) {
        ResponseEntity<String> response = requestToken("smoke-client", CLIENT_SECRET, scope);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return JsonPath.read(response.getBody(), "$.access_token");
    }

    private static HttpEntity<String> bearer(String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return new HttpEntity<>(body, headers);
    }

    @Test
    void clientCredentialsFlowEndToEnd() {
        ResponseEntity<String> tokenResponse = requestToken("smoke-client", CLIENT_SECRET, null);
        assertThat(tokenResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(tokenResponse.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(tokenResponse.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(JsonPath.<String>read(tokenResponse.getBody(), "$.token_type")).isEqualTo("Bearer");
        assertThat(JsonPath.<Integer>read(tokenResponse.getBody(), "$.expires_in")).isEqualTo(300);
        assertThat(JsonPath.<String>read(tokenResponse.getBody(), "$.scope")).isEqualTo("stats:read articles:write");
        String token = JsonPath.read(tokenResponse.getBody(), "$.access_token");

        // Synchronisation d'un article puis lecture de ses stats, avec le même jeton
        ResponseEntity<String> upsert = rest.exchange("/api/articles/4242", HttpMethod.PUT, bearer(token,
                "{\"title\":\"Smoke\",\"createdAt\":\"2026-09-01T08:00:00+02:00\"}"), String.class);
        assertThat(upsert.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ResponseEntity<String> stats = rest.exchange("/api/stats/articles/4242", HttpMethod.GET,
                bearer(token, null), String.class);
        assertThat(stats.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonPath.<String>read(stats.getBody(), "$.title")).isEqualTo("Smoke");
        assertThat(rest.exchange("/api/stats/top", HttpMethod.GET, bearer(token, null), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void readOnlyTokenCannotSyncArticles() {
        String token = accessToken("stats:read");
        assertThat(rest.exchange("/api/stats/trends", HttpMethod.GET, bearer(token, null), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<String> response = rest.exchange("/api/articles/4243", HttpMethod.DELETE,
                bearer(token, null), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(JsonPath.<String>read(response.getBody(), "$.detail")).isEqualTo("Insufficient scope");
    }

    @Test
    void wrongClientSecretIsRejected() {
        ResponseEntity<String> response = requestToken("smoke-client", "wrong-secret", null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Basic realm=\"stats-api\"");
        assertThat(JsonPath.<String>read(response.getBody(), "$.error")).isEqualTo("invalid_client");
    }

    @Test
    void openApiDocumentsTheOAuth2AndBearerSchemes() {
        ResponseEntity<String> response = rest.getForEntity("/v3/api-docs", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String json = response.getBody();
        assertThat(JsonPath.<String>read(json, "$.info.title")).isEqualTo("Blog Stats API");
        assertThat(JsonPath.<String>read(json, "$.components.securitySchemes.oauth2.type")).isEqualTo("oauth2");
        assertThat(JsonPath.<String>read(json, "$.components.securitySchemes.oauth2.flows.clientCredentials.tokenUrl"))
                .isEqualTo("/api/auth/token");
        assertThat(JsonPath.<java.util.Map<String, Object>>read(json,
                "$.components.securitySchemes.oauth2.flows.clientCredentials.scopes"))
                .containsOnlyKeys("stats:read", "articles:write");
        assertThat(JsonPath.<String>read(json, "$.components.securitySchemes.bearerJwt.scheme")).isEqualTo("bearer");
        assertThat(JsonPath.<String>read(json, "$.components.securitySchemes.bearerJwt.bearerFormat")).isEqualTo("JWT");
        assertThat(JsonPath.<List<String>>read(json, "$.paths['/api/stats/top'].get.security[*].oauth2[*]"))
                .containsExactly("stats:read");
        assertThat(JsonPath.<List<String>>read(json, "$.paths['/api/articles/{id}'].put.security[*].oauth2[*]"))
                .containsExactly("articles:write");
        // Point de jeton documenté, public, corps en formulaire
        assertThat(JsonPath.<Object>read(json,
                "$.paths['/api/auth/token'].post.requestBody.content['application/x-www-form-urlencoded']")).isNotNull();
        assertThat(JsonPath.<Object>read(json, "$.paths['/api/auth/token'].post.responses.401")).isNotNull();
        assertThat(JsonPath.<java.util.Map<String, Object>>read(json, "$.paths['/api/auth/token'].post"))
                .doesNotContainKey("security");
        assertThat(json).doesNotContain("X-API-KEY");
    }

    @Test
    void swaggerUiIsReachable() {
        ResponseEntity<String> response = rest.getForEntity("/swagger-ui.html", String.class);
        assertThat(response.getStatusCode().value()).isIn(200, 302);
        if (response.getStatusCode().is3xxRedirection()) {
            assertThat(String.valueOf(response.getHeaders().getLocation())).contains("/swagger-ui/index.html");
        }
        ResponseEntity<String> index = rest.getForEntity("/swagger-ui/index.html", String.class);
        assertThat(index.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(index.getBody()).contains("Swagger UI");
    }

    @Test
    void metricsNeedAStatsReadToken() {
        assertThat(rest.getForEntity("/actuator/metrics", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<String> response = rest.exchange("/actuator/metrics", HttpMethod.GET,
                bearer(accessToken("stats:read"), null), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void statsWithoutTokenAnswerProblemDetail() {
        ResponseEntity<String> response = rest.getForEntity("/api/stats/top", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertThat(JsonPath.<String>read(response.getBody(), "$.detail")).isEqualTo("Missing access token");
    }

    @Test
    void noDefaultUserIsGenerated() {
        assertThatThrownBy(() -> userDetailsService.loadUserByUsername("user"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void onlyLoopbackIsTrustedAsProxyByDefault() {
        Valve[] valves = ((TomcatWebServer) context.getWebServer()).getTomcat().getEngine().getPipeline().getValves();
        RemoteIpValve valve = Arrays.stream(valves)
                .filter(RemoteIpValve.class::isInstance).map(RemoteIpValve.class::cast)
                .findFirst().orElseThrow();
        assertThat(valve.getRemoteIpHeader()).isEqualToIgnoringCase("X-Forwarded-For");

        Pattern trusted = Pattern.compile(valve.getInternalProxies());
        for (String ip : List.of("127.0.0.1", "127.1.2.3", "0:0:0:0:0:0:0:1", "::1")) {
            assertThat(trusted.matcher(ip).matches()).as(ip).isTrue();
        }
        // Plages privées que Tomcat croit par défaut : un client du réseau Docker ou du LAN ne doit pas
        // pouvoir choisir son IP (et donc son quota) via X-Forwarded-For
        for (String ip : List.of("172.17.0.1", "172.18.0.1", "10.0.0.5", "192.168.1.10", "169.254.1.1",
                "100.64.0.1", "203.0.113.7", "1127.0.0.1")) {
            assertThat(trusted.matcher(ip).matches()).as(ip).isFalse();
        }
    }

    @Test
    void firewallRejectionsAreProblemDetailsOnTheRealContainer() {
        for (String path : List.of("/api/stats/top;jsessionid=ABC", "/api/%2e%2e/stats/top", "/api//stats/top")) {
            ResponseEntity<String> response = rest.getForEntity(URI.create(rest.getRootUri() + path), String.class);
            assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getHeaders().getContentType()).as(path).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(JsonPath.<Integer>read(response.getBody(), "$.status")).isEqualTo(400);
            assertThat(response.getBody()).doesNotContain("timestamp");
        }
    }

    @Test
    void errorPageIsNotReachableDirectly() {
        ResponseEntity<String> response = rest.getForEntity("/error", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void openApiDocumentsFilterResponsesOnEvents() {
        String json = rest.getForEntity("/v3/api-docs", String.class).getBody();
        for (String path : List.of("/api/events/view", "/api/events/read")) {
            assertThat(JsonPath.<Object>read(json, "$.paths['" + path + "'].post.responses.429")).isNotNull();
            assertThat(JsonPath.<Object>read(json, "$.paths['" + path + "'].post.responses.429.headers.Retry-After"))
                    .isNotNull();
            assertThat(JsonPath.<Object>read(json, "$.paths['" + path + "'].post.responses.415")).isNotNull();
        }
        // L'ErrorController n'apparaît pas dans la documentation
        assertThat(JsonPath.<java.util.Map<String, Object>>read(json, "$.paths")).doesNotContainKey("/error");
    }
}
