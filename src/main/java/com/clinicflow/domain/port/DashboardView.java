package com.clinicflow.domain.port;
import java.time.Instant;
import java.time.LocalDate;

public record DashboardView(LocalDate date, int total, int booked, int waiting, int inConsultation,
                            int completed, int cancelled, int walkIns, Instant generatedAt) {}
