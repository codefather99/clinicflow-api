package com.clinicflow.domain.port;
public class InvalidTransitionException extends DomainException {
    private final StaffView current;
    public InvalidTransitionException(String message, StaffView current) { super(message); this.current = current; }
    public StaffView current() { return current; }
}
