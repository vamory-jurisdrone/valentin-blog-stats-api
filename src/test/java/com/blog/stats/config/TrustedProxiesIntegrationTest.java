package com.blog.stats.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Sur un vrai Tomcat : X-Forwarded-For n'est cru que venant d'un proxy de confiance
 * (STATS_TRUSTED_PROXIES), et les erreurs du conteneur passent par l'ErrorController RFC 9457.
 * Quota réduit à 3 / min : une fenêtre d'une minute ne peut pas se recharger pendant le test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "stats.rate-limit.events-per-minute=3")
@ActiveProfiles("local")
class TrustedProxiesIntegrationTest {

    private static final String BODY = "{\"articleId\":999999,\"sessionId\":\"3f1c2a9e-7b1d-4c55-9a51-2f0e8f3b6d10\"}";

    /** Filtre de test placé avant la sécurité : simule une erreur levée par le conteneur. */
    @TestConfiguration(proxyBeanMethods = false)
    static class SendErrorConfig {
        @Bean
        FilterRegistrationBean<Filter> sendErrorFilter() {
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(
                    (request, response, chain) -> ((HttpServletResponse) response).sendError(503));
            registration.addUrlPatterns("/test/send-error");
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }
    }

    @Autowired
    TestRestTemplate rest;

    private static HttpStatus postEvent(TestRestTemplate rest, String forwardedFor) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", forwardedFor);
        ResponseEntity<String> response = rest.postForEntity("/api/events/view", new HttpEntity<>(BODY, headers),
                String.class);
        return HttpStatus.valueOf(response.getStatusCode().value());
    }

    @Test
    void containerErrorsAreProblemDetails() {
        ResponseEntity<String> response = rest.getForEntity("/test/send-error", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(JsonPath.<Integer>read(response.getBody(), "$.status")).isEqualTo(503);
        assertThat(JsonPath.<String>read(response.getBody(), "$.instance")).isEqualTo("/test/send-error");
        assertThat(response.getBody()).doesNotContain("timestamp").doesNotContain("trace");
    }

    /** Défaut : la boucle locale (d'où vient le client de test) est un proxy de confiance. */
    @Test
    void forwardedForFromTrustedProxyIsTheClientIp() {
        for (int i = 0; i < 3; i++) {
            assertThat(postEvent(rest, "203.0.113.10")).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
        assertThat(postEvent(rest, "203.0.113.10")).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        // Autre client derrière le même proxy : son propre quota
        assertThat(postEvent(rest, "203.0.113.11")).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    /** Proxy de confiance ailleurs : la connexion directe du client n'est pas crue. */
    @Nested
    @TestPropertySource(properties = "STATS_TRUSTED_PROXIES=192.0.2.1")
    class UntrustedPeer {

        /** Injecté ici : le champ de la classe englobante pointe vers le serveur de l'autre contexte. */
        @Autowired
        TestRestTemplate untrustedRest;

        @Test
        void forwardedForFromUntrustedPeerIsIgnored() {
            // Une IP différente à chaque requête ne permet pas de contourner le quota
            for (int i = 0; i < 3; i++) {
                assertThat(postEvent(untrustedRest, "198.51.100." + (20 + i)))
                        .isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            }
            assertThat(postEvent(untrustedRest, "198.51.100.99")).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
    }
}
