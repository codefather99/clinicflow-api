package com.clinicflow.web.security;

import com.clinicflow.domain.port.ActorRole;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private final StaffPrincipal grace = new StaffPrincipal(UUID.randomUUID(), "Grace \"Gee\" Ọlá \\ Okoro", ActorRole.RECEPTIONIST);

    private JwtService at(String instant) {
        return new JwtService(SECRET, 3600, Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    @Test
    void round_trip_keeps_identity_and_role() {
        JwtService jwt = at("2026-10-26T08:00:00Z");
        assertEquals(grace, jwt.verify(jwt.issue(grace)).orElseThrow());
    }

    @Test
    void changing_the_role_without_resigning_is_rejected() {
        JwtService jwt = at("2026-10-26T08:00:00Z");
        String[] p = jwt.issue(grace).split("\\.");
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(p[1])).replace("RECEPTIONIST", "ADMIN").getBytes());
        assertTrue(jwt.verify(p[0] + "." + forged + "." + p[2]).isEmpty());
    }

    @Test
    void token_from_another_key_and_alg_none_are_rejected() {
        JwtService jwt = at("2026-10-26T08:00:00Z");
        String token = jwt.issue(grace);
        assertTrue(new JwtService("ffffffffffffffffffffffffffffffff", 3600,
                Clock.fixed(Instant.parse("2026-10-26T08:00:00Z"), ZoneOffset.UTC)).verify(token).isEmpty());
        String none = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes());
        assertTrue(jwt.verify(none + "." + token.split("\\.")[1] + ".").isEmpty());
    }

    @Test
    void token_expires_exactly_at_its_expiry() {
        String token = at("2026-10-26T08:00:00Z").issue(grace);
        assertTrue(at("2026-10-26T08:59:59Z").verify(token).isPresent());
        assertTrue(at("2026-10-26T09:00:00Z").verify(token).isEmpty());
    }

    @Test
    void garbage_never_throws() {
        JwtService jwt = at("2026-10-26T08:00:00Z");
        assertTrue(jwt.verify("garbage").isEmpty());
        assertTrue(jwt.verify("a.b.c").isEmpty());
        assertTrue(jwt.verify(null).isEmpty());
    }

    @Test
    void short_secret_is_refused() {
        assertThrows(IllegalArgumentException.class, () -> new JwtService("short", 60, Clock.systemUTC()));
    }
}
