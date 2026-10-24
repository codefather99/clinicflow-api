package com.clinicflow.domain.port;
import java.time.LocalDate;

public interface DashboardPort {
    /** Computed from the records at call time. Nothing is stored. */
    DashboardView dashboard(LocalDate date);
}
