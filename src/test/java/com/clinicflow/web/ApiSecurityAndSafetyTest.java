package com.clinicflow.web;

import com.clinicflow.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Talks to the running service over real HTTP (no MockMvc), exactly as a hostile or buggy client would.
 * Each test must FAIL if the rule it checks is removed. Runs against the fake ports for now; the same checks run
 * against Deji's real ports after handshake H2.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiSecurityAndSafetyTest {

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private static final Pattern FREE_SLOT = Pattern.compile(
            "\\{\"id\":\"([0-9a-f-]{36})\",\"startsAt\":\"[^\"]+\",\"endsAt\":\"[^\"]+\",\"available\":true}");
    private static final AtomicInteger NEXT_SLOT = new AtomicInteger();

    // ------------------------------------------------------------------ helpers
    private HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) b.header("Content-Type", "application/json");
        for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String login(String email, String password) throws Exception {
        HttpResponse<String> r = send("POST", "/api/v1/auth/login",
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
        assertEquals(200, r.statusCode(), r.body());
        return find(r.body(), "\"accessToken\":\"([^\"]+)\"");
    }

    private static String find(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        assertTrue(m.find(), "expected " + regex + " in " + text);
        return m.group(1);
    }

    /** A distinct free slot per call, so tests never fight over the same slot. */
    private String freeSlot() throws Exception {
        LocalDate d = LocalDate.now(ZoneId.of("Africa/Lagos")).plusDays(2);
        if (d.getDayOfWeek().getValue() == 7) d = d.plusDays(1); // clinic closed Sundays
        HttpResponse<String> r = send("GET", "/api/v1/slots?date=" + d, null);
        assertEquals(200, r.statusCode());
        List<String> ids = new ArrayList<>();
        Matcher m = FREE_SLOT.matcher(r.body());
        while (m.find()) ids.add(m.group(1));
        assertFalse(ids.isEmpty(), "no free slots on " + d);
        return ids.get(NEXT_SLOT.getAndIncrement() % ids.size());
    }

    private static String bookingBody(String slotId, String name) {
        return "{\"patientName\":\"" + name + "\",\"contact\":\"0800-TEST-1\",\"visitTypeCode\":\"GENERAL\",\"slotId\":\"" + slotId + "\"}";
    }

    // ------------------------------------------------------------------ access (the mandatory wrong-role test)
    @Test
    void anonymous_cannot_change_queue_state_or_read_staff_data() throws Exception {
        HttpResponse<String> put = send("PUT", "/api/v1/appointments/" + UUID.randomUUID() + "/status",
                "{\"status\":\"WAITING\",\"expectedStatus\":\"BOOKED\"}");
        assertEquals(401, put.statusCode());
        assertTrue(put.body().contains("\"code\":\"UNAUTHENTICATED\""), put.body());
        assertNotNull(put.headers().firstValue("X-Request-Id").orElse(null), "errors still carry the request id");
        assertEquals(401, send("GET", "/api/v1/queue?date=2026-10-26", null).statusCode());
        assertEquals(401, send("GET", "/api/v1/appointments?date=2026-10-26", null).statusCode());
    }

    @Test
    void receptionist_cannot_reset_the_demo_day_but_admin_can() throws Exception {
        String reception = login("reception@clinicflow.demo", "Reception-Demo-1");
        HttpResponse<String> denied = send("POST", "/api/v1/demo/reset", null, "Authorization", "Bearer " + reception);
        assertEquals(403, denied.statusCode());
        assertTrue(denied.body().contains("\"code\":\"FORBIDDEN\""), denied.body());

        String admin = login("admin@clinicflow.demo", "Admin-Demo-1");
        assertEquals(204, send("POST", "/api/v1/demo/reset", null, "Authorization", "Bearer " + admin).statusCode());
    }

    @Test
    void forged_or_garbage_tokens_are_refused_and_wrong_password_is_401() throws Exception {
        assertEquals(401, send("GET", "/api/v1/queue?date=2026-10-26", null, "Authorization", "Bearer not.a.token").statusCode());
        HttpResponse<String> bad = send("POST", "/api/v1/auth/login", "{\"email\":\"reception@clinicflow.demo\",\"password\":\"nope\"}");
        assertEquals(401, bad.statusCode());
    }

    // ------------------------------------------------------------------ patient privacy
    @Test
    void patient_view_never_exposes_contact_or_other_patients() throws Exception {
        String slot = freeSlot();
        HttpResponse<String> created = send("POST", "/api/v1/patient/appointments", bookingBody(slot, "Privacy Test"),
                "Idempotency-Key", UUID.randomUUID().toString());
        assertEquals(201, created.statusCode(), created.body());
        String reference = find(created.body(), "\"reference\":\"(CF-[0-9A-Z]{4}-[0-9A-Z]{4})\"");
        HttpResponse<String> view = send("GET", "/api/v1/patient/appointments/" + reference, null);
        assertEquals(200, view.statusCode());
        assertFalse(view.body().contains("contact"), view.body());
        assertFalse(view.body().contains("0800-TEST"), view.body());
        assertFalse(view.body().contains("Amaka Obi"), "another patient's name leaked: " + view.body());
    }

    // ------------------------------------------------------------------ idempotency
    @Test
    void same_key_and_body_twice_returns_the_original_booking() throws Exception {
        String slot = freeSlot();
        String key = UUID.randomUUID().toString();
        HttpResponse<String> a = send("POST", "/api/v1/patient/appointments", bookingBody(slot, "Twice Test"), "Idempotency-Key", key);
        HttpResponse<String> b = send("POST", "/api/v1/patient/appointments", bookingBody(slot, "Twice Test"), "Idempotency-Key", key);
        assertEquals(201, a.statusCode(), a.body());
        assertEquals(201, b.statusCode(), b.body());
        assertEquals(find(a.body(), "\"reference\":\"([^\"]+)\""), find(b.body(), "\"reference\":\"([^\"]+)\""));
        assertEquals(find(a.body(), "\"manageToken\":\"([^\"]+)\""), find(b.body(), "\"manageToken\":\"([^\"]+)\""));
    }

    @Test
    void same_key_with_a_different_body_is_refused() throws Exception {
        String key = UUID.randomUUID().toString();
        assertEquals(201, send("POST", "/api/v1/patient/appointments", bookingBody(freeSlot(), "First Body"), "Idempotency-Key", key).statusCode());
        HttpResponse<String> r = send("POST", "/api/v1/patient/appointments", bookingBody(freeSlot(), "Other Body"), "Idempotency-Key", key);
        assertEquals(422, r.statusCode());
        assertTrue(r.body().contains("IDEMPOTENCY_KEY_MISMATCH"), r.body());
    }

    @Test
    void missing_idempotency_key_is_a_validation_error() throws Exception {
        HttpResponse<String> r = send("POST", "/api/v1/patient/appointments", bookingBody(freeSlot(), "No Key"));
        assertEquals(422, r.statusCode());
        assertTrue(r.body().contains("Idempotency-Key"), r.body());
    }

    @Test
    void five_simultaneous_submissions_with_one_key_make_one_booking() throws Exception {
        String slot = freeSlot();
        String key = UUID.randomUUID().toString();
        int n = 5;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++)
            fs.add(pool.submit(() -> { go.await(); return send("POST", "/api/v1/patient/appointments", bookingBody(slot, "Race Same Key"), "Idempotency-Key", key); }));
        go.countDown();
        Set<String> references = new HashSet<>();
        for (Future<HttpResponse<String>> f : fs) {
            HttpResponse<String> r = f.get(30, TimeUnit.SECONDS);
            assertEquals(201, r.statusCode(), r.body());
            references.add(find(r.body(), "\"reference\":\"([^\"]+)\""));
        }
        pool.shutdown();
        assertEquals(1, references.size(), "one key must produce exactly one booking: " + references);
    }

    // ------------------------------------------------------------------ the rule, over HTTP
    @Test
    void six_different_patients_racing_for_one_slot_produce_exactly_one_booking() throws Exception {
        String slot = freeSlot();
        int n = 6;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int who = i;
            fs.add(pool.submit(() -> { go.await(); return send("POST", "/api/v1/patient/appointments",
                    bookingBody(slot, "Racer " + who), "Idempotency-Key", UUID.randomUUID().toString()); }));
        }
        go.countDown();
        int created = 0, refused = 0;
        for (Future<HttpResponse<String>> f : fs) {
            HttpResponse<String> r = f.get(30, TimeUnit.SECONDS);
            if (r.statusCode() == 201) created++;
            else if (r.statusCode() == 409 && r.body().contains("\"code\":\"SLOT_TAKEN\"")) {
                refused++;
                assertTrue(r.body().contains("suggestedSlots"), "refusal must offer alternatives: " + r.body());
            } else fail("unexpected response " + r.statusCode() + ": " + r.body());
        }
        pool.shutdown();
        assertEquals(1, created, "exactly one booking may win");
        assertEquals(n - 1, refused);
    }
}
