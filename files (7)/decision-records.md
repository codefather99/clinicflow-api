# Decision records (ClinicFlow)

Format required by the brief: **context, decision, alternatives rejected, cost accepted, and the observation that would make us reverse it.** These five are *proposed* as of 22 Oct 2026. Each moves to *accepted* once we have the evidence noted under it. Edit them honestly if reality disagrees.

---

## ADR-001: Protect "a slot cannot be booked twice" with a partial unique index

**Status:** proposed · **Protects a business rule** · **Owners:** Deji (schema), Henry (error mapping)

**Context.** Two patients can pick the same slot in the same instant. The brief calls a double booking a defect that must be impossible, including when the service is called directly.

**Decision.** `CREATE UNIQUE INDEX ux_appointment_active_slot ON appointment(slot_id) WHERE slot_id IS NOT NULL AND status <> 'CANCELLED'`. The service simply inserts; a unique violation on that index becomes `409 SLOT_TAKEN` with up to three suggested slots.

**Rejected.**
- *Select then insert in Java:* two requests both see "free" and both insert. It's the exact bug the brief names.
- *Serializable isolation / advisory locks:* work, but need retry loops and can be bypassed by any code path that forgets them. An index cannot be forgotten.
- *Redis or in-memory lock:* adds a second system, doesn't survive a restart, and the database can still be written to directly.

**Cost.** The error arrives as a database exception we must translate; the partial `WHERE` means cancelled rows stay in the table (history is kept, which we want). Rescheduling must be one atomic `UPDATE`.

**Evidence so far.** 8 simultaneous inserts: 1 success / 7 refused with the index; 8 successes without it (22 Oct, `db/verify_schema_concurrency.py`). Still to do: the same test against the deployed service.

**Reverse if** a second bookable resource appears (several clinicians in parallel). The uniqueness would need to be `(practitioner_id, slot)`, or capacity per slot would need a counter with a constraint.

---

## ADR-002: Store a queue *rank*; derive the position at read time

**Status:** proposed · **Owner:** Deji

**Context.** The brief wants cancellations in the middle of the queue to renumber everyone behind, and two simultaneous check-ins never to share a position.

**Decision.** At check-in a patient gets a `queue_rank` from two counters on `clinic_day` (back counts up for normal arrivals, front counts down for a walk-in admitted ahead). Position = 1 + count of WAITING patients that day with a lower rank. Queue-changing operations take `SELECT … FOR UPDATE` on the day's row; a unique index on `(clinic_date, queue_rank)` is the backstop.

**Rejected.**
- *Stored contiguous positions (1,2,3…) updated after every cancel:* a renumbering UPDATE is easy to get wrong under concurrency and produces half-renumbered queues if it fails midway.
- *Timestamp ordering with no counter:* two check-ins in the same millisecond tie.
- *Array/linked list in a JSON column:* hides the structure from the database and from the index.

**Cost.** Position needs a count query per lookup (indexed by the partial `ix_appointment_waiting`; cheap at clinic scale). The per-day lock serialises queue changes, which is fine for one clinic. Ranks can be zero or negative, which looks odd in raw SQL.

**Reverse if** `EXPLAIN ANALYZE` shows the position count examining more than a few hundred rows per lookup, or the load test saturates because of lock waits on `clinic_day` rather than CPU or the database tier.

---

## ADR-003: Live position via Server-Sent Events, with a polling fallback

**Status:** proposed · **Owner:** Henry (service), Ehimare (client)

**Context.** The patient must see their position change without reloading. Free hosts and free time limit what we can run.

**Decision.** `GET /patient/appointments/{reference}/events` streams the patient's own state with `text/event-stream`. The domain publishes `QueueChanged` after commit; the SSE broadcaster re-reads each connected reference. The client falls back to polling every 10 seconds and shows "Reconnecting…" while disconnected.

**Rejected.**
- *WebSockets:* two-way is not needed; more moving parts on a free host.
- *Polling only:* works, but every waiting patient hits the database every few seconds, and the screen is stale between polls.
- *Third-party push service:* breaks the zero-cost, own-service rule.

**Cost.** In-memory registry means one instance only, and a restart drops all streams. Proxies may close idle connections (hence the keep-alive). A hand-written fallback in the client.

**Reverse if** the host closes SSE connections so often that the polling fallback is the main path in practice, in which case drop SSE and poll every 5 seconds, or move to a shared message bus if we ever need more than one instance.

---

## ADR-004: Flyway forward migrations plus hand-written down scripts

**Status:** proposed · **Owners:** Deji (scripts), Henry (pipeline)

**Context.** The brief requires versioned migrations that run automatically in the pipeline **and can be reversed**, and expects the schema to change after day six. Two engineers write migrations.

**Decision.** Flyway for forward migrations. Number blocks: Deji `V1–V99`, Henry `V100–V199`, with `out-of-order=true`. Each migration has a matching `db/down/Vn__down.sql`, run by a `workflow_dispatch` rollback workflow. A CI test applies up → down → up on a throwaway database.

**Rejected.**
- *Flyway undo migrations:* a paid feature.
- *Liquibase with rollback blocks:* free and built in, but a new tool to learn this week.
- *"Just drop and recreate":* not a migration strategy once real data exists.

**Cost.** Every migration has two files to keep in step, and Flyway's history table is not rewound automatically by our down script (the rollback workflow must also remove the history row). Out-of-order mode means an ordering mistake is possible, caught by the CI round trip.

**Evidence so far.** V1 and V100 up, down, up round trip passed on 22 Oct.

**Reverse if** more than a couple of down scripts break in CI, or a destructive change (dropping a column with data) needs real data-preserving rollback, in which case switch to Liquibase.

---

## ADR-005: Patients are anonymous; self-service changes need a manage token

**Status:** proposed · **Owners:** Henry (auth), Deji (token hash + rule)

**Context.** Patients should be able to book and see their own position without an account. The brief says the patient view is read-only and only staff change queue state, yet also requires cancellation and rescheduling.

**Decision.** No patient accounts. Booking returns a reference (public identifier) and a one-time manage token (stored only as a SHA-256 hash). The reference alone gives a read-only view of that one booking. The token allows cancel or reschedule **only while the status is BOOKED**. Once checked in, only staff act.

**Rejected.**
- *Patient accounts and login:* more surface, passwords and recovery for a demo with fictional people.
- *Reference alone for changes:* anyone who learns or guesses a reference could cancel it.
- *Staff-only changes:* simplest, but patients couldn't cancel without phoning, which defeats the product.

**Cost.** A lost token means the patient asks reception to cancel. The reference is a capability, so lookups are rate limited and the reference has about 40 bits of randomness. We cannot prove the booker's identity (no real identity verification is in scope).

**Reverse if** the client confirms patients must be able to cancel after check-in, or testing shows references being guessed (rate-limit logs), in which case add a second factor such as the last digits of the contact value.
