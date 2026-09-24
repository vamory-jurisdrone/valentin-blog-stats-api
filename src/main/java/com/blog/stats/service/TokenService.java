package com.blog.stats.service;

import com.blog.stats.config.JwtConfig;
import com.blog.stats.config.Scopes;
import com.blog.stats.config.StatsProperties;
import com.blog.stats.dto.TokenResponse;
import com.blog.stats.exception.TokenRequestException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * Émission des JWT applicatifs (OAuth2 client credentials, RFC 6749 §4.4) : authentifie le client par
 * son secret (comparaison en temps constant), vérifie grant_type et scope, puis signe un jeton court.
 */
@Slf4j
@Service
public class TokenService {

    public static final String CLIENT_CREDENTIALS = "client_credentials";
    public static final String TOKEN_TYPE = "Bearer";

    private final Map<String, RegisteredClient> clients;
    private final Duration ttl;
    private final JwtEncoder encoder;
    private final Clock clock;
    /** Comparé quand le client est inconnu, pour que la réponse prenne le même temps. */
    private final byte[] dummyDigest = sha256("unknown-client-" + UUID.randomUUID());

    public TokenService(StatsProperties properties, JwtEncoder encoder, Clock clock) {
        StatsProperties.Auth auth = properties.auth();
        if (auth == null || auth.tokenTtlMinutes() <= 0) {
            throw new IllegalStateException("stats.auth.token-ttl-minutes must be positive");
        }
        this.clients = registeredClients(auth.clients());
        this.ttl = Duration.ofMinutes(auth.tokenTtlMinutes());
        this.encoder = encoder;
        this.clock = clock;
    }

    /**
     * @param clientId     identifiant du client (HTTP Basic ou formulaire), {@code null} si absent
     * @param clientSecret secret du client, {@code null} si absent
     * @param grantType    paramètre {@code grant_type}
     * @param scope        paramètre {@code scope} (scopes séparés par des espaces), {@code null} = tous ceux du client
     * @throws TokenRequestException invalid_client (401), invalid_request, unsupported_grant_type, invalid_scope (400)
     */
    public TokenResponse issueToken(String clientId, String clientSecret, String grantType, String scope) {
        RegisteredClient client = authenticate(clientId, clientSecret);
        if (grantType == null || grantType.isBlank()) {
            throw TokenRequestException.invalidRequest("Missing grant_type parameter");
        }
        if (!CLIENT_CREDENTIALS.equals(grantType)) {
            throw new TokenRequestException(HttpStatus.BAD_REQUEST, TokenRequestException.UNSUPPORTED_GRANT_TYPE,
                    "Only the client_credentials grant type is supported");
        }
        List<String> granted = grantedScopes(client, scope);
        String scopeClaim = String.join(" ", granted);

        Instant issuedAt = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(JwtConfig.ISSUER)
                .audience(List.of(JwtConfig.AUDIENCE))
                .subject(client.id())
                .claim("scope", scopeClaim)
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(ttl))
                .id(UUID.randomUUID().toString())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        log.info("Access token issued to client '{}' (scope: {})", client.id(), scopeClaim);
        return new TokenResponse(token, TOKEN_TYPE, ttl.toSeconds(), scopeClaim);
    }

    /** Client inconnu et mauvais secret donnent la même erreur, dans le même temps. */
    private RegisteredClient authenticate(String clientId, String clientSecret) {
        if (clientId == null || clientId.isEmpty() || clientSecret == null) {
            throw TokenRequestException.invalidClient();
        }
        RegisteredClient client = clients.get(clientId);
        // Empreintes SHA-256 de taille fixe : la comparaison ne dépend ni du contenu ni de la longueur du secret
        byte[] expected = client == null ? dummyDigest : client.secretDigest();
        boolean secretMatches = MessageDigest.isEqual(expected, sha256(clientSecret));
        if (client == null || !secretMatches) {
            log.warn("Token request rejected: client authentication failed for client id '{}'", sanitize(clientId));
            throw TokenRequestException.invalidClient();
        }
        return client;
    }

    /** Scopes demandés s'ils sont tous autorisés au client, dans l'ordre de sa configuration. */
    private static List<String> grantedScopes(RegisteredClient client, String scope) {
        if (scope == null || scope.isBlank()) {
            return client.scopes();
        }
        Set<String> requested = Arrays.stream(scope.trim().split(" +")).collect(Collectors.toSet());
        if (!client.scopes().containsAll(requested)) {
            throw new TokenRequestException(HttpStatus.BAD_REQUEST, TokenRequestException.INVALID_SCOPE,
                    "Requested scope is unknown or not allowed for this client");
        }
        return client.scopes().stream().filter(requested::contains).toList();
    }

    /** Valide la configuration au démarrage : un client mal déclaré doit empêcher l'API de démarrer. */
    static Map<String, RegisteredClient> registeredClients(List<StatsProperties.Client> configured) {
        if (configured == null || configured.isEmpty()) {
            throw new IllegalStateException("stats.auth.clients must declare at least one client");
        }
        Map<String, RegisteredClient> byId = new LinkedHashMap<>();
        for (StatsProperties.Client client : configured) {
            if (client.id() == null || client.id().isBlank()) {
                throw new IllegalStateException("stats.auth.clients[].id must not be blank");
            }
            if (client.secret() == null || client.secret().isBlank()) {
                throw new IllegalStateException("stats.auth.clients[].secret of client '" + client.id()
                        + "' must not be blank");
            }
            List<String> scopes = client.scopes() == null ? List.of() : client.scopes().stream()
                    .map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
            if (scopes.isEmpty() || !Scopes.ALL.containsAll(scopes)) {
                throw new IllegalStateException("stats.auth.clients[].scopes of client '" + client.id()
                        + "' must be a non-empty subset of " + Scopes.ALL);
            }
            if (byId.put(client.id(), new RegisteredClient(client.id(), sha256(client.secret()), scopes)) != null) {
                throw new IllegalStateException("Duplicate client id in stats.auth.clients: " + client.id());
            }
        }
        return Map.copyOf(byId);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Identifiant fourni par le client, tronqué et sans caractères de contrôle avant d'être journalisé. */
    private static String sanitize(String value) {
        String cleaned = value.replaceAll("\\p{Cntrl}", "?");
        return cleaned.length() > 64 ? cleaned.substring(0, 64) + "…" : cleaned;
    }

    record RegisteredClient(String id, byte[] secretDigest, List<String> scopes) {}
}
