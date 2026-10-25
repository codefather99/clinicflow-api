package com.clinicflow.domain.port;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record QueueSnapshot(LocalDate date, List<StaffView> inConsultation, List<StaffView> waiting, Instant generatedAt) {}
