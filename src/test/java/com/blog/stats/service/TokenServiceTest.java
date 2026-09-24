package com.blog.stats.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.blog.stats.config.StatsProperties;
import com.blog.stats.dto.TokenResponse;
import com.blog.stats.exception.TokenRequestException;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

class TokenServiceTest {

    static final Instant NOW = Instant.parse("2026-09-24T09:00:00Z");
    static final SecretKeySpec KEY = new SecretKeySpec(
            "token-service-test-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8), "HmacSHA256");

    private final JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(KEY));
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private static StatsProperties properties(int ttl, List<StatsProperties.Client> clients) {
        return new StatsProperties(new StatsProperties.Auth("unused-here", ttl, clients), null, null, 30, 13, 5);
    }

    private static StatsProperties.Client client(String id, String secret, List<String> scopes) {
        return new StatsProperties.Client(id, secret, scopes);
    }

    private TokenService service(List<StatsProperties.Client> clients) {
        return new TokenService(properties(10, clients), encoder, clock);
    }

    @Test
    void issuesAJwtWithTheConfiguredLifetimeAndClaims() {
        TokenService service = service(List.of(client("symfony-blog", "s3cret", List.of("stats:read"))));
        TokenResponse response = service.issueToken("symfony-blog", "s3cret", "client_credentials", null);
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresIn()).isEqualTo(600);
        assertThat(response.scope()).isEqualTo("stats:read");

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(KEY).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(jwt -> OAuth2TokenValidatorResult.success()); // horloge figée dans le passé
        Jwt jwt = decoder.decode(response.accessToken());
        assertThat(jwt.getIssuedAt()).isEqualTo(NOW);
        assertThat(jwt.getExpiresAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(jwt.getSubject()).isEqualTo("symfony-blog");
    }

    @Test
    void secretComparisonIsExact() {
        TokenService service = service(List.of(client("app", "s3cret-clé", List.of("stats:read"))));
        assertThat(service.issueToken("app", "s3cret-clé", "client_credentials", null)).isNotNull();
        for (String wrong : List.of("s3cret-cle", "s3cret", "s3cret-clé ", "", "S3CRET-CLÉ")) {
            assertThatThrownBy(() -> service.issueToken("app", wrong, "client_credentials", null))
                    .isInstanceOfSatisfying(TokenRequestException.class, e -> {
                        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                        assertThat(e.getError()).isEqualTo("invalid_client");
                    });
        }
        assertThatThrownBy(() -> service.issueToken(null, "s3cret-clé", "client_credentials", null))
                .isInstanceOf(TokenRequestException.class);
        assertThatThrownBy(() -> service.issueToken("app", null, "client_credentials", null))
                .isInstanceOf(TokenRequestException.class);
    }

    @Test
    void invalidConfigurationPreventsStartup() {
        assertThatThrownBy(() -> service(List.of())).hasMessageContaining("at least one client");
        assertThatThrownBy(() -> service(null)).hasMessageContaining("at least one client");
        assertThatThrownBy(() -> service(List.of(client(" ", "s", List.of("stats:read")))))
                .hasMessageContaining("id must not be blank");
        assertThatThrownBy(() -> service(List.of(client("app", "", List.of("stats:read")))))
                .hasMessageContaining("secret of client 'app' must not be blank");
        assertThatThrownBy(() -> service(List.of(client("app", "s", List.of("admin")))))
                .hasMessageContaining("non-empty subset");
        assertThatThrownBy(() -> service(List.of(client("app", "s", null))))
                .hasMessageContaining("non-empty subset");
        assertThatThrownBy(() -> service(List.of(client("app", "s", List.of("stats:read")),
                client("app", "t", List.of("stats:read"))))).hasMessageContaining("Duplicate client id");
        assertThatThrownBy(() -> new TokenService(properties(0, List.of(client("app", "s", List.of("stats:read")))),
                encoder, clock)).hasMessageContaining("token-ttl-minutes");
        assertThatThrownBy(() -> new TokenService(new StatsProperties(null, null, null, 30, 13, 5), encoder, clock))
                .isInstanceOf(IllegalStateException.class);
    }
}
