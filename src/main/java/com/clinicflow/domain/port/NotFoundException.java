package com.clinicflow.domain.port;
public class NotFoundException extends DomainException {
    public NotFoundException(String what) { super(what + " not found"); }
}
