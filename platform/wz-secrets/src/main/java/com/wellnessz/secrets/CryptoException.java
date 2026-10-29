package com.wellnessz.secrets;

/** Raised when an encryption operation fails. Never includes key or plaintext material. */
public class CryptoException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CryptoException(String message, Throwable cause) {
        super(message, cause);
    }
}
