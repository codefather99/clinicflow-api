package com.clinicflow.domain.port;
public class SlotInPastException extends DomainException {
    public SlotInPastException() { super("That slot is in the past"); }
}
