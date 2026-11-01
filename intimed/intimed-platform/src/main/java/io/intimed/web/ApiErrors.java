package io.intimed.web;

import io.intimed.common.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;

/** RFC 9457 problem responses; internal details are logged with a correlation id, never returned. */
@RestControllerAdvice
public class ApiErrors {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);

    private static ResponseEntity<ProblemDetail> problem(HttpStatus s, String code, String msg) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(s, msg);
        p.setType(URI.create("https://intimed.io/problems/" + code));
        p.setTitle(code);
        return ResponseEntity.status(s).body(p);
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException e) { return problem(e.status(), e.code(), e.getMessage()); }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ProblemDetail> bad(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", e instanceof HttpMessageNotReadableException ? "malformed request body" : e.getMessage());
    }

    @ExceptionHandler(SecurityException.class)
    ResponseEntity<ProblemDetail> sec(SecurityException e) { return problem(HttpStatus.FORBIDDEN, "security", e.getMessage()); }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> nf(NoResourceFoundException e) { return problem(HttpStatus.NOT_FOUND, "not-found", "not found"); }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> other(Exception e) {
        String ref = io.intimed.common.Hashing.randomToken(6);
        LOG.error("Unhandled error ref={}", ref, e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "Unexpected error (ref " + ref + ")");
    }
}
