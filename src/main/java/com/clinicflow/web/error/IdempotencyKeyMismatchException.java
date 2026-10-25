package com.clinicflow.web.error;

public class IdempotencyKeyMismatchException extends RuntimeException {
    public IdempotencyKeyMismatchException() { super("This Idempotency-Key was already used for a different request"); }
}
