import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RaceCheck: books ONE slot with N patients at the same moment against a DEPLOYED ClinicFlow service and reports
 * how many bookings won. Exactly one must win and the rest must be refused with SLOT_TAKEN.
 *
 * Run (Java 21+, no build needed):   java RaceCheck.java https://your-service.onrender.com [patients] [yyyy-MM-dd]
 * Exit code 0 = rule held, 1 = rule broken or the service misbehaved. It cancels the winning booking afterwards
 * so you can run it again. Uses fictional names only.
 */
public class RaceCheck {
    static final Pattern FREE = Pattern.compile("\\{\"id\":\"([0-9a-f-]{36})\",\"startsAt\":\"[^\"]+\",\"endsAt\":\"[^\"]+\",\"available\":true}");
    static final HttpClient HTTP = HttpClient.newBuilder().executor(Executors.newFixedThreadPool(64)).build();

    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("usage: java RaceCheck.java <baseUrl> [patients=8] [date]"); System.exit(2); }
        String base = args[0].replaceAll("/+$", "");
        int n = args.length > 1 ? Integer.parseInt(args[1]) : 8;
        System.out.println("RaceCheck  " + ZonedDateTime.now(ZoneId.of("Africa/Lagos")) + "  target " + base + "  patients " + n);

        HttpResponse<String> meta = get(base + "/api/v1/meta");           // also wakes a sleeping free host
        if (meta.statusCode() != 200) fail("service did not answer /meta: HTTP " + meta.statusCode() + " " + meta.body());
        System.out.println("service is up: " + meta.body());

        LocalDate date = args.length > 2 ? LocalDate.parse(args[2]) : LocalDate.now(ZoneId.of("Africa/Lagos")).plusDays(2);
        if (args.length <= 2 && date.getDayOfWeek() == DayOfWeek.SUNDAY) date = date.plusDays(1);
        HttpResponse<String> slots = get(base + "/api/v1/slots?date=" + date);
        List<String> free = new ArrayList<>();
        Matcher m = FREE.matcher(slots.body());
        while (m.find()) free.add(m.group(1));
        if (free.isEmpty()) fail("no free slots on " + date + " (closed day, or all booked). Try another date as the third argument.");
        String slot = free.get(free.size() / 2);
        System.out.println("date " + date + ", " + free.size() + " free slots, racing for slot " + slot);

        for (int i = 0; i < 3; i++) { List<Future<?>> w = new ArrayList<>(); ExecutorService p = Executors.newFixedThreadPool(n);
            for (int k = 0; k < n; k++) w.add(p.submit(() -> get(base + "/api/v1/meta"))); for (Future<?> f : w) f.get(); p.shutdown(); }   // warm the connections

        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n), go = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int who = i;
            futures.add(pool.submit(() -> {
                String body = "{\"patientName\":\"Race Patient " + (who + 1) + "\",\"contact\":\"0800-RACE-" + (who + 1) + "\",\"visitTypeCode\":\"GENERAL\",\"slotId\":\"" + slot + "\"}";
                HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/api/v1/patient/appointments"))
                        .header("Content-Type", "application/json").header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("X-Request-Id", "racecheck-" + who + "-" + UUID.randomUUID())
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                ready.countDown(); go.await();
                return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            }));
        }
        ready.await(); go.countDown();
        int booked = 0, refused = 0, other = 0; String winnerRef = null, winnerToken = null; boolean offeredAlternatives = true;
        for (Future<HttpResponse<String>> f : futures) {
            HttpResponse<String> r = f.get(60, TimeUnit.SECONDS);
            if (r.statusCode() == 201) { booked++; winnerRef = find(r.body(), "\"reference\":\"(CF-[0-9A-Z]{4}-[0-9A-Z]{4})\""); winnerToken = find(r.body(), "\"manageToken\":\"([^\"]+)\""); }
            else if (r.statusCode() == 409 && r.body().contains("SLOT_TAKEN")) { refused++; if (!r.body().contains("suggestedSlots")) offeredAlternatives = false; }
            else { other++; System.out.println("  unexpected: HTTP " + r.statusCode() + " " + r.body()); }
        }
        pool.shutdown();
        System.out.println();
        System.out.println("  booked (HTTP 201) .......... " + booked);
        System.out.println("  refused (409 SLOT_TAKEN) ... " + refused + (offeredAlternatives ? "  (each offered alternative slots)" : "  (MISSING suggestedSlots)"));
        System.out.println("  anything else .............. " + other);
        if (winnerRef != null) {                       // leave the slot free again so the check can be repeated
            HttpRequest cancel = HttpRequest.newBuilder(URI.create(base + "/api/v1/patient/appointments/" + winnerRef + "/cancellation"))
                    .header("X-Manage-Token", winnerToken).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"reason\":\"race check cleanup\"}")).build();
            int c = HTTP.send(cancel, HttpResponse.BodyHandlers.ofString()).statusCode();
            System.out.println("  cleanup: cancelled winner " + winnerRef + " -> HTTP " + c);
        }
        boolean ok = booked == 1 && refused == n - 1 && other == 0 && offeredAlternatives;
        System.out.println();
        System.out.println(ok ? "RESULT: PASS - exactly one booking won; the rule held under simultaneous requests."
                              : "RESULT: FAIL - " + booked + " bookings won out of " + n + ". The rule did NOT hold.");
        System.exit(ok ? 0 : 1);
    }

    static HttpResponse<String> get(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    static String find(String text, String regex) { Matcher m = Pattern.compile(regex).matcher(text); return m.find() ? m.group(1) : null; }
    static void fail(String msg) { System.out.println("RESULT: ERROR - " + msg); System.exit(2); }
}
