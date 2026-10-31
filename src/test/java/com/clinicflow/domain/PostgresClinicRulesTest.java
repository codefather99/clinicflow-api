package com.clinicflow.domain;

import com.clinicflow.TestcontainersConfiguration;
import com.clinicflow.domain.port.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rule tests for the REAL ports against a REAL Postgres (Testcontainers). Each one must fail when the rule it
 * checks is removed. OWNER: Deji. Written by Henry as a first draft: extend it until every business rule
 * in the brief has a test here.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "clinicflow.ports=real")
class PostgresClinicRulesTest {

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private static final Actor RECEPTION = new Actor("Test reception", ActorRole.RECEPTIONIST, "test");
    private static final AtomicInteger NEXT = new AtomicInteger();

    @Autowired SlotPort slots;
    @Autowired BookingPort booking;
    @Autowired QueuePort queue;
    @Autowired AppointmentQueryPort queries;
    @Autowired DashboardPort dashboard;
    @Autowired DemoPort demo;

    // ------------------------------------------------------------------ helpers
    /** A future open day within the 15-day calendar window (skips closed days). */
    private LocalDate openDate() {
        LocalDate d = LocalDate.now(LAGOS).plusDays(2);
        while (slots.slotsFor(d).isEmpty()) d = d.plusDays(1);
        return d;
    }

    private UUID freeSlot() {
        List<SlotView> free = slots.slotsFor(openDate()).stream().filter(SlotView::available).toList();
        assertFalse(free.isEmpty(), "no free slots left in the test calendar");
        return free.get(NEXT.getAndIncrement() % free.size()).id();
    }

    private BookingResult book(String name) {
        return booking.book(new BookCommand(name, "0800-TEST", "GENERAL", freeSlot(), Actor.patient("test")));
    }

    private UUID idOf(String reference) {
        return queries.search(new AppointmentFilter(openDate(), null, reference, 0, 5)).items().get(0).id();
    }

    private StaffView checkIn(UUID id) { return queue.changeStatus(id, Status.BOOKED, Status.WAITING, null, RECEPTION); }

    private <T> List<Future<T>> inParallel(int n, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) fs.add(pool.submit(() -> { go.await(); return task.call(); }));
        go.countDown();
        pool.shutdown();
        return fs;
    }

    // ------------------------------------------------------------------ THE rule
    @Test
    void eight_simultaneous_bookings_of_one_slot_create_exactly_one() throws Exception {
        UUID slot = freeSlot();
        AtomicInteger who = new AtomicInteger();
        List<Future<String>> results = inParallel(8, () -> {
            try {
                booking.book(new BookCommand("Racer " + who.incrementAndGet(), "0800-TEST", "GENERAL", slot, Actor.patient("test")));
                return "booked";
            } catch (SlotTakenException e) {
                assertEquals(3, e.suggestions().size(), "refusal must offer 3 alternatives");
                assertTrue(e.suggestions().stream().noneMatch(s -> s.id().equals(slot)));
                return "refused";
            }
        });
        int booked = 0, refused = 0;
        for (Future<String> f : results) { if (f.get(30, TimeUnit.SECONDS).equals("booked")) booked++; else refused++; }
        assertEquals(1, booked, "exactly one booking may win");
        assertEquals(7, refused);
    }

    @Test
    void a_cancelled_booking_frees_its_slot() {
        UUID slot = freeSlot();
        BookingResult first = booking.book(new BookCommand("First", "0800-TEST", "GENERAL", slot, Actor.patient("test")));
        booking.cancelByPatient(first.appointment().reference(), first.manageToken(), "changed my mind");
        BookingResult second = booking.book(new BookCommand("Second", "0800-TEST", "GENERAL", slot, Actor.patient("test")));
        assertEquals(Status.BOOKED, second.appointment().status());
    }

    // ------------------------------------------------------------------ queue
    @Test
    void simultaneous_check_ins_never_share_a_position() throws Exception {
        List<String> refs = new ArrayList<>();
        for (int i = 0; i < 6; i++) refs.add(book("Arrival " + i).appointment().reference());
        List<UUID> ids = refs.stream().map(this::idOf).toList();
        Iterator<UUID> it = ids.iterator();
        Object lock = new Object();
        List<Future<StaffView>> fs = inParallel(6, () -> { UUID id; synchronized (lock) { id = it.next(); } return checkIn(id); });
        for (Future<StaffView> f : fs) f.get(30, TimeUnit.SECONDS);
        Set<Integer> positions = new HashSet<>();
        for (String r : refs) positions.add(queries.lookup(r).queuePosition());
        assertEquals(6, positions.size(), "every checked-in patient must have a different position: " + positions);
    }

    @Test
    void cancelling_mid_queue_closes_the_gap_behind_it() {
        String a = book("A").appointment().reference(), b = book("B").appointment().reference(), c = book("C").appointment().reference();
        UUID ia = idOf(a), ib = idOf(b), ic = idOf(c);
        checkIn(ia); checkIn(ib); checkIn(ic);
        int before = queries.lookup(c).queuePosition();
        queue.changeStatus(ib, Status.WAITING, Status.CANCELLED, "left", RECEPTION);
        assertEquals(before - 1, queries.lookup(c).queuePosition(), "the patient behind must move up by one");
        assertNull(queries.lookup(b).queuePosition(), "a cancelled patient has no position");
    }

    @Test
    void walk_in_admitted_ahead_goes_to_the_front() {
        StaffView walkIn;
        try {
            walkIn = queue.admitWalkIn(new WalkInCommand("Walker", "0800-TEST", "GENERAL", true, RECEPTION));
        } catch (ClinicClosedException e) {
            Assumptions.abort("clinic is closed today");
            return;
        }
        assertEquals(1, walkIn.queuePosition());
        assertTrue(walkIn.walkIn());
    }

    // ------------------------------------------------------------------ status sequence and stale clicks
    @Test
    void invalid_and_stale_status_changes_are_refused() {
        UUID id = idOf(book("Sequence").appointment().reference());
        checkIn(id);
        assertThrows(InvalidTransitionException.class,
                () -> queue.changeStatus(id, Status.WAITING, Status.COMPLETED, null, RECEPTION));
        StaleStateException stale = assertThrows(StaleStateException.class,
                () -> queue.changeStatus(id, Status.BOOKED, Status.WAITING, null, RECEPTION));
        assertEquals(Status.WAITING, stale.current().status(), "the refusal carries the true current state");
        assertEquals(Status.WAITING, queue.changeStatus(id, Status.WAITING, Status.WAITING, null, RECEPTION).status(),
                "same status twice is a no-op");
    }

    @Test
    void patient_cannot_cancel_after_check_in_and_a_wrong_token_is_refused() {
        BookingResult b = book("Token");
        String ref = b.appointment().reference();
        assertThrows(BadManageTokenException.class, () -> booking.cancelByPatient(ref, "wrong-token", "x"));
        checkIn(idOf(ref));
        assertThrows(InvalidTransitionException.class, () -> booking.cancelByPatient(ref, b.manageToken(), "x"));
    }

    // ------------------------------------------------------------------ labelled estimate, seed
    @Test
    void waiting_patients_get_an_estimate_with_its_basis() {
        String ref = book("Estimate").appointment().reference();
        checkIn(idOf(ref));
        PatientView v = queries.lookup(ref);
        assertNotNull(v.estimate());
        assertFalse(v.estimate().basis().isBlank(), "an estimate must always say how it was calculated");
    }

    @Test
    void the_demo_day_has_40_appointments_covering_every_status() {
        LocalDate today = LocalDate.now(LAGOS);
        if (slots.slotsFor(today).isEmpty() && dashboard.dashboard(today).total() == 0)
            Assumptions.abort("clinic is closed today, so no demo day is seeded");
        demo.resetDay(today);
        DashboardView d = dashboard.dashboard(today);
        assertEquals(40, d.total());
        assertEquals(2, d.walkIns());
        assertEquals(12, d.completed());
        assertEquals(1, d.inConsultation());
        assertEquals(8, d.waiting());
        assertEquals(3, d.cancelled());
        assertEquals(16, d.booked());
        assertTrue(queue.queue(today).waiting().get(0).walkIn(), "the walk-in admitted ahead is first in the queue");
    }
}
