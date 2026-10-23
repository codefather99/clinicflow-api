package com.clinicflow.domain.port;
import com.clinicflow.domain.port.ActorRole;
import com.clinicflow.domain.port.Status;

import java.time.Instant;
import java.util.UUID;

public record ActivityView(long id, UUID appointmentId, String reference, String eventType,
                           Status from, Status to, String actor, ActorRole actorRole, Instant occurredAt) {}
