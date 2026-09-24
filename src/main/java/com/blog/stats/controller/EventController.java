package com.blog.stats.controller;

import com.blog.stats.dto.ReadEventRequest;
import com.blog.stats.dto.ViewEventRequest;
import com.blog.stats.service.EventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ingestion publique : appelée par le front du blog (fetch ou navigator.sendBeacon).
 * sendBeacon envoie souvent le JSON en text/plain : les deux types sont acceptés (voir WebConfig).
 */
@Tag(name = "Événements", description = "Collecte des vues et lectures envoyées par le front du blog")
@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @Operation(summary = "Enregistre une vue",
            description = "Toujours 202 si le corps est valide. Ignorée pour les bots, les articles inconnus "
                    + "ou supprimés, et les doublons (même session et article sous 30 min).")
    @ApiResponse(responseCode = "202", description = "Événement accepté")
    @ApiResponse(responseCode = "400", description = "Corps invalide")
    @PostMapping(path = "/view", consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_PLAIN_VALUE})
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void view(
            @Valid @RequestBody ViewEventRequest request,
            @Parameter(hidden = true) @RequestHeader(value = HttpHeaders.USER_AGENT, required = false)
                    String userAgent) {
        eventService.recordView(request, userAgent);
    }

    @Operation(summary = "Enregistre une lecture (temps passé, scroll)",
            description = "Toujours 202 si le corps est valide. La lecture en cours de la même session "
                    + "(moins de 30 min, même jour à Paris, pas de nouvelle vue depuis) est mise à jour avec "
                    + "les valeurs maximales au lieu d'être dupliquée.")
    @ApiResponse(responseCode = "202", description = "Événement accepté")
    @ApiResponse(responseCode = "400", description = "Corps invalide")
    @PostMapping(path = "/read", consumes = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_PLAIN_VALUE})
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void read(
            @Valid @RequestBody ReadEventRequest request,
            @Parameter(hidden = true) @RequestHeader(value = HttpHeaders.USER_AGENT, required = false)
                    String userAgent) {
        eventService.recordRead(request, userAgent);
    }
}
