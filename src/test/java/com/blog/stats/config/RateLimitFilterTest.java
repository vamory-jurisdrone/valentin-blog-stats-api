package com.blog.stats.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/** Fenêtre fixe d'une minute par IP, pilotée par une horloge de test. */
class RateLimitFilterTest {

    /** Horloge qu'on peut avancer à la main. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-24T09:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();

    private RateLimitFilter filter(int perMinute) {
        return new RateLimitFilter(PathPatternRequestMatcher.withDefaults().matcher("/api/events/**"),
                perMinute, "events", clock, Jackson2ObjectMapperBuilder.json().build());
    }

    private static MockHttpServletResponse send(RateLimitFilter filter, String method, String uri, String ip)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void sixtyFirstEventWithinAMinuteIsRejectedEvenWhenSpread() throws Exception {
        RateLimitFilter filter = filter(60);
        // 60 événements étalés sur 48 s (un toutes les 0,8 s) : aucun jeton ne revient en cours de minute
        for (int i = 0; i < 60; i++) {
            assertThat(send(filter, "POST", "/api/events/view", "203.0.113.1").getStatus()).isEqualTo(200);
            clock.advance(Duration.ofMillis(800));
        }
        MockHttpServletResponse rejected = send(filter, "POST", "/api/events/view", "203.0.113.1");
        assertThat(rejected.getStatus()).isEqualTo(429);
        // Fenêtre ouverte au 1er événement (t = 0), on est à t = 48 s : 12 s avant le rechargement
        assertThat(rejected.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("12");
        assertThat(rejected.getContentType()).isEqualTo("application/problem+json");
        assertThat(rejected.getContentAsString()).contains("\"status\":429").doesNotContain("properties")
                .contains("Too many events, retry in 12 s");

        // Toujours refusé juste avant la fin de la minute…
        clock.advance(Duration.ofMillis(11_900));
        MockHttpServletResponse stillRejected = send(filter, "POST", "/api/events/view", "203.0.113.1");
        assertThat(stillRejected.getStatus()).isEqualTo(429);
        assertThat(stillRejected.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("1");

        // …puis la minute écoulée, le seau est plein à nouveau
        clock.advance(Duration.ofMillis(100));
        for (int i = 0; i < 60; i++) {
            assertThat(send(filter, "POST", "/api/events/view", "203.0.113.1").getStatus()).isEqualTo(200);
        }
        assertThat(send(filter, "POST", "/api/events/view", "203.0.113.1").getStatus()).isEqualTo(429);
    }

    @Test
    void burstOfSixtyOneIsRejectedAndRetryAfterCoversTheWholeWindow() throws Exception {
        RateLimitFilter filter = filter(60);
        for (int i = 0; i < 60; i++) {
            send(filter, "POST", "/api/events/view", "203.0.113.5");
        }
        MockHttpServletResponse rejected = send(filter, "POST", "/api/events/view", "203.0.113.5");
        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("60");
        assertThat(rejected.getHeader(RateLimitFilter.REMAINING_HEADER)).isEqualTo("0");

        // Une seconde plus tard, toujours rien (pas de remplissage progressif)
        clock.advance(Duration.ofSeconds(1));
        assertThat(send(filter, "POST", "/api/events/view", "203.0.113.5").getStatus()).isEqualTo(429);
    }

    @Test
    void retryAfterReflectsWaitTime() throws Exception {
        RateLimitFilter filter = filter(2);
        send(filter, "POST", "/api/events/read", "203.0.113.2");
        clock.advance(Duration.ofSeconds(15));
        send(filter, "POST", "/api/events/read", "203.0.113.2");
        MockHttpServletResponse rejected = send(filter, "POST", "/api/events/read", "203.0.113.2");
        assertThat(rejected.getStatus()).isEqualTo(429);
        // Fenêtre d'une minute ouverte 15 s plus tôt
        assertThat(rejected.getHeader(HttpHeaders.RETRY_AFTER)).isEqualTo("45");
        assertThat(rejected.getHeader(RateLimitFilter.REMAINING_HEADER)).isEqualTo("0");
    }

    @Test
    void ignoresOptionsAndOtherPaths() throws Exception {
        RateLimitFilter filter = filter(1);
        for (int i = 0; i < 5; i++) {
            assertThat(send(filter, "OPTIONS", "/api/events/view", "203.0.113.3").getStatus()).isEqualTo(200);
            assertThat(send(filter, "GET", "/api/stats/top", "203.0.113.3").getStatus()).isEqualTo(200);
        }
        assertThat(send(filter, "POST", "/api/events/view", "203.0.113.3").getStatus()).isEqualTo(200);
        assertThat(send(filter, "POST", "/api/events/view", "203.0.113.3").getStatus()).isEqualTo(429);
    }

    @Test
    void zeroDisablesTheLimit() throws Exception {
        RateLimitFilter filter = filter(0);
        for (int i = 0; i < 100; i++) {
            assertThat(send(filter, "POST", "/api/events/view", "203.0.113.4").getStatus()).isEqualTo(200);
        }
    }
}
