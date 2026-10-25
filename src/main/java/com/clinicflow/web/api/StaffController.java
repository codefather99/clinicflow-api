package com.clinicflow.web.api;

import com.clinicflow.domain.port.*;
import com.clinicflow.web.Hashes;
import com.clinicflow.web.Snapshots;
import com.clinicflow.web.dto.Dto;
import com.clinicflow.web.dto.Mappers;
import com.clinicflow.web.error.ApiValidationException;
import com.clinicflow.web.idempotency.IdempotencyService;
import com.clinicflow.web.security.CurrentActor;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Reception and admin. Access is enforced in SecurityConfig, not here, so it cannot be bypassed by the UI. */
@RestController
@RequestMapping("/api/v1")
public class StaffController {
    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private static final String WALK_IN_SCOPE = "POST /api/v1/walk-ins";

    private final AppointmentQueryPort queries;
    private final QueuePort queue;
    private final BookingPort booking;
    private final DashboardPort dashboard;
    private final DemoPort demo;
    private final IdempotencyService idempotency;

    public StaffController(AppointmentQueryPort queries, QueuePort queue, BookingPort booking,
                           DashboardPort dashboard, DemoPort demo, IdempotencyService idempotency) {
        this.queries = queries;
        this.queue = queue;
        this.booking = booking;
        this.dashboard = dashboard;
        this.demo = demo;
        this.idempotency = idempotency;
    }

    /** Safe, idempotent. Filters combine; omit one to clear it. */
    @GetMapping("/appointments")
    public Dto.AppointmentPage list(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) List<Status> status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0) throw new ApiValidationException("page", "page must be 0 or more");
        if (size < 1 || size > 100) throw new ApiValidationException("size", "size must be between 1 and 100");
        return Mappers.page(queries.search(new AppointmentFilter(date,
                status == null ? null : new HashSet<>(status), q, page, size)));
    }

    @GetMapping("/appointments/{id}")
    public Dto.StaffAppointment get(@PathVariable UUID id) { return Mappers.staff(queries.get(id)); }

    /** Unsafe, idempotent. expectedStatus guards against two receptionists clicking at once (STALE_STATE). */
    @PutMapping("/appointments/{id}/status")
    public Dto.StaffAppointment setStatus(@PathVariable UUID id, @Valid @RequestBody Dto.StatusChangeRequest body) {
        return Mappers.staff(queue.changeStatus(id, body.expectedStatus(), body.status(), body.reason(), CurrentActor.staff()));
    }

    @PutMapping("/appointments/{id}/slot")
    public Dto.StaffAppointment reschedule(@PathVariable UUID id, @Valid @RequestBody Dto.SlotChangeRequest body) {
        return Mappers.staff(booking.rescheduleByStaff(id, body.slotId(), CurrentActor.staff()));
    }

    @GetMapping("/appointments/{id}/activity")
    public List<Dto.ActivityEntry> activity(@PathVariable UUID id) {
        return queries.activity(id).stream().map(Mappers::activity).toList();
    }

    /** Unsafe; idempotent via Idempotency-Key. */
    @PostMapping("/walk-ins")
    public ResponseEntity<Dto.StaffAppointment> admitWalkIn(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody Dto.WalkInRequest body) {
        String k = ApiSupport.requireKey(key);
        String snapshot = idempotency.run(k, WALK_IN_SCOPE, Hashes.sha256Hex(body.toString()), () -> {
            StaffView v = queue.admitWalkIn(new WalkInCommand(body.patientName().trim(), body.contact().trim(),
                    body.visitTypeCode(), body.aheadOfQueue(), CurrentActor.staff()));
            return Snapshots.walkIn(v.id());
        });
        return ResponseEntity.status(201).body(Mappers.staff(queries.get(Snapshots.parseWalkIn(snapshot))));
    }

    @GetMapping("/queue")
    public Dto.QueueView queue(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Mappers.queue(queue.queue(date));
    }

    @GetMapping("/dashboard")
    public Dto.Dashboard dashboard(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return Mappers.dashboard(dashboard.dashboard(date));
    }

    @GetMapping("/activity")
    public List<Dto.ActivityEntry> recent(@RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > 100) throw new ApiValidationException("limit", "limit must be between 1 and 100");
        return queries.recent(limit).stream().map(Mappers::activity).toList();
    }

    /** DEMO ONLY. ADMIN only (SecurityConfig). Rebuilds today's clinic day from seed data. */
    @PostMapping("/demo/reset")
    public ResponseEntity<Void> resetDemoDay() {
        demo.resetDay(LocalDate.now(LAGOS));
        return ResponseEntity.noContent().build();
    }
}
