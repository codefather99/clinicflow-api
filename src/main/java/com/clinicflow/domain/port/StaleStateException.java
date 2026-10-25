package com.clinicflow.domain.port;
public class StaleStateException extends DomainException {
    private final StaffView current;
    public StaleStateException(StaffView current) {
        super("Someone else already changed this appointment");
        this.current = current;
    }
    public StaffView current() { return current; }
}
