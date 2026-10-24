package com.clinicflow.domain.port;

/** Who is acting. Henry builds it from the JWT / request; Deji only reads it. */
public record Actor(String label, ActorRole role, String requestId) {
    public static Actor patient(String requestId) { return new Actor("patient", ActorRole.PATIENT, requestId); }
    public static Actor system() { return new Actor("system", ActorRole.SYSTEM, null); }
}
