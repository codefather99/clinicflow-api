package com.clinicflow.web.api;

import com.clinicflow.domain.port.*;
import com.clinicflow.web.Hashes;
import com.clinicflow.web.Snapshots;
import com.clinicflow.web.dto.Dto;
import com.clinicflow.web.dto.Mappers;
import com.clinicflow.web.idempotency.IdempotencyService;
import com.clinicflow.web.security.CurrentActor;
import com.clinicflow.web.sse.SseBroadcaster;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Anonymous patient side. A patient can only ever reach their own booking, by reference. */
@RestController
@RequestMapping("/api/v1/patient/appointments")
public class PatientController {
    private static final String BOOK_SCOPE = "POST /api/v1/patient/appointments";

    private final BookingPort booking;
    private final AppointmentQueryPort queries;
    private final IdempotencyService idempotency;
    private final SseBroadcaster sse;

    public PatientController(BookingPort booking, AppointmentQueryPort queries, IdempotencyService idempotency, SseBroadcaster sse) {
        this.booking = booking;
        this.queries = queries;
        this.idempotency = idempotency;
        this.sse = sse;
    }

    /** Unsafe; idempotent via Idempotency-Key (same key + same body replays the original 201). */
    @PostMapping
    public ResponseEntity<Dto.BookingCreated> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody Dto.BookingRequest body) {
        String k = ApiSupport.requireKey(key);
        String snapshot = idempotency.run(k, BOOK_SCOPE, Hashes.sha256Hex(body.toString()), () -> {
            BookingResult r = booking.book(new BookCommand(body.patientName().trim(), body.contact().trim(),
                    body.visitTypeCode(), body.slotId(), CurrentActor.patient()));
            return Snapshots.booking(r.appointment().reference(), r.manageToken());
        });
        Snapshots.Booking s = Snapshots.parseBooking(snapshot);
        PatientView view = queries.lookup(s.reference());
        return ResponseEntity.status(201).body(new Dto.BookingCreated(Mappers.patient(view), s.manageToken()));
    }

    /** Safe, idempotent. Read-only: the patient view has no contact field and nothing about other patients. */
    @GetMapping("/{reference}")
    public Dto.PatientAppointment get(@PathVariable String reference) {
        return Mappers.patient(queries.lookup(reference));
    }

    /** Safe, idempotent. Server-Sent Events: one `appointment` event now, another on every change. */
    @GetMapping("/{reference}/events")
    public SseEmitter events(@PathVariable String reference) {
        return sse.subscribe(reference);
    }

    /** Unsafe, idempotent. Only while BOOKED; needs the manage token. */
    @PostMapping("/{reference}/cancellation")
    public Dto.PatientAppointment cancel(@PathVariable String reference,
                                         @RequestHeader(value = "X-Manage-Token", required = false) String token,
                                         @Valid @RequestBody(required = false) Dto.CancelRequest body) {
        ApiSupport.requireToken(token);
        return Mappers.patient(booking.cancelByPatient(reference, token, body == null ? null : body.reason()));
    }

    /** Unsafe, idempotent. Atomic move to another slot; only while BOOKED. */
    @PutMapping("/{reference}/slot")
    public Dto.PatientAppointment reschedule(@PathVariable String reference,
                                             @RequestHeader(value = "X-Manage-Token", required = false) String token,
                                             @Valid @RequestBody Dto.SlotChangeRequest body) {
        ApiSupport.requireToken(token);
        return Mappers.patient(booking.rescheduleByPatient(reference, token, body.slotId()));
    }
}
