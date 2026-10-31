package com.clinicflow.domain;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * At startup: make sure the next 15 days exist (slots are bookable 14 days ahead) and that today has a believable
 * demo day if the database is empty. Writes happen here, never on a GET.
 * Known limit: the 15-day window only rolls forward when the service restarts or the day is reset.
 */
@Component
@ConditionalOnProperty(name = "clinicflow.ports", havingValue = "real")
public class CalendarInitializer implements ApplicationRunner {
    private final PostgresClinic clinic;

    public CalendarInitializer(PostgresClinic clinic) { this.clinic = clinic; }

    @Override
    public void run(ApplicationArguments args) {
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Lagos"));
        clinic.ensureCalendar(today, 15);
        clinic.seedIfEmpty(today);
    }
}
