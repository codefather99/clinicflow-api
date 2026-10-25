package com.clinicflow.web.security;

import com.clinicflow.domain.port.ActorRole;

import java.util.UUID;

public record StaffPrincipal(UUID id, String displayName, ActorRole role) {}
