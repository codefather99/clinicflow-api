package com.clinicflow.domain.port;
import java.util.UUID;

public interface BookingPort {
    /** @throws SlotTakenException, SlotInPastException, UnknownVisitTypeException, NotFoundException (no such slot) */
    BookingResult book(BookCommand command);
    /** Idempotent: cancelling an already cancelled booking returns the same view. Only while BOOKED. */
    PatientView cancelByPatient(String reference, String manageToken, String reason);
    PatientView rescheduleByPatient(String reference, String manageToken, UUID newSlotId);
    StaffView rescheduleByStaff(UUID appointmentId, UUID newSlotId, Actor actor);
}
