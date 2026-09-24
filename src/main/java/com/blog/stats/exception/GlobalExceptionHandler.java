package com.blog.stats.exception;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Toutes les erreurs sortent au format RFC 9457 (ProblemDetail), sans stack trace. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /** Royaume annoncé par {@code WWW-Authenticate} (identique à celui des erreurs Bearer). */
    static final String TOKEN_REALM = "stats-api";

    @ExceptionHandler(ArticleNotFoundException.class)
    public ProblemDetail handleNotFound(ArticleNotFoundException ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    /** Paramètres métier invalides : period inconnu, from > to, limit hors bornes… */
    @ExceptionHandler(BadRequestException.class)
    public ProblemDetail handleBadParameter(BadRequestException ex, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    /**
     * Refus de {@code POST /api/auth/token} : RFC 9457 + code RFC 6749 dans {@code error} ;
     * un échec d'authentification du client annonce le schéma Basic (RFC 6749 §5.2).
     */
    @ExceptionHandler(TokenRequestException.class)
    public ResponseEntity<ProblemDetail> handleTokenRequest(TokenRequestException ex, HttpServletRequest request) {
        ProblemDetail pd = problem(ex.getStatus(), ex.getMessage(), request);
        pd.setProperty("error", ex.getError());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.getStatus())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache");
        if (ex.getStatus() == HttpStatus.UNAUTHORIZED) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"" + TOKEN_REALM + "\"");
        }
        return response.body(pd);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, ex.getName() + " has an invalid value", request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error on {}", request.getRequestURI(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            @NonNull MethodArgumentNotValidException ex, @NonNull HttpHeaders headers,
            @NonNull HttpStatusCode status, @NonNull WebRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining(", "));
        ProblemDetail body = ex.getBody();
        body.setDetail(detail);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            @NonNull HandlerMethodValidationException ex, @NonNull HttpHeaders headers,
            @NonNull HttpStatusCode status, @NonNull WebRequest request) {
        String detail = ex.getAllErrors().stream()
                .map(e -> e.getDefaultMessage())
                .collect(Collectors.joining(", "));
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            @NonNull HttpMessageNotReadableException ex, @NonNull HttpHeaders headers,
            @NonNull HttpStatusCode status, @NonNull WebRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed JSON body");
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    private static ProblemDetail problem(HttpStatus status, String detail, HttpServletRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setInstance(URI.create(request.getRequestURI()));
        return pd;
    }
}
