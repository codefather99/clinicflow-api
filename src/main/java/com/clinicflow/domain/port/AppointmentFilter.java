package com.clinicflow.domain.port;
import com.clinicflow.domain.port.Status;

import java.time.LocalDate;
import java.util.Set;

public record AppointmentFilter(LocalDate date, Set<Status> statuses, String q, int page, int size) {
    public AppointmentFilter {
        if (date == null) throw new IllegalArgumentException("date is required");
        statuses = statuses == null ? Set.of() : Set.copyOf(statuses);
        if (page < 0) throw new IllegalArgumentException("page must be >= 0");
        if (size < 1 || size > 100) throw new IllegalArgumentException("size must be 1..100");
    }
}
