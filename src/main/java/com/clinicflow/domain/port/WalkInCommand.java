package com.clinicflow.domain.port;
public record WalkInCommand(String patientName, String contact, String visitTypeCode, boolean aheadOfQueue, Actor actor) {}
