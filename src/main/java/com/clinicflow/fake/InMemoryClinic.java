package com.clinicflow.fake;

import com.clinicflow.domain.port.*;

import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * HENRY'S FAKE PORTS. One in-memory clinic implementing every port so controllers, security, error mapping
 * and the interface can be built before Deji's real code lands. It follows the same rules (status sequence,
 * one booking per slot, derived queue position) but is NOT the real thing: no database, single instance,
 * everything is synchronized. Never ship this as the real implementation. Label it in the README.
 */
public class InMemoryClinic implements SlotPort, BookingPort, QueuePort, AppointmentQueryPort, DashboardPort, DemoPort {

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private static final String CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final List<VisitTypeView> VISIT_TYPES = List.of(
            new VisitTypeView(1, "GENERAL", "General consultation", 20),
            new VisitTypeView(2, "FOLLOW_UP", "Follow-up visit", 15),
            new VisitTypeView(3, "DIAGNOSTIC", "Diagnostic test", 30),
            new VisitTypeView(4, "VACCINATION", "Vaccination", 10));
    private static final Map<Status, Set<Status>> ALLOWED = Map.of(
            Status.BOOKED, Set.of(Status.WAITING, Status.CANCELLED),
            Status.WAITING, Set.of(Status.IN_CONSULTATION, Status.CANCELLED),
            Status.IN_CONSULTATION, Set.of(Status.COMPLETED),
            Status.COMPLETED, Set.of(),
            Status.CANCELLED, Set.of());

    private record SlotRec(UUID id, LocalDate date, Instant start, Instant end) {}

    private static final class Appt {
        UUID id = UUID.randomUUID();
        String reference, manageToken, name, contact;
        VisitTypeView visitType;
        UUID slotId;
        LocalDate date;
        boolean walkIn;
        Status status;
        Long rank;
        Instant checkedInAt, consultationStartedAt, completedAt, cancelledAt, createdAt, updatedAt;
        String cancelReason;
        int version;
    }

    private final Clock clock;
    private final Set<DayOfWeek> closedDays;
    private final Consumer<QueueChanged> publisher;
    private final SecureRandom random = new SecureRandom();
    private final Map<LocalDate, List<SlotRec>> slotsByDate = new HashMap<>();
    private final Map<UUID, SlotRec> slotsById = new HashMap<>();
    private final Map<UUID, Appt> appts = new LinkedHashMap<>();
    private final Map<LocalDate, long[]> ranks = new HashMap<>(); // [front, back]
    private final List<ActivityView> activity = new ArrayList<>();
    private long activitySeq = 0;

    public InMemoryClinic(Clock clock, Set<DayOfWeek> closedDays, Consumer<QueueChanged> publisher) {
        this.clock = clock;
        this.closedDays = Set.copyOf(closedDays);
        this.publisher = publisher;
    }

    public LocalDate today() { return LocalDate.now(clock.withZone(LAGOS)); }
    private Instant now() { return clock.instant(); }

    // ------------------------------------------------------------------ SlotPort
    @Override public synchronized List<VisitTypeView> visitTypes() { return VISIT_TYPES; }

    @Override public synchronized List<SlotView> slotsFor(LocalDate date) {
        return ensureSlots(date).stream()
                .filter(s -> s.start().isAfter(now()))
                .map(s -> new SlotView(s.id(), s.start(), s.end(), !taken(s.id())))
                .toList();
    }

    private List<SlotRec> ensureSlots(LocalDate date) {
        return slotsByDate.computeIfAbsent(date, d -> {
            if (closedDays.contains(d.getDayOfWeek())) return List.of();
            List<SlotRec> list = new ArrayList<>();
            for (LocalTime t = LocalTime.of(8, 0); t.isBefore(LocalTime.of(16, 0)); t = t.plusMinutes(20)) {
                Instant s = d.atTime(t).atZone(LAGOS).toInstant();
                SlotRec r = new SlotRec(UUID.randomUUID(), d, s, s.plusSeconds(20 * 60));
                list.add(r);
                slotsById.put(r.id(), r);
            }
            return list;
        });
    }

    private boolean taken(UUID slotId) {
        return appts.values().stream().anyMatch(a -> slotId.equals(a.slotId) && a.status != Status.CANCELLED);
    }

    // ------------------------------------------------------------------ BookingPort
    @Override public synchronized BookingResult book(BookCommand c) {
        VisitTypeView vt = visitType(c.visitTypeCode());
        SlotRec slot = slotsById.get(c.slotId());
        if (slot == null) throw new NotFoundException("Slot");
        if (!slot.start().isAfter(now())) throw new SlotInPastException();
        if (taken(slot.id())) throw new SlotTakenException(suggest(slot));
        Appt a = newAppt(c.patientName(), c.contact(), vt, slot, Status.BOOKED);
        a.manageToken = randomToken();
        log(a, "BOOKED", null, Status.BOOKED, c.actor());
        publish(a);
        return new BookingResult(patientView(a), a.manageToken);
    }

    @Override public synchronized PatientView cancelByPatient(String reference, String manageToken, String reason) {
        Appt a = byReference(reference);
        checkToken(a, manageToken);
        if (a.status == Status.CANCELLED) return patientView(a);
        if (a.status != Status.BOOKED)
            throw new InvalidTransitionException("Once you have arrived, please ask reception to cancel", staffView(a));
        transition(a, Status.CANCELLED, reason, Actor.patient(null));
        return patientView(a);
    }

    @Override public synchronized PatientView rescheduleByPatient(String reference, String manageToken, UUID newSlotId) {
        Appt a = byReference(reference);
        checkToken(a, manageToken);
        reschedule(a, newSlotId, Actor.patient(null));
        return patientView(a);
    }

    @Override public synchronized StaffView rescheduleByStaff(UUID id, UUID newSlotId, Actor actor) {
        Appt a = byId(id);
        reschedule(a, newSlotId, actor);
        return staffView(a);
    }

    private void reschedule(Appt a, UUID newSlotId, Actor actor) {
        if (a.status != Status.BOOKED)
            throw new InvalidTransitionException("Only a booked appointment can be rescheduled", staffView(a));
        SlotRec slot = slotsById.get(newSlotId);
        if (slot == null) throw new NotFoundException("Slot");
        if (newSlotId.equals(a.slotId)) return;
        if (!slot.start().isAfter(now())) throw new SlotInPastException();
        if (taken(slot.id())) throw new SlotTakenException(suggest(slot));
        a.slotId = slot.id();
        a.date = slot.date();
        a.version++;
        a.updatedAt = now();
        log(a, "RESCHEDULED", Status.BOOKED, Status.BOOKED, actor);
        publish(a);
    }

    // ------------------------------------------------------------------ QueuePort
    @Override public synchronized StaffView changeStatus(UUID id, Status expected, Status target, String reason, Actor actor) {
        Appt a = byId(id);
        if (a.status == target && expected == target) return staffView(a); // idempotent no-op
        if (a.status != expected) throw new StaleStateException(staffView(a));
        if (!ALLOWED.get(a.status).contains(target))
            throw new InvalidTransitionException("A " + a.status.name().toLowerCase().replace('_', ' ')
                    + " appointment cannot move to " + target.name().toLowerCase().replace('_', ' '), staffView(a));
        transition(a, target, reason, actor);
        return staffView(a);
    }

    private void transition(Appt a, Status target, String reason, Actor actor) {
        Status from = a.status;
        String event;
        switch (target) {
            case WAITING -> { a.rank = ranksFor(a.date)[1]++; a.checkedInAt = now(); event = "CHECKED_IN"; }
            case IN_CONSULTATION -> { a.consultationStartedAt = now(); event = "CONSULTATION_STARTED"; }
            case COMPLETED -> { a.completedAt = now(); event = "COMPLETED"; }
            case CANCELLED -> { a.cancelledAt = now(); a.cancelReason = reason; event = "CANCELLED"; }
            default -> throw new IllegalStateException("unreachable " + target);
        }
        a.status = target;
        a.version++;
        a.updatedAt = now();
        log(a, event, from, target, actor);
        publish(a);
    }

    @Override public synchronized StaffView admitWalkIn(WalkInCommand c) {
        LocalDate d = today();
        if (closedDays.contains(d.getDayOfWeek())) throw new ClinicClosedException();
        VisitTypeView vt = visitType(c.visitTypeCode());
        Appt a = newAppt(c.patientName(), c.contact(), vt, null, Status.WAITING);
        a.walkIn = true;
        a.date = d;
        long[] r = ranksFor(d);
        a.rank = c.aheadOfQueue() ? r[0]-- : r[1]++;
        a.checkedInAt = now();
        log(a, "WALK_IN_ADMITTED", null, Status.WAITING, c.actor());
        publish(a);
        return staffView(a);
    }

    @Override public synchronized QueueSnapshot queue(LocalDate date) {
        List<StaffView> inConsult = appts.values().stream()
                .filter(a -> a.date.equals(date) && a.status == Status.IN_CONSULTATION)
                .sorted(Comparator.comparing((Appt a) -> a.consultationStartedAt))
                .map(this::staffView).toList();
        return new QueueSnapshot(date, inConsult, waiting(date).stream().map(this::staffView).toList(), now());
    }

    // ------------------------------------------------------------------ AppointmentQueryPort
    @Override public synchronized PageResult<StaffView> search(AppointmentFilter f) {
        String q = f.q() == null ? "" : f.q().trim().toLowerCase();
        List<Appt> all = appts.values().stream()
                .filter(a -> a.date.equals(f.date()))
                .filter(a -> f.statuses().isEmpty() || f.statuses().contains(a.status))
                .filter(a -> q.isEmpty() || a.name.toLowerCase().contains(q) || a.reference.equalsIgnoreCase(q))
                .sorted(Comparator.comparing(this::sortTime))
                .toList();
        int from = Math.min(f.page() * f.size(), all.size());
        int to = Math.min(from + f.size(), all.size());
        return new PageResult<>(all.subList(from, to).stream().map(this::staffView).toList(), f.page(), f.size(), all.size());
    }

    private Instant sortTime(Appt a) { return a.slotId != null ? slotsById.get(a.slotId).start() : a.createdAt; }

    @Override public synchronized StaffView get(UUID id) { return staffView(byId(id)); }
    @Override public synchronized PatientView lookup(String reference) { return patientView(byReference(reference)); }

    @Override public synchronized List<ActivityView> activity(UUID id) {
        return activity.stream().filter(v -> v.appointmentId().equals(id)).toList();
    }

    @Override public synchronized List<ActivityView> recent(int limit) {
        List<ActivityView> copy = new ArrayList<>(activity);
        Collections.reverse(copy);
        return copy.subList(0, Math.min(limit, copy.size()));
    }

    // ------------------------------------------------------------------ DashboardPort
    @Override public synchronized DashboardView dashboard(LocalDate date) {
        Map<Status, Long> counts = appts.values().stream().filter(a -> a.date.equals(date))
                .collect(Collectors.groupingBy(a -> a.status, Collectors.counting()));
        int total = (int) appts.values().stream().filter(a -> a.date.equals(date)).count();
        int walkIns = (int) appts.values().stream().filter(a -> a.date.equals(date) && a.walkIn).count();
        return new DashboardView(date, total, n(counts, Status.BOOKED), n(counts, Status.WAITING),
                n(counts, Status.IN_CONSULTATION), n(counts, Status.COMPLETED), n(counts, Status.CANCELLED),
                walkIns, now());
    }

    private static int n(Map<Status, Long> m, Status s) { return m.getOrDefault(s, 0L).intValue(); }

    // ------------------------------------------------------------------ DemoPort (small fake seed, NOT Deji's seed)
    @Override public synchronized void resetDay(LocalDate date) {
        appts.values().removeIf(a -> a.date.equals(date));
        ranks.remove(date);
        List<SlotRec> slots = ensureSlots(date);
        if (slots.size() < 20) { publisher.accept(new QueueChanged(date, Set.of())); return; }
        Actor sys = Actor.system();
        String[][] completed = {{"Amaka Obi", "14"}, {"Tunde Bello", "18"}, {"Ngozi Eze", "16"}};
        for (int i = 0; i < completed.length; i++) {
            Appt a = seed(completed[i][0], slots.get(i), Status.COMPLETED, sys);
            int mins = Integer.parseInt(completed[i][1]);
            a.checkedInAt = now().minusSeconds(3600L);
            a.consultationStartedAt = now().minusSeconds(60L * (30 - 10 * i));
            a.completedAt = a.consultationStartedAt.plusSeconds(60L * mins);
        }
        seed("Chidi Okafor", slots.get(3), Status.IN_CONSULTATION, sys).consultationStartedAt = now().minusSeconds(300);
        seed("Fatima Sani", slots.get(4), Status.WAITING, sys);
        seed("Emeka Nwosu", slots.get(5), Status.WAITING, sys);
        seed("Bisi Adeyemi", slots.get(6), Status.WAITING, sys);
        Appt cancelled = seed("Yusuf Danjuma", slots.get(7), Status.CANCELLED, sys);
        cancelled.cancelledAt = now();
        cancelled.cancelReason = "Patient called to cancel";
        seed("Ada Ikenna", slots.get(10), Status.BOOKED, sys);
        seed("Ọlá D'Souza-Akinwumi", slots.get(11), Status.BOOKED, sys);
        seed("X".repeat(119) + "Z", slots.get(12), Status.BOOKED, sys); // 120-char name to break layouts
        seed("Amaka Obi", slots.get(13), Status.BOOKED, sys);          // same name as a completed patient
        Appt walkIn = newAppt("Walk-in Example", "0000", VISIT_TYPES.get(0), null, Status.WAITING);
        walkIn.walkIn = true; walkIn.date = date; walkIn.rank = ranksFor(date)[0]--; walkIn.checkedInAt = now();
        log(walkIn, "WALK_IN_ADMITTED", null, Status.WAITING, sys);
        publisher.accept(new QueueChanged(date, appts.values().stream().filter(a -> a.date.equals(date))
                .map(a -> a.reference).collect(Collectors.toSet())));
    }

    private Appt seed(String name, SlotRec slot, Status status, Actor actor) {
        Appt a = newAppt(name, "0000-FICTIONAL", VISIT_TYPES.get(0), slot, Status.BOOKED);
        a.manageToken = randomToken();
        log(a, "BOOKED", null, Status.BOOKED, actor);
        if (status != Status.BOOKED) {
            a.status = status;
            if (status != Status.CANCELLED) a.rank = ranksFor(a.date)[1]++;
            if (status == Status.WAITING) a.checkedInAt = now();
            if (status == Status.IN_CONSULTATION) a.checkedInAt = now().minusSeconds(900);
        }
        return a;
    }

    // ------------------------------------------------------------------ helpers
    private Appt newAppt(String name, String contact, VisitTypeView vt, SlotRec slot, Status status) {
        Appt a = new Appt();
        a.reference = uniqueReference();
        a.name = name; a.contact = contact; a.visitType = vt;
        a.slotId = slot == null ? null : slot.id();
        a.date = slot == null ? today() : slot.date();
        a.status = status;
        a.createdAt = a.updatedAt = now();
        if (status == Status.WAITING && slot != null) { a.rank = ranksFor(a.date)[1]++; }
        appts.put(a.id, a);
        return a;
    }

    private long[] ranksFor(LocalDate d) { return ranks.computeIfAbsent(d, x -> new long[]{0, 1}); }

    private List<Appt> waiting(LocalDate d) {
        return appts.values().stream().filter(a -> a.date.equals(d) && a.status == Status.WAITING)
                .sorted(Comparator.comparingLong(a -> a.rank)).toList();
    }

    private Integer position(Appt a) {
        if (a.status != Status.WAITING) return null;
        return 1 + (int) appts.values().stream()
                .filter(o -> o.date.equals(a.date) && o.status == Status.WAITING && o.rank < a.rank).count();
    }

    private EstimateView estimate(Appt a, int peopleAhead) {
        List<Long> durations = appts.values().stream()
                .filter(o -> o.date.equals(a.date) && o.status == Status.COMPLETED
                        && o.consultationStartedAt != null && o.completedAt != null)
                .map(o -> Duration.between(o.consultationStartedAt, o.completedAt).toSeconds()).toList();
        int per; String basis;
        if (durations.size() >= 3) {
            per = (int) Math.max(1, Math.round(durations.stream().mapToLong(Long::longValue).average().orElse(60) / 60.0));
            basis = "About " + per + " min per person ahead, from today's " + durations.size() + " completed consultations";
        } else {
            per = a.visitType.durationMinutes();
            basis = "About " + per + " min per person ahead, using the default for a "
                    + a.visitType.label().toLowerCase() + " (too few visits completed today to average)";
        }
        return new EstimateView(peopleAhead * per, basis);
    }

    private PatientView patientView(Appt a) {
        Integer pos = position(a);
        Integer ahead = pos == null ? null : pos - 1;
        SlotRec s = a.slotId == null ? null : slotsById.get(a.slotId);
        return new PatientView(a.reference, a.name, a.visitType,
                s == null ? null : s.start(), s == null ? null : s.end(), a.status, pos, ahead,
                ahead == null ? null : estimate(a, ahead),
                a.status == Status.BOOKED, a.status == Status.BOOKED, a.updatedAt);
    }

    private StaffView staffView(Appt a) {
        SlotRec s = a.slotId == null ? null : slotsById.get(a.slotId);
        return new StaffView(a.id, a.reference, a.name, a.contact, a.visitType,
                s == null ? null : s.start(), s == null ? null : s.end(), a.walkIn, a.status, position(a),
                a.checkedInAt, a.consultationStartedAt, a.completedAt, a.cancelledAt, a.cancelReason,
                a.version, a.createdAt, a.updatedAt);
    }

    private List<SlotView> suggest(SlotRec wanted) {
        return slotsFor(wanted.date()).stream()
                .filter(SlotView::available).filter(s -> !s.id().equals(wanted.id()))
                .sorted(Comparator.comparingLong(s -> Math.abs(Duration.between(wanted.start(), s.startsAt()).toSeconds())))
                .limit(3).toList();
    }

    private VisitTypeView visitType(String code) {
        return VISIT_TYPES.stream().filter(v -> v.code().equals(code)).findFirst()
                .orElseThrow(() -> new UnknownVisitTypeException(code));
    }

    private Appt byId(UUID id) {
        Appt a = appts.get(id);
        if (a == null) throw new NotFoundException("Appointment");
        return a;
    }

    private Appt byReference(String reference) {
        return appts.values().stream().filter(a -> a.reference.equals(reference)).findFirst()
                .orElseThrow(() -> new NotFoundException("Appointment"));
    }

    private void checkToken(Appt a, String token) {
        if (a.manageToken == null || token == null || !a.manageToken.equals(token)) throw new BadManageTokenException();
    }

    private String uniqueReference() {
        while (true) {
            StringBuilder sb = new StringBuilder("CF-");
            for (int i = 0; i < 8; i++) {
                if (i == 4) sb.append('-');
                sb.append(CROCKFORD.charAt(random.nextInt(CROCKFORD.length())));
            }
            String ref = sb.toString();
            if (appts.values().stream().noneMatch(a -> a.reference.equals(ref))) return ref;
        }
    }

    private String randomToken() {
        byte[] b = new byte[16];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private void log(Appt a, String event, Status from, Status to, Actor actor) {
        activity.add(new ActivityView(++activitySeq, a.id, a.reference, event, from, to,
                actor == null ? "system" : actor.label(), actor == null ? ActorRole.SYSTEM : actor.role(), now()));
    }

    private void publish(Appt a) {
        Set<String> refs = waiting(a.date).stream().map(w -> w.reference).collect(Collectors.toCollection(HashSet::new));
        refs.add(a.reference);
        publisher.accept(new QueueChanged(a.date, refs));
    }
}
