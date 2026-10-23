package com.clinicflow.domain.port;
public class ClinicClosedException extends DomainException {
    public ClinicClosedException() { super("The clinic is closed that day"); }
}
