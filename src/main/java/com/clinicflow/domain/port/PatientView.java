package com.clinicflow.domain.port;
import com.clinicflow.domain.port.Status;

import java.time.Instant;

/** What a patient may see. Deliberately has no contact field and nothing about other patients. */
public record PatientView(String reference, String patientName, VisitTypeView visitType,
                          Instant slotStart, Instant slotEnd, Status status,
                          Integer queuePosition, Integer peopleAhead, EstimateView estimate,
                          boolean canCancel, boolean canReschedule, Instant updatedAt) {}
