package com.clinicflow.fake;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.DayOfWeek;
import java.util.Set;

/**
 * Active while clinicflow.ports=fake (the default until handshake H2).
 * InMemoryClinic implements every port, so ONE bean is enough: Spring injects it for SlotPort,
 * BookingPort, QueuePort, AppointmentQueryPort, DashboardPort and DemoPort alike.
 * When Deji's real implementations exist they are annotated
 * @ConditionalOnProperty(name = "clinicflow.ports", havingValue = "real") and this class steps aside.
 */
@Configuration
@ConditionalOnProperty(name = "clinicflow.ports", havingValue = "fake", matchIfMissing = true)
public class FakePortsConfig {

    @Bean
    InMemoryClinic inMemoryClinic(ApplicationEventPublisher events,
                                  @Value("${clinicflow.closed-days:SUNDAY}") Set<DayOfWeek> closedDays) {
        InMemoryClinic clinic = new InMemoryClinic(Clock.systemUTC(), closedDays, e -> events.publishEvent(e));
        clinic.resetDay(clinic.today());
        return clinic;
    }
}