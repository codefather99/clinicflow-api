package com.clinicflow.web.api;

import com.clinicflow.web.error.ApiValidationException;

final class ApiSupport {
    private ApiSupport() {}

    static String requireKey(String key) {
        if (key == null || key.isBlank() || key.length() < 8 || key.length() > 64)
            throw new ApiValidationException("Idempotency-Key", "Send an Idempotency-Key header of 8 to 64 characters, one per form submission");
        return key;
    }

    static void requireToken(String token) {
        if (token == null || token.isBlank()) throw new com.clinicflow.domain.port.BadManageTokenException();
    }
}
