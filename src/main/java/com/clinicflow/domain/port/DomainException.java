package com.clinicflow.domain.port;
/** Base type. web.GlobalExceptionHandler maps each subtype to an HTTP status and error code. */
public abstract class DomainException extends RuntimeException {
    protected DomainException(String message) { super(message); }
}
