package com.clinicflow.domain.port;
public class UnknownVisitTypeException extends DomainException {
    public UnknownVisitTypeException(String code) { super("Unknown visit type: " + code); }
}
