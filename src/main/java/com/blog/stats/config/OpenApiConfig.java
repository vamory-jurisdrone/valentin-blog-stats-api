package com.blog.stats.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documentation OpenAPI (springdoc), servie sur /v3/api-docs et /swagger-ui.html.
 * Deux schémas équivalents pour les routes protégées : {@code oauth2} (client credentials, le bouton
 * <em>Authorize</em> de Swagger UI obtient lui-même un jeton sur /api/auth/token) et {@code bearerJwt}
 * (coller un jeton déjà obtenu). Les controllers les référencent via {@code @SecurityRequirement}.
 * Les réponses produites par les filtres (hors controllers) sont ajoutées ici.
 */
@Configuration
public class OpenApiConfig {

    public static final String OAUTH2_SCHEME = "oauth2";
    public static final String BEARER_SCHEME = "bearerJwt";
    public static final String TOKEN_URL = "/api/auth/token";

    @Bean
    public OpenAPI statsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Blog Stats API")
                        .version("v1")
                        .description("""
                                Microservice de statistiques de lecture du blog.

                                - **Ingestion** (`/api/events/**`) : appelée par le navigateur, publique, \
                                CORS limité au domaine du blog, 60 événements / min / IP (fenêtre fixe).
                                - **Statistiques** (`/api/stats/**`, scope `stats:read`) et **synchronisation \
                                des articles** (`/api/articles/**`, scope `articles:write`) : appelées par Symfony \
                                avec un JWT applicatif (`Authorization: Bearer <jwt>`).
                                - **Jeton** (`POST /api/auth/token`) : OAuth2 client credentials, le client \
                                s'authentifie en HTTP Basic et reçoit un JWT HS256 valable 15 min.

                                Horodatages en ISO 8601 (UTC) ; jours et périodes calculés en heure \
                                française (Europe/Paris). Erreurs au format RFC 9457 (`application/problem+json`)."""))
                .components(new Components()
                        .addSecuritySchemes(OAUTH2_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.OAUTH2)
                                .description("Client credentials : identifiant et secret de l'application "
                                        + "(STATS_CLIENT_ID / STATS_CLIENT_SECRET)")
                                .flows(new OAuthFlows().clientCredentials(new OAuthFlow()
                                        .tokenUrl(TOKEN_URL)
                                        .scopes(new Scopes()
                                                .addString(com.blog.stats.config.Scopes.STATS_READ,
                                                        "Lire les statistiques et les métriques")
                                                .addString(com.blog.stats.config.Scopes.ARTICLES_WRITE,
                                                        "Synchroniser les articles")))))
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT obtenu sur POST " + TOKEN_URL)));
    }

    /** 429 (limitation de débit, écrit par RateLimitFilter) et 415 sur les routes d'ingestion. */
    @Bean
    public OpenApiCustomizer eventsFilterResponses() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, item) -> {
                if (!path.startsWith("/api/events/")) {
                    return;
                }
                item.readOperations().forEach(operation -> {
                    operation.getResponses().computeIfAbsent("429", code -> problem(
                                    "Quota d'événements de la minute dépassé pour cette IP")
                            .addHeaderObject("Retry-After", new Header()
                                    .description("Secondes avant la fin de la fenêtre")
                                    .schema(new IntegerSchema()))
                            .addHeaderObject(RateLimitFilter.REMAINING_HEADER, new Header()
                                    .description("Événements encore acceptés dans la minute")
                                    .schema(new IntegerSchema())));
                    operation.getResponses().computeIfAbsent("415", code -> problem(
                            "Content-Type autre que application/json ou text/plain"));
                });
            });
        };
    }

    private static ApiResponse problem(String description) {
        return new ApiResponse()
                .description(description)
                .content(new Content().addMediaType(
                        org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                        new MediaType().schema(new ObjectSchema())));
    }
}
