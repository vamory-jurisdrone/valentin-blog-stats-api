package com.blog.stats.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Remplace le BasicErrorController de Spring Boot : les erreurs levées hors de Spring MVC
 * ({@code sendError} d'un filtre, exception du conteneur…) sont aussi rendues en RFC 9457,
 * sans aucun détail interne. Seuls les dispatchs ERROR y accèdent (voir SecurityConfig).
 */
@Hidden
@Controller
@RequiredArgsConstructor
@RequestMapping("${server.error.path:${error.path:/error}}")
public class ProblemErrorController implements ErrorController {

    private final ObjectMapper objectMapper;

    @RequestMapping
    public void error(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        HttpStatus status = statusOf(request);
        Object uri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        response.resetBuffer();
        SecurityConfig.writeProblem(response, objectMapper, uri instanceof String s ? s : null, status, detailOf(status));
    }

    private static HttpStatus statusOf(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        HttpStatus status = code instanceof Integer c ? HttpStatus.resolve(c) : null;
        return status == null || !status.isError() ? HttpStatus.INTERNAL_SERVER_ERROR : status;
    }

    /** Messages génériques, alignés sur ceux de GlobalExceptionHandler et SecurityConfig. */
    private static String detailOf(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> "No resource found";
            case BAD_REQUEST -> "Malformed request";
            case INTERNAL_SERVER_ERROR -> "Internal error";
            default -> status.getReasonPhrase();
        };
    }
}
