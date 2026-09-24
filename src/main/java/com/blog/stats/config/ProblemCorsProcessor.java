package com.blog.stats.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;

/**
 * Règles CORS standard de Spring, mais un refus répond 403 au format RFC 9457
 * au lieu du texte brut « Invalid CORS request ». Pas un @Component : instancié par SecurityConfig.
 */
class ProblemCorsProcessor extends DefaultCorsProcessor {

    private final ObjectMapper objectMapper;

    ProblemCorsProcessor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean processRequest(@Nullable CorsConfiguration config, HttpServletRequest request,
                                  HttpServletResponse response) throws IOException {
        if (super.processRequest(config, request, response)) {
            return true;
        }
        // Refusée : origine inconnue, ou méthode / en-tête non autorisés pour une origine connue
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        String detail = config == null || config.checkOrigin(origin) == null
                ? "Origin not allowed"
                : "CORS method or header not allowed";
        SecurityConfig.writeProblem(response, objectMapper, request, HttpStatus.FORBIDDEN, detail);
        return false;
    }

    /** Ne fait que poser le statut et recopier les en-têtes (Vary) ; le corps est écrit par processRequest. */
    @Override
    protected void rejectRequest(ServerHttpResponse response) throws IOException {
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getBody();
    }
}
