package org.jbatres.library_manager_api.exception;

/** A unique value already exists (email, ISBN...). Mapped to HTTP 409 by GlobalExceptionHandler. */
public class DuplicateResourceException extends RuntimeException {

    public DuplicateResourceException(String message) {
        super(message);
    }
}