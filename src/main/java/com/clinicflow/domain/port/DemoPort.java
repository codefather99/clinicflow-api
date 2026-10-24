package com.clinicflow.domain.port;
import java.time.LocalDate;

public interface DemoPort {
    /** DEMO ONLY: rebuild the given clinic day from seed data. */
    void resetDay(LocalDate date);
}
