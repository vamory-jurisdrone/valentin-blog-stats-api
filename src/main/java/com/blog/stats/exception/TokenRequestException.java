package com.blog.stats.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Demande de jeton refusée ({@code POST /api/auth/token}) : rendue en RFC 9457 avec le code d'erreur
 * RFC 6749 §5.2 dans la propriété {@code error}.
 */
@Getter
public class TokenRequestException extends RuntimeException {

    public static final String INVALID_REQUEST = "invalid_request";
    public static final String INVALID_CLIENT = "invalid_client";
    public static final String UNSUPPORTED_GRANT_TYPE = "unsupported_grant_type";
    public static final String INVALID_SCOPE = "invalid_scope";

    private final HttpStatus status;
    private final String error;

    public TokenRequestException(HttpStatus status, String error, String description) {
        super(description);
        this.status = status;
        this.error = error;
    }

    public static TokenRequestException invalidRequest(String description) {
        return new TokenRequestException(HttpStatus.BAD_REQUEST, INVALID_REQUEST, description);
    }

    /** Même message pour un client inconnu et un mauvais secret : on ne révèle pas quels clients existent. */
    public static TokenRequestException invalidClient() {
        return new TokenRequestException(HttpStatus.UNAUTHORIZED, INVALID_CLIENT, "Client authentication failed");
    }
}
