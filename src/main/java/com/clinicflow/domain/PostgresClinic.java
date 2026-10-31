package com.clinicflow.domain;

import com.clinicflow.domain.Sql;
import com.clinicflow.domain.port.*;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * THE REAL PORTS (Deji's side). Same behaviour as com.clinicflow.fake.InMemoryClinic, but the rules live in
 * Postgres. Active when clinicflow.ports=real. All SQL is in Sql.java.
 *
 * The rules, and what protects them:
 *   a slot cannot be booked twice  -> plain INSERT; unique index ux_appointment_active_slot refuses the second
 *   no duplicate queue position    -> day row lock + atomic rank counters; unique index ux_appointment_queue_rank
 *   cancel closes the gap          -> position is derived (1 + waiting with lower rank), never stored
 *   stale clicks                   -> expectedStatus checked under the appointment row lock
 *
 * Transactions: every public method joins the caller's transaction (Henry's IdempotencyService opens one), so a
 * booking and its idempotency key commit or roll back together. After a unique violation Postgres aborts the
 * transaction, so the "nearest free slots" lookup runs in a NEW transaction.
 */
@Service
@ConditionalOnProperty(name = "clinicflow.ports", havingValue = "real")
@Transactional
public class PostgresClinic implements SlotPort, BookingPort, QueuePort, AppointmentQueryPort, DashboardPort, DemoPort {

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private static final String CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final String SLOT_INDEX = "ux_appointment_active_slot";
    private static final Map<Status, Set<Status>> ALLOWED = Map.of(
            Status.BOOKED, Set.of(Status.WAITING, Status.CANCELLED),
            Status.WAITING, Set.of(Status.IN_CONSULTATION, Status.CANCELLED),
            Status.IN_CONSULTATION, Set.of(Status.COMPLETED),
            Status.COMPLETED, Set.of(),
            Status.CANCELLED, Set.of());

    private static final String[] FIRST = {"Amaka", "Tunde", "Ngozi", "Chidi", "Fatima", "Emeka", "Bisi", "Yusuf", "Ada", "Kunle",
            "Zainab", "Obinna", "Funmi", "Ibrahim", "Chioma", "Segun", "Halima", "Uche", "Tolu", "Mariam"};
    private static final String[] LAST = {"Obi", "Bello", "Eze", "Okafor", "Sani", "Nwosu", "Adeyemi", "Danjuma", "Ikenna", "Balogun",
            "Musa", "Okoro", "Adebayo", "Lawal", "Nnamdi", "Ogunleye", "Abubakar", "Eboh", "Akpan", "Okonkwo"};

    private record SlotRec(UUID id, LocalDate date, Instant start) {}

    private record Row(UUID id, String reference, String name, String contact, VisitTypeView visitType, UUID slotId,
                       Instant slotStart, Instant slotEnd, boolean walkIn, Status status, Long rank, Integer position,
                       Instant checkedInAt, Instant consultationStartedAt, Instant completedAt, Instant cancelledAt,
                       String cancelReason, int version, Instant createdAt, Instant updatedAt, LocalDate date) {}

    private static final RowMapper<Row> ROW = (rs, i) -> new Row(
            uuid(rs, "id"), rs.getString("reference"), rs.getString("patient_display_name"), rs.getString("contact"),
            new VisitTypeView(rs.getInt("vt_id"), rs.getString("vt_code"), rs.getString("vt_label"), rs.getInt("vt_minutes")),
            uuid(rs, "slot_id"), instant(rs, "starts_at"), instant(rs, "ends_at"), rs.getBoolean("is_walk_in"),
            Status.valueOf(rs.getString("status")), rs.getObject("queue_rank", Long.class), rs.getObject("queue_pos", Integer.class),
            instant(rs, "checked_in_at"), instant(rs, "consultation_started_at"), instant(rs, "completed_at"),
            instant(rs, "cancelled_at"), rs.getString("cancel_reason"), rs.getInt("version"),
            instant(rs, "created_at"), instant(rs, "updated_at"), rs.getObject("clinic_date", LocalDate.class));

    private static final RowMapper<ActivityView> ACTIVITY = (rs, i) -> new ActivityView(
            rs.getLong("id"), uuid(rs, "appointment_id"), rs.getString("reference"), rs.getString("event_type"),
            status(rs.getString("from_status")), status(rs.getString("to_status")), rs.getString("actor"),
            ActorRole.valueOf(rs.getString("actor_role")), instant(rs, "occurred_at"));

    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager tm;
    private final ApplicationEventPublisher events;
    private final Set<DayOfWeek> closedDays;
    private final SecureRandom random = new SecureRandom();

    public PostgresClinic(JdbcTemplate jdbc, PlatformTransactionManager tm, ApplicationEventPublisher events,
                          @Value("${clinicflow.closed-days:SUNDAY}") Set<DayOfWeek> closedDays) {
        this.jdbc = jdbc;
        this.tm = tm;
        this.events = events;
        this.closedDays = Set.copyOf(closedDays);
    }

    // ================================================================== calendar (writes happen at startup and reset, never on a GET)
    /** Creates clinic days and 15-minute slots for [from, from + days). Idempotent. Closed days get no slots. */
    public void ensureCalendar(LocalDate from, int days) {
        for (int i = 0; i < days; i++) {
            LocalDate d = from.plusDays(i);
            boolean open = !closedDays.contains(d.getDayOfWeek());
            jdbc.update(Sql.ENSURE_DAY, d, open);
            if (open) jdbc.update(Sql.ENSURE_SLOTS, d, d, d);
        }
    }

    /** Called at startup so a freshly deployed service always has a believable day to demonstrate. */
    public void seedIfEmpty(LocalDate date) {
        Long n = jdbc.queryForObject(Sql.COUNT_DAY, Long.class, date);
        if (n == null || n == 0) resetDay(date);
    }

    // ================================================================== SlotPort
    @Override @Transactional(readOnly = true)
    public List<VisitTypeView> visitTypes() {
        return jdbc.query(Sql.VISIT_TYPES, (rs, i) ->
                new VisitTypeView(rs.getInt("id"), rs.getString("code"), rs.getString("label"), rs.getInt("duration_minutes")));
    }

    @Override @Transactional(readOnly = true)
    public List<SlotView> slotsFor(LocalDate date) {
        return jdbc.query(Sql.SLOTS_FOR, (rs, i) ->
                new SlotView(uuid(rs, "id"), instant(rs, "starts_at"), instant(rs, "ends_at"), rs.getBoolean("available")), date);
    }

    // ================================================================== BookingPort
    @Override
    public BookingResult book(BookCommand c) {
        VisitTypeView vt = visitType(c.visitTypeCode());
        SlotRec slot = slot(c.slotId());
        if (!slot.start().isAfter(Instant.now())) throw new SlotInPastException();
        String token = randomToken();
        String reference = newReference();
        UUID id;
        try {
            // No "is it free?" check. Just insert; the unique index decides.
            id = jdbc.queryForObject(Sql.INSERT_BOOKING, UUID.class, reference, sha256Hex(token),
                    c.patientName(), c.contact(), vt.id(), slot.date(), slot.id());
        } catch (DuplicateKeyException e) {
            if (constraintMessage(e).contains(SLOT_INDEX)) throw new SlotTakenException(suggestionsInNewTransaction(slot));
            throw e;
        }
        activity(id, "BOOKED", null, Status.BOOKED, c.actor());
        publishChange(slot.date(), reference);
        return new BookingResult(lookup(reference), token);
    }

    @Override
    public PatientView cancelByPatient(String reference, String manageToken, String reason) {
        UUID id = authorize(reference, manageToken);
        Row r = row(id);
        if (r.status() == Status.CANCELLED) return patientView(r);
        if (r.status() != Status.BOOKED)
            throw new InvalidTransitionException("Once you have arrived, please ask reception to cancel", staffView(r));
        changeInternal(id, Status.BOOKED, Status.CANCELLED, reason, Actor.patient(requestId()));
        return lookup(reference);
    }

    @Override
    public PatientView rescheduleByPatient(String reference, String manageToken, UUID newSlotId) {
        UUID id = authorize(reference, manageToken);
        reschedule(id, newSlotId, Actor.patient(requestId()));
        return lookup(reference);
    }

    @Override
    public StaffView rescheduleByStaff(UUID appointmentId, UUID newSlotId, Actor actor) {
        reschedule(appointmentId, newSlotId, actor);
        return staffView(row(appointmentId));
    }

    private void reschedule(UUID id, UUID newSlotId, Actor actor) {
        row(id);                                   // NotFound if missing
        lockAppointment(id);
        Row r = row(id);
        if (r.status() != Status.BOOKED)
            throw new InvalidTransitionException("Only a booked appointment can be rescheduled", staffView(r));
        SlotRec slot = slot(newSlotId);
        if (newSlotId.equals(r.slotId())) return;
        if (!slot.start().isAfter(Instant.now())) throw new SlotInPastException();
        try {
            jdbc.update(Sql.RESCHEDULE, slot.id(), slot.date(), id);   // one UPDATE: old slot untouched if this fails
        } catch (DuplicateKeyException e) {
            throw new SlotTakenException(suggestionsInNewTransaction(slot));
        }
        activity(id, "RESCHEDULED", Status.BOOKED, Status.BOOKED, actor);
        publishChange(slot.date(), r.reference());
    }

    // ================================================================== QueuePort
    @Override
    public StaffView changeStatus(UUID appointmentId, Status expected, Status target, String reason, Actor actor) {
        return changeInternal(appointmentId, expected, target, reason, actor);
    }

    private StaffView changeInternal(UUID id, Status expected, Status target, String reason, Actor actor) {
        Row first = row(id);                       // NotFound if missing; also gives us the clinic date
        lockDay(first.date());                     // serialise every queue change for the day
        lockAppointment(id);
        Row r = row(id);                           // re-read under the lock
        if (r.status() == target && expected == target) return staffView(r);          // idempotent no-op
        if (r.status() != expected) throw new StaleStateException(staffView(r));
        if (!ALLOWED.get(r.status()).contains(target))
            throw new InvalidTransitionException("A " + words(r.status()) + " appointment cannot move to " + words(target), staffView(r));
        String event = switch (target) {
            case WAITING -> {
                Long rank = jdbc.queryForObject(Sql.NEXT_BACK_RANK, Long.class, r.date());
                jdbc.update(Sql.TO_WAITING, rank, id);
                yield "CHECKED_IN";
            }
            case IN_CONSULTATION -> { jdbc.update(Sql.TO_CONSULTATION, id); yield "CONSULTATION_STARTED"; }
            case COMPLETED -> { jdbc.update(Sql.TO_COMPLETED, id); yield "COMPLETED"; }
            case CANCELLED -> { jdbc.update(Sql.TO_CANCELLED, reason, id); yield "CANCELLED"; }
            default -> throw new IllegalStateException("unreachable " + target);
        };
        activity(id, event, r.status(), target, actor);
        publishChange(r.date(), r.reference());
        return staffView(row(id));
    }

    @Override
    public StaffView admitWalkIn(WalkInCommand c) {
        LocalDate today = LocalDate.now(LAGOS);
        List<Boolean> open = jdbc.query(Sql.DAY_OPEN, (rs, i) -> rs.getBoolean(1), today);
        if (open.isEmpty() || !open.get(0)) throw new ClinicClosedException();
        VisitTypeView vt = visitType(c.visitTypeCode());
        lockDay(today);
        Long rank = jdbc.queryForObject(c.aheadOfQueue() ? Sql.NEXT_FRONT_RANK : Sql.NEXT_BACK_RANK, Long.class, today);
        String reference = newReference();
        UUID id = jdbc.queryForObject(Sql.INSERT_WALK_IN, UUID.class, reference, c.patientName(), c.contact(), vt.id(), today, rank);
        activity(id, "WALK_IN_ADMITTED", null, Status.WAITING, c.actor());
        publishChange(today, reference);
        return staffView(row(id));
    }

    @Override @Transactional(readOnly = true)
    public QueueSnapshot queue(LocalDate date) {
        List<StaffView> consulting = jdbc.query(Sql.BASE + Sql.WHERE_DAY_STATUS_ORDER_CONSULT, ROW, date)
                .stream().map(PostgresClinic::staffView).toList();
        List<StaffView> waiting = jdbc.query(Sql.BASE + Sql.WHERE_DAY_WAITING_ORDER, ROW, date)
                .stream().map(PostgresClinic::staffView).toList();
        return new QueueSnapshot(date, consulting, waiting, Instant.now());
    }

    // ================================================================== AppointmentQueryPort
    @Override @Transactional(readOnly = true)
    public PageResult<StaffView> search(AppointmentFilter f) {
        StringBuilder where = new StringBuilder(Sql.SEARCH_WHERE);
        List<Object> args = new ArrayList<>();
        args.add(f.date());
        if (!f.statuses().isEmpty()) {
            where.append(" AND a.status IN (").append(String.join(",", Collections.nCopies(f.statuses().size(), "?"))).append(")");
            f.statuses().forEach(s -> args.add(s.name()));
        }
        if (f.q() != null && !f.q().isBlank()) {
            String q = f.q().trim();
            where.append(Sql.SEARCH_AND_Q);
            args.add("%" + escapeLike(q.toLowerCase(Locale.ROOT)) + "%");
            args.add(q);
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM appointment a" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(f.size());
        pageArgs.add(f.page() * f.size());
        List<StaffView> items = jdbc.query(Sql.BASE + where + Sql.SEARCH_ORDER_PAGE, ROW, pageArgs.toArray())
                .stream().map(PostgresClinic::staffView).toList();
        return new PageResult<>(items, f.page(), f.size(), total == null ? 0 : total);
    }

    @Override @Transactional(readOnly = true)
    public StaffView get(UUID appointmentId) { return staffView(row(appointmentId)); }

    @Override @Transactional(readOnly = true)
    public PatientView lookup(String reference) {
        List<Row> rows = jdbc.query(Sql.BASE + Sql.WHERE_REFERENCE, ROW, reference);
        if (rows.isEmpty()) throw new NotFoundException("Appointment");
        return patientView(rows.get(0));
    }

    @Override @Transactional(readOnly = true)
    public List<ActivityView> activity(UUID appointmentId) { return jdbc.query(Sql.ACTIVITY_FOR, ACTIVITY, appointmentId); }

    @Override @Transactional(readOnly = true)
    public List<ActivityView> recent(int limit) { return jdbc.query(Sql.ACTIVITY_RECENT, ACTIVITY, limit); }

    // ================================================================== DashboardPort
    @Override @Transactional(readOnly = true)
    public DashboardView dashboard(LocalDate date) {
        Map<String, Integer> counts = jdbc.query(Sql.DASH_COUNTS, (rs, i) -> Map.entry(rs.getString("status"), rs.getInt("c")), date)
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        Long walkIns = jdbc.queryForObject(Sql.DASH_WALK_INS, Long.class, date);
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        return new DashboardView(date, total, counts.getOrDefault("BOOKED", 0), counts.getOrDefault("WAITING", 0),
                counts.getOrDefault("IN_CONSULTATION", 0), counts.getOrDefault("COMPLETED", 0),
                counts.getOrDefault("CANCELLED", 0), walkIns == null ? 0 : walkIns.intValue(), Instant.now());
    }

    // ================================================================== DemoPort: believable demo day (40 appointments)
    @Override
    public void resetDay(LocalDate date) {
        jdbc.update(Sql.DELETE_DAY_ACTIVITY, date);
        jdbc.update(Sql.DELETE_DAY_APPOINTMENTS, date);
        ensureCalendar(date, 1);
        jdbc.update(Sql.RESET_RANKS, date);
        List<UUID> slots = jdbc.query(Sql.DAY_SLOT_IDS, (rs, i) -> uuid(rs, 1), date);
        if (slots.size() < 38) {                                   // closed day: nothing to seed
            events.publishEvent(new QueueChanged(date, Set.of()));
            return;
        }
        int[] n = {0};                 // creation order, drives names
        long[] back = {1};             // next normal queue rank
        // 12 completed consultations of 8 to 20 minutes
        for (int i = 0; i < 12; i++) {
            int dur = 8 + (i % 5) * 3;
            seedRow(date, n[0]++, slots.get(i), false, Status.COMPLETED, back[0]++, 200 - i * 12, 195 - i * 12, 195 - i * 12 - dur, null, null);
        }
        seedRow(date, n[0]++, slots.get(19), false, Status.CANCELLED, back[0]++, 45, null, null, 20, "Left before being seen");   // cancelled mid-queue
        seedRow(date, n[0]++, slots.get(12), false, Status.IN_CONSULTATION, back[0]++, 60, 5, null, null, null);
        for (int k = 0; k < 6; k++)
            seedRow(date, n[0]++, slots.get(13 + k), false, Status.WAITING, back[0]++, 50 - k * 5, null, null, null, null);
        seedRow(date, n[0]++, null, true, Status.WAITING, back[0]++, 12, null, null, null, null);   // walk-in at the back
        seedRow(date, n[0]++, null, true, Status.WAITING, 0L, 8, null, null, null, null);           // walk-in admitted AHEAD of the queue
        seedRow(date, n[0]++, slots.get(20), false, Status.CANCELLED, null, null, null, null, 30, "Patient called to cancel");
        seedRow(date, n[0]++, slots.get(21), false, Status.CANCELLED, null, null, null, null, 60, "Rebooked for the same time by someone else");
        for (int k = 0; k < 16; k++)                                                                 // 16 booked; slot 21 was cancelled then rebooked
            seedRow(date, n[0]++, slots.get(21 + k), false, Status.BOOKED, null, null, null, null, null, null);
        jdbc.update(Sql.SET_RANKS, -1L, back[0], date);
        List<String> refs = jdbc.query(Sql.WAITING_REFS, (rs, i) -> rs.getString(1), date);
        events.publishEvent(new QueueChanged(date, new HashSet<>(refs)));
    }

    private void seedRow(LocalDate date, int index, UUID slot, boolean walkIn, Status status, Long rank,
                         Integer checkedInAgo, Integer startedAgo, Integer completedAgo, Integer cancelledAgo, String why) {
        UUID id = jdbc.queryForObject(Sql.INSERT_SEED, UUID.class, newReference(), slot == null ? null : sha256Hex(randomToken()),
                seedName(index), 1 + index % 4, date, slot, walkIn, status.name(), rank,
                checkedInAgo, checkedInAgo, startedAgo, startedAgo, completedAgo, completedAgo, cancelledAgo, cancelledAgo, why);
        Actor seed = new Actor("Seed data", ActorRole.SYSTEM, null);
        if (!walkIn) activity(id, "BOOKED", null, Status.BOOKED, seed);
        if (walkIn) { activity(id, "WALK_IN_ADMITTED", null, Status.WAITING, seed); return; }
        switch (status) {
            case COMPLETED -> {
                activity(id, "CHECKED_IN", Status.BOOKED, Status.WAITING, seed);
                activity(id, "CONSULTATION_STARTED", Status.WAITING, Status.IN_CONSULTATION, seed);
                activity(id, "COMPLETED", Status.IN_CONSULTATION, Status.COMPLETED, seed);
            }
            case IN_CONSULTATION -> {
                activity(id, "CHECKED_IN", Status.BOOKED, Status.WAITING, seed);
                activity(id, "CONSULTATION_STARTED", Status.WAITING, Status.IN_CONSULTATION, seed);
            }
            case WAITING -> activity(id, "CHECKED_IN", Status.BOOKED, Status.WAITING, seed);
            case CANCELLED -> {
                if (rank != null) {
                    activity(id, "CHECKED_IN", Status.BOOKED, Status.WAITING, seed);
                    activity(id, "CANCELLED", Status.WAITING, Status.CANCELLED, seed);
                } else {
                    activity(id, "CANCELLED", Status.BOOKED, Status.CANCELLED, seed);
                }
            }
            default -> { }
        }
    }

    /** Fictional names only. Three are awkward on purpose: a 120-character name, apostrophe and accents, a repeat of the first name. */
    private static String seedName(int index) {
        if (index == 36) return "Chukwuemeka-Oluwaseun ".repeat(6).substring(0, 119) + "Z";
        if (index == 37) return "Ọlá D'Souza-Akinwumi";
        if (index == 38) return seedName(0);
        return FIRST[index % 20] + " " + LAST[(index * 7 + index / 20) % 20];
    }

    // ================================================================== helpers
    private Row row(UUID id) {
        List<Row> rows = jdbc.query(Sql.BASE + Sql.WHERE_ID, ROW, id);
        if (rows.isEmpty()) throw new NotFoundException("Appointment");
        return rows.get(0);
    }

    private SlotRec slot(UUID id) {
        List<SlotRec> rows = jdbc.query(Sql.SLOT_BY_ID, (rs, i) ->
                new SlotRec(uuid(rs, "id"), rs.getObject("clinic_date", LocalDate.class), instant(rs, "starts_at")), id);
        if (rows.isEmpty()) throw new NotFoundException("Slot");
        return rows.get(0);
    }

    private VisitTypeView visitType(String code) {
        return visitTypes().stream().filter(v -> v.code().equals(code)).findFirst()
                .orElseThrow(() -> new UnknownVisitTypeException(code));
    }

    private void lockDay(LocalDate date) {
        if (jdbc.query(Sql.LOCK_DAY, (rs, i) -> 1, date).isEmpty()) throw new ClinicClosedException();
    }

    private void lockAppointment(UUID id) { jdbc.query(Sql.LOCK_APPOINTMENT, (rs, i) -> rs.getString(1), id); }

    private UUID authorize(String reference, String token) {
        List<String[]> rows = jdbc.query(Sql.TOKEN_BY_REFERENCE,
                (rs, i) -> new String[]{rs.getString("id"), rs.getString("manage_token_hash")}, reference);
        if (rows.isEmpty()) throw new NotFoundException("Appointment");
        String stored = rows.get(0)[1];
        if (stored == null || token == null
                || !MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8), sha256Hex(token).getBytes(StandardCharsets.UTF_8)))
            throw new BadManageTokenException();
        return UUID.fromString(rows.get(0)[0]);
    }

    private List<SlotView> suggestionsInNewTransaction(SlotRec slot) {
        TransactionTemplate t = new TransactionTemplate(tm);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        t.setReadOnly(true);
        List<SlotView> result = t.execute(status -> jdbc.query(Sql.SUGGESTIONS, (rs, i) ->
                        new SlotView(uuid(rs, "id"), instant(rs, "starts_at"), instant(rs, "ends_at"), true),
                slot.date(), slot.id(), OffsetDateTime.ofInstant(slot.start(), ZoneOffset.UTC)));
        return result == null ? List.of() : result;
    }

    private void activity(UUID id, String event, Status from, Status to, Actor actor) {
        jdbc.update(Sql.ACTIVITY_INSERT, id, event, from == null ? null : from.name(), to.name(),
                actor.label(), actor.role().name(), actor.requestId());
    }

    /** The domain announces; Henry's SSE broadcaster listens. Fires after the surrounding transaction commits. */
    private void publishChange(LocalDate date, String reference) {
        Set<String> refs = new HashSet<>(jdbc.query(Sql.WAITING_REFS, (rs, i) -> rs.getString(1), date));
        refs.add(reference);
        events.publishEvent(new QueueChanged(date, refs));
    }

    private PatientView patientView(Row r) {
        Integer pos = r.position();
        Integer ahead = pos == null ? null : pos - 1;
        return new PatientView(r.reference(), r.name(), r.visitType(), r.slotStart(), r.slotEnd(), r.status(), pos, ahead,
                ahead == null ? null : estimate(r, ahead), r.status() == Status.BOOKED, r.status() == Status.BOOKED, r.updatedAt());
    }

    private static StaffView staffView(Row r) {
        return new StaffView(r.id(), r.reference(), r.name(), r.contact(), r.visitType(), r.slotStart(), r.slotEnd(),
                r.walkIn(), r.status(), r.position(), r.checkedInAt(), r.consultationStartedAt(), r.completedAt(),
                r.cancelledAt(), r.cancelReason(), r.version(), r.createdAt(), r.updatedAt());
    }

    /** Always an estimate, always with its basis. Averages today's completed consultations once there are at least 3. */
    private EstimateView estimate(Row r, int peopleAhead) {
        double[] stats = jdbc.query(Sql.COMPLETED_STATS, (rs, i) -> new double[]{rs.getLong("n"), rs.getDouble("avg_seconds")}, r.date()).get(0);
        int per;
        String basis;
        if (stats[0] >= 3) {
            per = (int) Math.max(1, Math.round(stats[1] / 60.0));
            basis = "About " + per + " min per person ahead, from today's " + (long) stats[0] + " completed consultations";
        } else {
            per = r.visitType().durationMinutes();
            basis = "About " + per + " min per person ahead, using the default for a "
                    + r.visitType().label().toLowerCase(Locale.ROOT) + " (too few visits completed today to average)";
        }
        return new EstimateView(peopleAhead * per, basis);
    }

    private String newReference() {
        StringBuilder sb = new StringBuilder("CF-");
        for (int i = 0; i < 8; i++) {
            if (i == 4) sb.append('-');
            sb.append(CROCKFORD.charAt(random.nextInt(CROCKFORD.length())));
        }
        return sb.toString();
    }

    private String randomToken() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String words(Status s) { return s.name().toLowerCase(Locale.ROOT).replace('_', ' '); }
    private static String constraintMessage(DuplicateKeyException e) { return String.valueOf(e.getMostSpecificCause().getMessage()); }
    private static String requestId() { return MDC.get("requestId"); }

    private static UUID uuid(ResultSet rs, String col) throws SQLException { return rs.getObject(col, UUID.class); }
    private static UUID uuid(ResultSet rs, int idx) throws SQLException { return rs.getObject(idx, UUID.class); }
    private static Status status(String s) { return s == null ? null : Status.valueOf(s); }
    private static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime o = rs.getObject(col, OffsetDateTime.class);
        return o == null ? null : o.toInstant();
    }
}
