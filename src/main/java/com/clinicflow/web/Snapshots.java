package com.clinicflow.web;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the idempotency table remembers about a finished write. Deliberately tiny: just enough to rebuild the
 * response (the current state is re-read on replay). Values come from our own strict character sets, so a
 * regex is safe here and we avoid depending on a particular JSON library.
 */
public final class Snapshots {
    private Snapshots() {}

    public record Booking(String reference, String manageToken) {}

    private static final Pattern BOOKING =
            Pattern.compile(
                    "\\{\\s*\"reference\"\\s*:\\s*\"(CF-[0-9A-Z]{4}-[0-9A-Z]{4})\"\\s*,\\s*\"manageToken\"\\s*:\\s*\"([A-Za-z0-9_-]+)\"\\s*}"
            );
    private static final Pattern WALK_IN = Pattern.compile("\\{\"id\":\"([0-9a-fA-F-]{36})\"}");

    public static String booking(String reference, String manageToken) {
        return "{\"reference\":\"" + reference + "\",\"manageToken\":\"" + manageToken + "\"}";
    }

    public static Booking parseBooking(String json) {
        Matcher m = BOOKING.matcher(json);
        if (!m.matches()) throw new IllegalStateException("Unreadable booking snapshot");
        return new Booking(m.group(1), m.group(2));
    }

    public static String walkIn(UUID id) { return "{\"id\":\"" + id + "\"}"; }

    public static UUID parseWalkIn(String json) {
        Matcher m = WALK_IN.matcher(json);
        if (!m.matches()) throw new IllegalStateException("Unreadable walk-in snapshot");
        return UUID.fromString(m.group(1));
    }
}
