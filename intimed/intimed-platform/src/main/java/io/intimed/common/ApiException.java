package io.intimed.common;

import org.springframework.http.HttpStatus;

/** Error mapped to an RFC 9457 problem response; messages never contain PHI or secrets. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    public static ApiException notFound(String what) { return new ApiException(HttpStatus.NOT_FOUND, "not-found", what + " not found"); }
    public static ApiException bad(String code, String msg) { return new ApiException(HttpStatus.BAD_REQUEST, code, msg); }
    public static ApiException conflict(String code, String msg) { return new ApiException(HttpStatus.CONFLICT, code, msg); }
    public static ApiException forbidden(String code, String msg) { return new ApiException(HttpStatus.FORBIDDEN, code, msg); }
    public static ApiException unprocessable(String code, String msg) { return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, msg); }
}
