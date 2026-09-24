package com.blog.stats.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * JWT applicatifs signés en HS256 : l'API émet les jetons ({@code POST /api/auth/token}) et les vérifie
 * (resource server), une seule clé symétrique suffit. La clé ({@code stats.auth.jwt-secret},
 * variable STATS_JWT_SECRET) doit faire au moins 32 octets : sinon le démarrage échoue.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(StatsProperties.class)
public class JwtConfig {

    /** Émetteur ({@code iss}) et unique audience ({@code aud}) des jetons. */
    public static final String ISSUER = "stats-api";
    public static final String AUDIENCE = "stats-api";

    /** HS256 exige une clé d'au moins 256 bits (RFC 7518 §3.2). */
    static final int MIN_SECRET_BYTES = 32;
    /** Tolérance sur exp / nbf entre horloges. */
    static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    /** Valeurs par défaut de application.yml / compose.yaml, connues de tous. */
    static final String DEFAULT_JWT_SECRET = "dev-only-jwt-secret-change-me-0123456789";
    static final String DEFAULT_CLIENT_SECRET = "change-me";
    static final int MIN_CLIENT_SECRET_LENGTH = 16;

    private final StatsProperties properties;

    public JwtConfig(StatsProperties properties) {
        this.properties = properties;
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey(properties)));
    }

    /** Vérifie signature HS256 (seul algorithme accepté), exp / nbf (horloge injectée, 30 s de tolérance), iss et aud. */
    @Bean
    public JwtDecoder jwtDecoder(ObjectProvider<Clock> clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(signingKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(validator(clock.getIfAvailable(Clock::systemUTC)));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> validator(Clock clock) {
        JwtTimestampValidator timestamps = new JwtTimestampValidator(CLOCK_SKEW);
        timestamps.setClock(clock);
        return new DelegatingOAuth2TokenValidator<>(
                timestamps,
                // Un jeton sans exp serait valable indéfiniment
                new JwtClaimValidator<Instant>(JwtClaimNames.EXP, Objects::nonNull),
                new JwtIssuerValidator(ISSUER),
                new JwtAudienceValidator(AUDIENCE));
    }

    /** Clé HMAC-SHA256 tirée de {@code stats.auth.jwt-secret} (octets UTF-8) ; échoue si elle est trop courte. */
    static SecretKey signingKey(StatsProperties properties) {
        String secret = properties.auth() == null ? null : properties.auth().jwtSecret();
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("stats.auth.jwt-secret (STATS_JWT_SECRET) must be at least "
                    + MIN_SECRET_BYTES + " bytes for HS256, got " + bytes.length
                    + ". Generate one with: openssl rand -base64 48");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    /** Alerte bien visible dans les logs de démarrage si des secrets par défaut ou faibles sont en place. */
    @EventListener(ApplicationReadyEvent.class)
    public void warnAboutInsecureSecrets() {
        List<String> problems = insecureSettings(properties.auth());
        if (!problems.isEmpty()) {
            // Volontairement en ERROR et encadré : doit sauter aux yeux dans les logs de démarrage
            log.error("""

                    ************************************************************************
                    *  INSECURE AUTH SETTINGS:
                    *    - {}
                    *  Anyone who knows these defaults can get a token, read the stats
                    *  and edit articles. Before going to production set:
                    *    STATS_CLIENT_SECRET  (openssl rand -hex 32)
                    *    STATS_JWT_SECRET     (openssl rand -base64 48)
                    ************************************************************************""",
                    String.join("\n*    - ", problems));
        }
    }

    /** Secrets par défaut ou trop courts pour résister à une recherche exhaustive. */
    static List<String> insecureSettings(StatsProperties.Auth auth) {
        List<String> problems = new ArrayList<>();
        if (auth == null) {
            return problems;
        }
        if (DEFAULT_JWT_SECRET.equals(auth.jwtSecret())) {
            problems.add("stats.auth.jwt-secret is the default value");
        }
        if (auth.clients() != null) {
            for (StatsProperties.Client client : auth.clients()) {
                String secret = client.secret();
                if (secret != null && (DEFAULT_CLIENT_SECRET.equals(secret) || secret.length() < MIN_CLIENT_SECRET_LENGTH)) {
                    problems.add("secret of client '" + client.id() + "' is the default value or shorter than "
                            + MIN_CLIENT_SECRET_LENGTH + " characters");
                }
            }
        }
        return problems;
    }
}
