package com.blog.stats.controller;

import com.blog.stats.dto.TokenRequestForm;
import com.blog.stats.dto.TokenResponse;
import com.blog.stats.exception.TokenRequestException;
import com.blog.stats.service.TokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Point de jeton OAuth2, grant {@code client_credentials} uniquement (RFC 6749 §4.4) : le backend Symfony
 * s'y authentifie avec son identifiant et son secret, et reçoit un JWT court à présenter aux autres routes.
 */
@Tag(name = "Authentification", description = "Jetons d'accès applicatifs (OAuth2 client credentials)")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class TokenController {

    private static final String BASIC_PREFIX = "Basic ";
    private static final List<String> SINGLE_VALUED =
            List.of("grant_type", "scope", "client_id", "client_secret");

    private final TokenService tokenService;

    @Operation(summary = "Délivre un jeton d'accès (client credentials)",
            description = """
                    Authentification du client en HTTP Basic (`client_id:client_secret`, RFC 6749 §2.3.1) \
                    ou par les paramètres `client_id` / `client_secret` du formulaire (pas les deux). \
                    Le JWT (HS256) dure `expires_in` secondes et s'envoie ensuite dans \
                    `Authorization: Bearer <jwt>`. 10 demandes / min / IP.""",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
                    mediaType = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
                    schema = @Schema(implementation = TokenRequestForm.class))))
    @ApiResponse(responseCode = "200", description = "Jeton délivré (Cache-Control: no-store)",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = TokenResponse.class),
                    examples = @ExampleObject(value = """
                            {
                              "access_token": "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJzdGF0cy1hcGkiLCJzdWIiOiJzeW1mb255LWJsb2ciLCJzY29wZSI6InN0YXRzOnJlYWQgYXJ0aWNsZXM6d3JpdGUifQ.c2lnbmF0dXJl",
                              "token_type": "Bearer",
                              "expires_in": 900,
                              "scope": "stats:read articles:write"
                            }""")))
    @ApiResponse(responseCode = "400",
            description = "invalid_request (grant_type absent, paramètre répété), unsupported_grant_type, invalid_scope",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class),
                    examples = @ExampleObject(value = """
                            { "type": "about:blank", "title": "Bad Request", "status": 400,
                              "detail": "Only the client_credentials grant type is supported",
                              "instance": "/api/auth/token", "error": "unsupported_grant_type" }""")))
    @ApiResponse(responseCode = "401", description = "invalid_client : client inconnu ou mauvais secret",
            headers = @Header(name = HttpHeaders.WWW_AUTHENTICATE, description = "Basic realm=\"stats-api\""),
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class),
                    examples = @ExampleObject(value = """
                            { "type": "about:blank", "title": "Unauthorized", "status": 401,
                              "detail": "Client authentication failed",
                              "instance": "/api/auth/token", "error": "invalid_client" }""")))
    @ApiResponse(responseCode = "429", description = "Plus de 10 demandes de jeton dans la minute pour cette IP",
            headers = @Header(name = HttpHeaders.RETRY_AFTER, description = "Secondes avant la fin de la fenêtre",
                    schema = @Schema(type = "integer")),
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(path = "/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TokenResponse> token(HttpServletRequest request) {
        for (String name : SINGLE_VALUED) {
            String[] values = request.getParameterValues(name);
            if (values != null && values.length > 1) {
                throw TokenRequestException.invalidRequest("Parameter " + name + " must not be repeated");
            }
        }
        String[] credentials = clientCredentials(request);
        TokenResponse body = tokenService.issueToken(credentials[0], credentials[1],
                request.getParameter("grant_type"), request.getParameter("scope"));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(body);
    }

    /** {@code [client_id, client_secret]} lus en HTTP Basic ou dans le formulaire ; éléments nuls si absents. */
    static String[] clientCredentials(HttpServletRequest request) {
        String formId = request.getParameter("client_id");
        String formSecret = request.getParameter("client_secret");
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || authorization.isBlank()) {
            return new String[] {formId, formSecret};
        }
        if (!authorization.regionMatches(true, 0, BASIC_PREFIX, 0, BASIC_PREFIX.length())) {
            throw TokenRequestException.invalidClient();
        }
        if (formSecret != null) {
            // RFC 6749 §2.3 : une seule méthode d'authentification du client par requête
            throw TokenRequestException.invalidRequest(
                    "Use either HTTP Basic or client_id/client_secret parameters, not both");
        }
        String[] basic = decodeBasic(authorization.substring(BASIC_PREFIX.length()).trim());
        if (formId != null && !formId.equals(basic[0])) {
            throw TokenRequestException.invalidRequest("client_id parameter does not match HTTP Basic credentials");
        }
        return basic;
    }

    /** {@code base64(urlencode(id) ":" urlencode(secret))} (RFC 6749 §2.3.1). */
    private static String[] decodeBasic(String encoded) {
        try {
            String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            int colon = decoded.indexOf(':');
            if (colon < 0) {
                throw TokenRequestException.invalidClient();
            }
            return new String[] {
                    URLDecoder.decode(decoded.substring(0, colon), StandardCharsets.UTF_8),
                    URLDecoder.decode(decoded.substring(colon + 1), StandardCharsets.UTF_8)};
        } catch (IllegalArgumentException e) {
            // Base64 ou encodage URL invalide
            throw TokenRequestException.invalidClient();
        }
    }
}
