package com.blog.stats.controller;

import com.blog.stats.config.OpenApiConfig;
import com.blog.stats.config.Scopes;
import com.blog.stats.dto.ArticleStatsResponse;
import com.blog.stats.exception.BadRequestException;
import com.blog.stats.dto.TopResponse;
import com.blog.stats.dto.TrendsResponse;
import com.blog.stats.service.StatsService;
import com.blog.stats.util.Period;
import com.blog.stats.util.TimeZones;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints de lecture des stats, appelés par Symfony avec un JWT portant le scope stats:read. */
@RestController
@RequestMapping(value = "/api/stats", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "Stats", description = "Statistiques de lecture des articles (JWT, scope stats:read)")
@SecurityRequirement(name = OpenApiConfig.OAUTH2_SCHEME, scopes = Scopes.STATS_READ)
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
public class StatsController {

    static final int MAX_LIMIT = 50;
    static final int MAX_TREND_DAYS = 366;
    static final int DEFAULT_TREND_DAYS = 30;

    private final StatsService statsService;
    private final Clock clock;

    @GetMapping("/articles/{id}")
    @Operation(summary = "Statistiques d'un article",
            description = "Calcul exact depuis les événements bruts, sur toute la vie de l'article ou une période.")
    @ApiResponse(responseCode = "200", description = "Statistiques de l'article",
            content = @Content(schema = @Schema(implementation = ArticleStatsResponse.class),
                    examples = @ExampleObject(value = """
                            {
                              "articleId": 42,
                              "title": "Débuter avec Spring Boot",
                              "period": "30d",
                              "views": 1280,
                              "uniqueReaders": 954,
                              "avgReadTimeSeconds": 187,
                              "completionRate": 0.41,
                              "lastViewedAt": "2026-09-23T18:04:11Z"
                            }""")))
    @ApiResponse(responseCode = "400", description = "period inconnu", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class),
            examples = @ExampleObject(value = """
                    { "type": "about:blank", "title": "Bad Request", "status": 400,
                      "detail": "period must be one of 24h, 7d, 30d, 90d, all", "instance": "/api/stats/articles/42" }""")))
    @ApiResponse(responseCode = "401", description = "Jeton absent, invalide ou expiré", content = @Content)
    @ApiResponse(responseCode = "403", description = "Jeton sans le scope stats:read", content = @Content)
    @ApiResponse(responseCode = "404", description = "Article inconnu de la table article", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class),
            examples = @ExampleObject(value = """
                    { "type": "about:blank", "title": "Not Found", "status": 404,
                      "detail": "article 42 not found", "instance": "/api/stats/articles/42" }""")))
    public ArticleStatsResponse articleStats(
            @Parameter(description = "id de l'article côté Symfony", example = "42") @PathVariable Long id,
            @Parameter(description = "24h, 7d, 30d, 90d ou all", example = "30d")
            @RequestParam(defaultValue = "all") String period) {
        return statsService.getArticleStats(id, Period.fromValue(period));
    }

    @GetMapping("/top")
    @Operation(summary = "Articles les plus lus",
            description = "Tri par vues décroissantes puis lecteurs uniques ; articles supprimés exclus. Cache 5 min.")
    @ApiResponse(responseCode = "200", description = "Classement",
            content = @Content(schema = @Schema(implementation = TopResponse.class),
                    examples = @ExampleObject(value = """
                            {
                              "period": "7d",
                              "generatedAt": "2026-09-24T09:00:00Z",
                              "items": [
                                { "rank": 1, "articleId": 42, "title": "Débuter avec Spring Boot", "views": 512, "uniqueReaders": 430, "completionRate": 0.47 },
                                { "rank": 2, "articleId": 17, "title": "Symfony vs Spring", "views": 388, "uniqueReaders": 301, "completionRate": 0.33 }
                              ]
                            }""")))
    @ApiResponse(responseCode = "400", description = "period inconnu ou limit hors bornes", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class),
            examples = @ExampleObject(value = """
                    { "type": "about:blank", "title": "Bad Request", "status": 400,
                      "detail": "limit must be between 1 and 50", "instance": "/api/stats/top" }""")))
    @ApiResponse(responseCode = "401", description = "Jeton absent, invalide ou expiré", content = @Content)
    @ApiResponse(responseCode = "403", description = "Jeton sans le scope stats:read", content = @Content)
    public TopResponse top(
            @Parameter(description = "24h, 7d, 30d, 90d ou all", example = "7d")
            @RequestParam(defaultValue = "7d") String period,
            @Parameter(description = "Nombre d'articles, de 1 à 50", example = "5")
            @RequestParam(defaultValue = "5") Integer limit) {
        Period resolved = Period.fromValue(period);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new BadRequestException("limit must be between 1 and " + MAX_LIMIT);
        }
        return statsService.getTop(resolved, limit, today());
    }

    @GetMapping("/trends")
    @Operation(summary = "Vues par jour",
            description = "Un point par jour (Europe/Paris), zéros compris. Sans articleId : tout le blog. Cache 5 min.")
    @ApiResponse(responseCode = "200", description = "Série journalière",
            content = @Content(schema = @Schema(implementation = TrendsResponse.class),
                    examples = @ExampleObject(value = """
                            {
                              "from": "2026-09-01",
                              "to": "2026-09-03",
                              "articleId": null,
                              "totalViews": 734,
                              "points": [
                                { "date": "2026-09-01", "views": 210, "uniqueReaders": 180 },
                                { "date": "2026-09-02", "views": 0, "uniqueReaders": 0 },
                                { "date": "2026-09-03", "views": 524, "uniqueReaders": 410 }
                              ]
                            }""")))
    @ApiResponse(responseCode = "400", description = "Date invalide, from > to ou intervalle > 366 jours",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class),
                    examples = @ExampleObject(value = """
                            { "type": "about:blank", "title": "Bad Request", "status": 400,
                              "detail": "from must be before or equal to to", "instance": "/api/stats/trends" }""")))
    @ApiResponse(responseCode = "401", description = "Jeton absent, invalide ou expiré", content = @Content)
    @ApiResponse(responseCode = "403", description = "Jeton sans le scope stats:read", content = @Content)
    @ApiResponse(responseCode = "404", description = "articleId inconnu de la table article", content = @Content)
    public TrendsResponse trends(
            @Parameter(description = "Premier jour (YYYY-MM-DD), défaut : aujourd'hui − 29 j", example = "2026-09-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Dernier jour inclus (YYYY-MM-DD), défaut : aujourd'hui", example = "2026-09-30")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Absent = tout le blog", example = "42")
            @RequestParam(required = false) Long articleId) {
        LocalDate today = today();
        LocalDate end = to != null ? to : today;
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_TREND_DAYS - 1L);
        if (start.isAfter(end)) {
            throw new BadRequestException("from must be before or equal to to");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > MAX_TREND_DAYS) {
            throw new BadRequestException("range must not exceed " + MAX_TREND_DAYS + " days");
        }
        return statsService.getTrends(start, end, articleId, today);
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), TimeZones.PARIS);
    }
}
