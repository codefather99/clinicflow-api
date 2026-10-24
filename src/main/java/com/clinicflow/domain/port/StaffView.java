package com.clinicflow.domain.port;
import com.clinicflow.domain.port.Status;

import java.time.Instant;
import java.util.UUID;

public record StaffView(UUID id, String reference, String patientName, String contact, VisitTypeView visitType,
                        Instant slotStart, Instant slotEnd, boolean walkIn, Status status, Integer queuePosition,
                        Instant checkedInAt, Instant consultationStartedAt, Instant completedAt, Instant cancelledAt,
                        String cancelReason, int version, Instant createdAt, Instant updatedAt) {}
