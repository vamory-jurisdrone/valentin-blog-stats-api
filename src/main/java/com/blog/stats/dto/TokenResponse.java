package com.blog.stats.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/** Réponse de {@code POST /api/auth/token} (RFC 6749 §5.1). */
public record TokenResponse(
        @JsonProperty("access_token") @Schema(description = "JWT signé HS256 à envoyer dans Authorization: Bearer")
        String accessToken,
        @JsonProperty("token_type") @Schema(example = "Bearer")
        String tokenType,
        @JsonProperty("expires_in") @Schema(description = "Durée de vie en secondes", example = "900")
        long expiresIn,
        @Schema(description = "Scopes accordés, séparés par des espaces", example = "stats:read articles:write")
        String scope) {
}
