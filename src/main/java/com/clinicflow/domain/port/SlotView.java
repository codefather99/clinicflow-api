package com.clinicflow.domain.port;
import java.time.Instant;
import java.util.UUID;

public record SlotView(UUID id, Instant startsAt, Instant endsAt, boolean available) {}
