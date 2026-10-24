package com.clinicflow.domain.port;
import java.time.LocalDate;
import java.util.UUID;

public interface QueuePort {
    /**
     * Moves an appointment along the status sequence. expected is what the screen believed the status was.
     * @throws StaleStateException if expected does not match; InvalidTransitionException if the move is not allowed
     */
    StaffView changeStatus(UUID appointmentId, Status expected, Status target, String reason, Actor actor);
    /** Creates a slotless appointment already WAITING. aheadOfQueue puts them at the front of the waiting list. */
    StaffView admitWalkIn(WalkInCommand command);
    QueueSnapshot queue(LocalDate date);
}
