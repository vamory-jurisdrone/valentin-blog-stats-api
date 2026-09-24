package com.blog.stats.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Corps {@code application/x-www-form-urlencoded} de {@code POST /api/auth/token}.
 * Sert uniquement à la documentation OpenAPI : le controller lit les paramètres du formulaire.
 */
@Schema(name = "TokenRequest", requiredProperties = "grant_type")
public record TokenRequestForm(
        @JsonProperty("grant_type") @Schema(allowableValues = "client_credentials", example = "client_credentials")
        String grantType,
        @Schema(description = "Sous-ensemble des scopes du client, séparés par des espaces ; absent = tous",
                example = "stats:read")
        String scope,
        @JsonProperty("client_id") @Schema(description = "Seulement si le client ne s'authentifie pas en HTTP Basic",
                example = "symfony-blog")
        String clientId,
        @JsonProperty("client_secret") @Schema(description = "Seulement si le client ne s'authentifie pas en HTTP Basic",
                format = "password")
        String clientSecret) {
}
