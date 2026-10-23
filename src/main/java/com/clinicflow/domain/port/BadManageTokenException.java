package com.clinicflow.domain.port;
public class BadManageTokenException extends DomainException {
    public BadManageTokenException() { super("Invalid manage token"); }
}
