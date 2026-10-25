package com.clinicflow.web.error;

import com.clinicflow.web.dto.Dto;

import java.util.List;

/** Thrown by controllers for input problems Bean Validation cannot express (missing header, bad page size). */
public class ApiValidationException extends RuntimeException {
    private final List<Dto.FieldError> fieldErrors;

    public ApiValidationException(String field, String message) {
        super(message);
        this.fieldErrors = List.of(new Dto.FieldError(field, message));
    }

    public List<Dto.FieldError> fieldErrors() { return fieldErrors; }
}
