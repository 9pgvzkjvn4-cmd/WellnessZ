package com.wellnessz.common.error;

import java.net.URI;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.wellnessz.common.web.CorrelationIdFilter;

/**
 * Translates exceptions to RFC 9457 problem details. Never leaks stack traces, SQL or PHI to clients;
 * unexpected errors are logged server-side with a correlation id the client can quote to support.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApi(ApiException ex) {
        ProblemDetail pd = problem(ex.status(), ex.code(), ex.getMessage());
        ex.properties().forEach(pd::setProperty);
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, "validation_failed", detail);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleUploadSize(MaxUploadSizeExceededException ex) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "payload_too_large", "Uploaded file exceeds the permitted size");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "bad_request", "Request could not be processed");
    }

    /** Last resort: never leak exception details; the correlation id links the client error to the server log. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) throws Exception {
        if (isSecurityException(ex)) {
            throw ex; // let Spring Security translate these to 403 / 401 (wz-common stays free of a security dependency)
        }
        if (ex instanceof org.springframework.web.ErrorResponse er) {
            // Spring MVC's own exceptions (404, 405, 415 …) keep their status
            HttpStatus status = HttpStatus.resolve(er.getStatusCode().value());
            if (status != null && status.is4xxClientError()) {
                return problem(status, "http_" + status.value(), status.getReasonPhrase());
            }
        }
        log.error("Unhandled exception correlationId={}", MDC.get(CorrelationIdFilter.MDC_KEY), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "An unexpected error occurred");
    }

    private static boolean isSecurityException(Throwable ex) {
        for (Class<?> c = ex.getClass(); c != null; c = c.getSuperclass()) {
            String n = c.getName();
            if (n.equals("org.springframework.security.access.AccessDeniedException")
                    || n.equals("org.springframework.security.core.AuthenticationException")) {
                return true;
            }
        }
        return false;
    }

    public static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create("urn:wellnessz:problem:" + code));
        pd.setTitle(status.getReasonPhrase());
        pd.setProperty("code", code);
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            pd.setProperty("correlationId", correlationId);
        }
        if (status.is5xxServerError()) {
            log.error("Server error code={} correlationId={}", code, correlationId);
        }
        return pd;
    }
}
