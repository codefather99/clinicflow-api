package com.clinicflow.web.dto;

import com.clinicflow.domain.port.ActorRole;
import com.clinicflow.domain.port.Status;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Wire shapes. Names and types match openapi/openapi.yaml exactly. Change the YAML first, then this file. */
public final class Dto {
    private Dto() {}

    // ------------------------------------------------------------ responses
    public record Meta(String clinicName, boolean demoMode, Instant serverTime, String timezone, String version) {}
    public record VisitType(int id, String code, String label, int durationMinutes) {}
    public record Slot(UUID id, Instant startsAt, Instant endsAt, boolean available) {}
    public record SlotWindow(Instant startsAt, Instant endsAt) {}
    public record Estimate(int minutes, @JsonProperty("isEstimate") boolean isEstimate, String basis) {}

    public record PatientAppointment(String reference, String patientName, VisitType visitType, SlotWindow slot,
                                     Status status, Integer queuePosition, Integer peopleAhead, Estimate estimate,
                                     boolean canCancel, boolean canReschedule, Instant updatedAt) {}

    public record BookingCreated(PatientAppointment appointment, String manageToken) {}

    public record StaffAppointment(UUID id, String reference, String patientName, String contact, VisitType visitType,
                                   SlotWindow slot, @JsonProperty("isWalkIn") boolean isWalkIn, Status status,
                                   Integer queuePosition, Instant checkedInAt, Instant consultationStartedAt,
                                   Instant completedAt, Instant cancelledAt, String cancelReason, int version,
                                   Instant createdAt, Instant updatedAt) {}

    public record AppointmentPage(List<StaffAppointment> items, int page, int size, long totalItems) {}
    public record QueueView(LocalDate date, List<StaffAppointment> inConsultation, List<StaffAppointment> waiting,
                            Instant generatedAt) {}
    public record Dashboard(LocalDate date, int appointmentsTotal, int booked, int waiting, int inConsultation,
                            int completed, int cancelled, int walkIns, Instant generatedAt) {}
    public record ActivityEntry(long id, UUID appointmentId, String reference, String eventType, Status fromStatus,
                                Status toStatus, String actor, ActorRole actorRole, Instant occurredAt) {}
    public record StaffUser(UUID id, String displayName, ActorRole role) {}
    public record LoginResponse(String accessToken, String tokenType, long expiresInSeconds, StaffUser user) {}
    public record FieldError(String field, String message) {}

    // ------------------------------------------------------------ requests
    public record BookingRequest(
            @NotBlank(message = "Name is required") @Size(min = 2, max = 120, message = "Name must be 2 to 120 characters") String patientName,
            @NotBlank(message = "Contact is required") @Size(min = 3, max = 60, message = "Contact must be 3 to 60 characters") String contact,
            @NotBlank(message = "Choose a visit type") String visitTypeCode,
            @NotNull(message = "Choose a slot") UUID slotId) {}

    public record WalkInRequest(
            @NotBlank(message = "Name is required") @Size(min = 2, max = 120, message = "Name must be 2 to 120 characters") String patientName,
            @NotBlank(message = "Contact is required") @Size(min = 3, max = 60, message = "Contact must be 3 to 60 characters") String contact,
            @NotBlank(message = "Choose a visit type") String visitTypeCode,
            @NotNull(message = "Say whether to admit ahead of the queue") Boolean aheadOfQueue) {}

    public record StatusChangeRequest(
            @NotNull(message = "status is required") Status status,
            @NotNull(message = "expectedStatus is required") Status expectedStatus,
            @Size(max = 200, message = "Reason must be at most 200 characters") String reason) {}

    public record SlotChangeRequest(@NotNull(message = "Choose a slot") UUID slotId) {}

    public record CancelRequest(@Size(max = 200, message = "Reason must be at most 200 characters") String reason) {}

    public record LoginRequest(
            @NotBlank(message = "Email is required") @Size(max = 160) String email,
            @NotBlank(message = "Password is required") @Size(max = 100) String password) {
        /** Never print the password, even by accident in a log line. */
        @Override public String toString() { return "LoginRequest[email=" + email + "]"; }
    }
}
