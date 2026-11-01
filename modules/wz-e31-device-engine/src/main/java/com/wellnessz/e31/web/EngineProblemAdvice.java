package com.wellnessz.e31.web;

import java.net.URI;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.wellnessz.common.web.CorrelationIdFilter;
import com.wellnessz.e31.support.RateLimitedException;

/** 429 with {@code Retry-After} for the engine's own quotas (tenant quota, per-connector webhook limit). */
@RestControllerAdvice(basePackages = "com.wellnessz.e31.web")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EngineProblemAdvice {

    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<ProblemDetail> rateLimited(RateLimitedException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
        pd.setType(URI.create("urn:wellnessz:problem:" + ex.code()));
        pd.setTitle(ex.status().getReasonPhrase());
        pd.setProperty("code", ex.code());
        pd.setProperty("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));
        return ResponseEntity.status(ex.status()).header(HttpHeaders.RETRY_AFTER, Integer.toString(ex.retryAfterSeconds())).body(pd);
    }
}
