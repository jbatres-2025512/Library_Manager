package org.jbatres.library_manager_api.exception;

import org.jbatres.library_manager_api.dto.response.ErrorResponse;
import org.jbatres.library_manager_api.dto.response.ErrorResponse.FieldViolation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Single place that turns every exception into the standard JSON error body
 * { timestamp, status, error, message, path [, fieldErrors] }.
 *
 * Extends ResponseEntityExceptionHandler so Spring's own MVC errors (malformed JSON, wrong HTTP
 * method, unsupported media type, bad parameter types, unknown URL...) get the same body instead of
 * being swallowed by the catch-all 500 handler.
 *
 * Rules: never leak internals (SQL, constraint names, stack traces, class names) to the client;
 * 5xx are logged with their stack trace, 4xx only at debug level.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ------------------------------------------------------------------ domain exceptions

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Object> handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", ex.getMessage(), request.getRequestURI());
    }

    /** More specific than BusinessRuleException, so Spring picks this one: returning twice is a conflict. */
    @ExceptionHandler(LoanAlreadyReturnedException.class)
    public ResponseEntity<Object> handleAlreadyReturned(LoanAlreadyReturnedException ex, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage(), request.getRequestURI());
    }

    /** Loan limit, no stock, sanctioned reader (UserSanctionedException), invalid sort property... */
    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<Object> handleBusinessRule(BusinessRuleException ex, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<Object> handleDuplicate(DuplicateResourceException ex, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, "DUPLICATE_RESOURCE", ex.getMessage(), request.getRequestURI());
    }

    // ------------------------------------------------------------------ persistence

    /** Backstop for unique/FK/CHECK violations that slip past the service checks (e.g. a race on email/ISBN). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Data integrity violation path={} cause={}", request.getRequestURI(),
                ex.getMostSpecificCause().getClass().getSimpleName());
        return respond(HttpStatus.CONFLICT, "CONFLICT",
                "The operation conflicts with existing data", request.getRequestURI());
    }

    /** Row-lock timeout (lock_timeout), deadlock victim, optimistic-lock failure: the client may retry. */
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<Object> handleConcurrency(ConcurrencyFailureException ex, HttpServletRequest request) {
        log.warn("Concurrency failure path={} type={}", request.getRequestURI(), ex.getClass().getSimpleName());
        return respond(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "The request conflicted with another operation in progress; please retry", request.getRequestURI());
    }

    /** Connection pool exhausted / database unreachable: overload, not a bug. */
    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
    public ResponseEntity<Object> handleUnavailable(Exception ex, HttpServletRequest request) {
        log.error("Database unavailable or pool exhausted path={} type={}", request.getRequestURI(),
                ex.getClass().getSimpleName());
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "1");
        return respond(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "The service is temporarily unavailable; please retry shortly", request.getRequestURI(), null, headers);
    }

    // ------------------------------------------------------------------ validation

    /** Bean Validation on @RequestBody (@Valid): one entry per invalid field. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        ex.getBindingResult().getFieldErrors().forEach(fe -> violations.add(
                new FieldViolation(fe.getField(), fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid value")));
        ex.getBindingResult().getGlobalErrors().forEach(ge -> violations.add(
                new FieldViolation(ge.getObjectName(), ge.getDefaultMessage() != null ? ge.getDefaultMessage() : "Invalid value")));
        violations.sort(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message));
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed", path(request), violations, null);
    }

    /** Bean Validation on parameters / services (@Validated). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        List<FieldViolation> violations = ex.getConstraintViolations().stream()
                .map(v -> new FieldViolation(v.getPropertyPath().toString(), v.getMessage()))
                .sorted(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message))
                .toList();
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed",
                request.getRequestURI(), violations, null);
    }

    /** Malformed JSON, wrong types, unknown enum value... The Jackson message is NOT echoed back. */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "Malformed or unreadable request body", path(request));
    }

    // ------------------------------------------------------------------ security (thrown from controllers / @PreAuthorize)

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return respond(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "You do not have permission to perform this action", request.getRequestURI());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Object> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return respond(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
                "Authentication is required to access this resource", request.getRequestURI(), null, headers);
    }

    // ------------------------------------------------------------------ Spring MVC standard exceptions (405, 415, 404, 400...)

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode statusCode, WebRequest request) {
        String message;
        if (statusCode.value() == HttpStatus.NOT_FOUND.value()) {
            message = "The requested resource was not found";
        } else if (ex instanceof org.springframework.web.ErrorResponse springError
                && springError.getBody().getDetail() != null) {
            message = springError.getBody().getDetail();
        } else {
            message = reasonPhrase(statusCode);
        }
        if (statusCode.is5xxServerError()) {
            log.error("Server error path={}", path(request), ex);
        }
        return respond(statusCode, errorCodeFor(statusCode), message, path(request), null, headers);
    }

    // ------------------------------------------------------------------ catch-all

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, HttpServletRequest request) {
        String reference = UUID.randomUUID().toString().substring(0, 8);
        log.error("Unhandled exception ref={} path={}", reference, request.getRequestURI(), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred. Reference: " + reference, request.getRequestURI());
    }

    // ------------------------------------------------------------------ helpers

    private ResponseEntity<Object> respond(HttpStatusCode status, String code, String message, String path) {
        return respond(status, code, message, path, null, null);
    }

    private ResponseEntity<Object> respond(HttpStatusCode status, String code, String message, String path,
                                           List<FieldViolation> fieldErrors, HttpHeaders headers) {
        ErrorResponse body = fieldErrors == null
                ? ErrorResponse.of(status.value(), code, message, path)
                : ErrorResponse.of(status.value(), code, message, path, fieldErrors);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
        if (headers != null) {
            builder.headers(extra -> headers.forEach((name, values) -> {
                if (!HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name)) {
                    extra.addAll(name, values);
                }
            }));
        }
        return builder.body(body);
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest servletRequest
                ? servletRequest.getRequest().getRequestURI()
                : "";
    }

    private static String reasonPhrase(HttpStatusCode status) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        return resolved != null ? resolved.getReasonPhrase() : "Request failed";
    }

    private static String errorCodeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "BAD_REQUEST";
            case 404 -> "RESOURCE_NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            case 503 -> "SERVICE_UNAVAILABLE";
            default -> status.is5xxServerError() ? "INTERNAL_ERROR" : "REQUEST_ERROR";
        };
    }
}