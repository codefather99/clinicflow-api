package com.clinicflow.domain.port;
import java.time.LocalDate;
import java.util.Set;

/** Published by the domain AFTER COMMIT whenever queue-visible state changes. Henry's SSE broadcaster listens. */
public record QueueChanged(LocalDate clinicDate, Set<String> references) {}
