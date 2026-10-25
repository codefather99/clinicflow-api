package com.clinicflow.web.security;

import com.clinicflow.domain.port.ActorRole;
import com.clinicflow.web.Json;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal HS256 JWT. Only ever issues and accepts the one header we write ourselves, compares the signature in
 * constant time, and checks expiry. No refresh tokens (see design.md: a 401 means sign in again).
 */
public final class JwtService {
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final String HEADER = B64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    private static final Pattern SUB = Pattern.compile("\"sub\":\"([0-9a-fA-F-]{36})\"");
    private static final Pattern NAME = Pattern.compile("\"name\":\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern ROLE = Pattern.compile("\"role\":\"(RECEPTIONIST|ADMIN)\"");
    private static final Pattern EXP = Pattern.compile("\"exp\":(\\d{1,12})");

    private final byte[] secret;
    private final long ttlSeconds;
    private final Clock clock;

    public JwtService(String secret, long ttlSeconds, Clock clock) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalArgumentException("clinicflow.jwt.secret must be at least 32 bytes");
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlSeconds = ttlSeconds;
        this.clock = clock;
    }

    public long ttlSeconds() { return ttlSeconds; }

    public String issue(StaffPrincipal p) {
        long exp = clock.instant().getEpochSecond() + ttlSeconds;
        String payload = "{\"sub\":\"" + p.id() + "\",\"name\":" + Json.quote(p.displayName())
                + ",\"role\":\"" + p.role().name() + "\",\"exp\":" + exp + "}";
        String signingInput = HEADER + "." + B64.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + B64.encodeToString(hmac(signingInput));
    }

    public Optional<StaffPrincipal> verify(String token) {
        try {
            if (token == null) return Optional.empty();
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || !HEADER.equals(parts[0])) return Optional.empty();
            byte[] expected = hmac(parts[0] + "." + parts[1]);
            if (!MessageDigest.isEqual(expected, B64D.decode(parts[2]))) return Optional.empty();
            String payload = new String(B64D.decode(parts[1]), StandardCharsets.UTF_8);
            Matcher exp = EXP.matcher(payload), sub = SUB.matcher(payload), role = ROLE.matcher(payload), name = NAME.matcher(payload);
            if (!exp.find() || !sub.find() || !role.find() || !name.find()) return Optional.empty();
            if (Long.parseLong(exp.group(1)) <= clock.instant().getEpochSecond()) return Optional.empty();
            return Optional.of(new StaffPrincipal(UUID.fromString(sub.group(1)), Json.unescape(name.group(1)),
                    ActorRole.valueOf(role.group(1))));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
