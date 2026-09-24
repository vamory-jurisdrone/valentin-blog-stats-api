package com.blog.stats.controller;

import com.blog.stats.config.OpenApiConfig;
import com.blog.stats.config.Scopes;
import com.blog.stats.dto.ArticleSyncRequest;
import com.blog.stats.service.ArticleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Synchronisation des articles, appelée par Symfony avec un JWT portant le scope articles:write. */
@Tag(name = "Articles", description = "Copie minimale des articles Symfony (titre, date de création)")
@SecurityRequirement(name = OpenApiConfig.OAUTH2_SCHEME, scopes = Scopes.ARTICLES_WRITE)
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
@RestController
@RequestMapping("/api/articles")
@RequiredArgsConstructor
public class ArticleController {

    private final ArticleService articleService;

    @Operation(summary = "Crée ou met à jour un article",
            description = "Upsert du titre et de la date de création ; restaure un article supprimé.")
    @ApiResponse(responseCode = "204", description = "Article synchronisé")
    @ApiResponse(responseCode = "400",
            description = "Id non positif, corps invalide ou createdAt hors bornes (avant 1970 ou après demain)")
    @ApiResponse(responseCode = "401", description = "Jeton absent, invalide ou expiré")
    @ApiResponse(responseCode = "403", description = "Jeton sans le scope articles:write")
    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void upsert(
            @Parameter(description = "Id de l'article côté Symfony", example = "42") @PathVariable Long id,
            @Valid @RequestBody ArticleSyncRequest request) {
        articleService.upsert(id, request);
    }

    @Operation(summary = "Supprime un article (logiquement)",
            description = "Marque l'article supprimé : il sort du classement, ses stats sont conservées. "
                    + "Idempotent : un id inconnu renvoie aussi 204.")
    @ApiResponse(responseCode = "204", description = "Article supprimé ou déjà absent")
    @ApiResponse(responseCode = "400", description = "Id non positif")
    @ApiResponse(responseCode = "401", description = "Jeton absent, invalide ou expiré")
    @ApiResponse(responseCode = "403", description = "Jeton sans le scope articles:write")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @Parameter(description = "Id de l'article côté Symfony", example = "42") @PathVariable Long id) {
        articleService.softDelete(id);
    }
}
