package com.clinicflow.domain.port;
import java.util.UUID;

public record BookCommand(String patientName, String contact, String visitTypeCode, UUID slotId, Actor actor) {}
