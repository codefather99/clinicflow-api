# ClinicFlow: who builds what (Team 2)

**Team:** Henry Emefo (API, auth, pipeline) · Ayodeji "Deji" Oni (Data and rules) · Ehimare J. Okosun (Interface) · Supervisor: Laja
**Today:** Thu 22 Oct 2026 · **MVP checkpoint:** Mon 26 Oct 2026

> The brief says to record the split on the first day. Paste this file in the repo as `docs/team-split.md` and commit it as the first commit. Both of you sign it by adding your name and the date at the bottom.

---

## 1. The one idea that keeps you out of each other's way

The backend is **one Spring Boot app with two sides and one seam between them**.

```
 Henry's side            THE SEAM               Deji's side
 (web package)       (domain.port package)      (domain package)
 HTTP, JSON, auth,   Java interfaces +          Rules, SQL, transactions,
 errors, idempotency  records + exceptions      concurrency, seed data
        │                    ▲                          │
        └──── calls ─────────┴──────── implements ──────┘
```

- Henry never touches a repository, a table, or a SQL statement in `domain`.
- Deji never touches a controller, a security rule, or a DTO in `web`.
- They only meet at `domain.port` (interfaces such as `BookingPort`). Changing a port needs **both** of you to approve the PR.
- Henry builds against **fake ports** (in-memory) from hour one, so he is never waiting for Deji. Deji tests his real ports directly with Testcontainers, so he is never waiting for Henry. On the swap day you flip one Spring profile and the real ones take over (this is the "easy to recouple" part).
- An ArchUnit test fails the build if `web` imports anything from `domain` except `domain.port`. The seam is enforced by the pipeline, not by good intentions.

---

## 2. Ownership

Mapped to the brief's two halves: **Henry = Engineer B**, **Deji = Engineer A**.

| Area | Henry (API and access, pipeline) | Deji (Data and rules) |
|---|---|---|
| **Code** | `web` package: controllers, request/response DTOs, bean validation, `GlobalExceptionHandler` (domain exception → `Problem`), `RequestIdFilter`, idempotency wrapper, SSE endpoint and broadcaster, JWT security, manage-token check, rate limiting | `domain` package: use-case implementations of every port, status-transition rules, booking, check-in, queue ranking, walk-ins, estimate calculation, dashboard queries, reference generator |
| **Database** | Migrations `V100–V199` (`staff_user`, `idempotency_key`) and their down scripts | Migrations `V1–V99` (everything else) and their down scripts, indexes, constraints, `EXPLAIN` notes |
| **Data** | Demo staff seeder (reception + admin accounts) | Demo clinic-day seeder (≥38 appointments, every status), `POST /demo/reset` logic |
| **Contract** | Owns `openapi/openapi.yaml` with Ehimare. Prism mock script. Contract tests (Schemathesis) in the pipeline | Reviews any contract change that touches a rule |
| **Pipeline** | GitHub Actions (build, test, lint, typecheck, OpenAPI lint, ArchUnit), deploy to Render + Neon, migration job, rollback workflow, post-deploy concurrency job, "test bites" job | — |
| **Tests** | Authorisation test (wrong role fails when check removed), patient-privacy test, idempotency test, SSE test, contract tests | Unit + integration tests for **every business rule** (against real Postgres), the concurrency tests, seed validity test |
| **Observability** | Request-id filter (puts `requestId` in MDC, echoes it in the response) | `logback-spring.xml` JSON logging, domain log lines |
| **Load test** | Wires the k6 job into CI and runs it against the deployed URL | Writes the k6 script, sets the target, finds the saturation point |
| **Docs** | README: setup, demo credentials, deployment, free-tier terms you verified (with date), pipeline | Data model doc, ERD, five-queries/index table, seed-data doc |

**Shared (both approve every change):** `domain.port` · `docs/decision-records.md` · AI log (each keeps their own section) · the friction note.

**Rule of thumb for edge cases:** if it needs a SQL statement or a rule, it's Deji's. If it needs an HTTP status, a header, a token or a YAML file, it's Henry's.

### Things that look shared but aren't (decided now so nobody argues later)

| Question | Answer |
|---|---|
| Who writes `appointment_activity` rows? | Deji. They are part of the rule (every status change appends one). The `actor` label and `requestId` are passed *in* by Henry via the `com.clinicflow.domain.port.Actor` and command records. |
| Who owns the concurrency test? | Deji writes the test logic (it is a rule test). Henry wires it into the pipeline and points it at the deployed URL. |
| Who generates the JWT and the roles? | Henry. Deji's code only receives an `com.clinicflow.domain.port.Actor(label, role)` and never sees a token. |
| Who decides the HTTP status for a rule failure? | Henry, via the exception table in `design.md` §4. Deji just throws the right exception. |
| Who calls `queue changed` to the SSE stream? | Deji publishes a `com.clinicflow.domain.port.QueueChanged` Spring event after commit. Henry subscribes. Neither calls the other directly. |

---

## 3. Working agreements

**Repo:** one backend repo `clinicflow-api` (Ehimare has `clinicflow-web`). Trunk-based: `main` is always deployable, short branches, PR for everything, squash merge.

**Branch names:** `henry/<thing>` and `deji/<thing>`. You never push to the other person's branch.

**`CODEOWNERS`** (GitHub then requests the right reviewer automatically):

```
/src/main/java/com/clinicflow/web/            @henry-emefo
/src/main/java/com/clinicflow/domain/port/    @henry-emefo @ayodeji-oni
/src/main/java/com/clinicflow/domain/         @ayodeji-oni
/src/main/resources/db/migration/V1*          @ayodeji-oni
/db/down/                                     @ayodeji-oni @henry-emefo
/.github/                                     @henry-emefo
/openapi/                                     @henry-emefo @ehimare-okosun
/docs/decision-records.md                     @henry-emefo @ayodeji-oni
```

(Replace handles with your real GitHub usernames. Flyway reads `src/main/resources/db/migration`; copy the two SQL files there.)

**Contract change protocol** (the brief wants two engineers named on every change):
1. Whoever needs the change edits `openapi.yaml` in a PR.
2. Henry and Ehimare approve. Deji is added if a rule is involved.
3. Add one dated line to `docs/contract-decisions.md`: *what changed, why, who agreed*.
4. Henry updates the controller and Ehimare the screen. A path that is not in the YAML is not built and not called.

**Daily 10-minute sync** (voice, 9:00 and 20:00 while the clock is tight): what I merged, what I'm blocked on, what I'm touching next. Post the same three lines in the team chat so Ehimare can follow.

**Borrowing work:** if you finish early, take a task from the other person's list *only after asking*, and add a line to `docs/work-log.md` ("Henry took X from Deji, 24 Oct"). The brief asks for this record.

**AI log:** keep it as you go in `docs/ai-log.md`. Every time AI output turns out wrong, write it down immediately (what it claimed, how you checked, what was true, what you did). You need at least one, and it is much easier to log it now than to remember it on day 13.

---

## 4. The handshakes (the only moments you must meet)

| # | When | What | Who |
|---|---|---|---|
| H0 | **Thu 22, tonight** | Contract (`openapi.yaml`) sent to Ehimare. Skeleton repo exists. `domain.port` interfaces merged. | Henry |
| H1 | Thu 22, tonight | `V1` migration merged and applied to a local Postgres. | Deji |
| H2 | **Fri 23, midday** | Swap: `clinicflow.ports=real` replaces `fake`. Booking + check-in work through real code. | both |
| H3 | **Fri 23, evening** | Monday journey (book → check in) run once against the **deployed** service, divergences written down. | all three |
| H4 | **Sat 24, afternoon** | Concurrency test runs against the **deployed** URL and passes. | Deji writes, Henry wires |
| H5 | **Sun 25, morning** | Protection removed on a throwaway branch, same test fails, evidence saved (CI run link + screenshot). Protection restored. | Henry runs, Deji confirms |
| H6 | Sun 25, evening | Full rehearsal from a clean browser. | all three |

If H2 slips past Friday night: Deji keeps working, Henry keeps the fake ports on and **Monday's demo can still run on real Postgres by swapping only the three ports that matter** (`BookingPort`, `QueuePort`, `SlotPort`). Don't swap all at once if you are short of time.

---

## 5. Day-by-day (Thursday to Monday, then the rest)

> You are about one day behind the brief's suggested path (it assumes a start on Wed 21). The plan below absorbs that by doing the contract and the schema in parallel tonight. **Tell Laja on Friday, not Monday, if you cannot finish both MVPs.** The brief says to protect the main product and drop the side project first.

### Thursday 22 (tonight)

| Henry | Deji |
|---|---|
| Create repos, push this folder. Spring Boot skeleton (Java 21, Maven, Web, Validation, Security, Flyway, JDBC/JPA, Actuator, springdoc not needed: contract-first). | Copy `V1__clinicflow_core.sql` into `db/migration`; apply to local Postgres (Docker). Read it end to end; challenge anything you disagree with *tonight*. |
| Send `openapi.yaml` + the "what Ehimare needs" list (§7) to Ehimare. Start Prism mock. | Write the **client questions** answers/assumptions into `intent.md` §6 with Henry. |
| Merge `domain.port` interfaces + exceptions + `com.clinicflow.domain.port.Actor` (copy from `design.md` §4). | Review the ports. Anything awkward? Change them *now*, while it's free. |
| Skeleton CI: build + test + ArchUnit on every push. | Start `SlotPort`/`BookingPort` real implementation with Testcontainers. |

### Friday 23

| Henry | Deji |
|---|---|
| Fake ports (in-memory) so controllers work today. | `BookingPort.book` with the unique-index protection; `SlotPort.slotsFor`; reference generator. |
| JWT login, roles, `GlobalExceptionHandler`, `RequestIdFilter`, idempotency wrapper. | `QueuePort.changeStatus` (check-in, start, complete, cancel), `admitWalkIn`, queue lock. |
| Render + Neon created, deploy `/api/v1/meta` through the pipeline (empty but live). | Estimate calculation, `AppointmentQueryPort`, `com.clinicflow.domain.port.PatientView` derivation. |
| **H2** at midday, **H3** in the evening. | |

### Saturday 24

| Henry | Deji |
|---|---|
| SSE endpoint + `com.clinicflow.domain.port.QueueChanged` listener. Rate limiting on public endpoints. | Concurrency tests: two bookings same slot; two check-ins same instant; cancel mid-queue renumbers; reschedule race. |
| Wire the concurrency test to run **against the deployed URL** after deploy (**H4**). | Dashboard queries; `appointment_activity` writes verified. |
| Authorisation test and patient-privacy test (each must fail with the check removed). | Seed data runner v1 (enough for Monday: a clean day with a handful of each status). |

### Sunday 25

| Henry | Deji |
|---|---|
| **H5**: throwaway branch with the protection removed, record the failing run, restore. | Seed data runner complete (≥38 appointments). `POST /demo/reset`. |
| Contract tests (Schemathesis) in the pipeline. README demo credentials. Label every simulated thing. | Confirm every seed state appears in the UI with Ehimare. |
| **H6** rehearsal from a clean browser, no extensions. | |

### Monday 26: present (slides in the deck file when you are ready).

### After Monday (Days 6–14, in the brief's order)

| Days | Henry | Deji |
|---|---|---|
| Next 3 | Idempotency polish, rate limit tuning, request-id end to end with Ehimare, OpenAPI polish | All rule tests complete, each shown failing when the rule is removed |
| Next 3 | k6 job in CI, performance figures on the deployed address | Load test script, saturation point, five-queries/index table with real `EXPLAIN ANALYZE` |
| Last 2–3 | README, free-tier terms with date, decision records, AI log, walkthrough, friction note | Data model doc, seed doc, decision records, AI log, friction note |

---

## 6. When something goes wrong between you

| Situation | What to do |
|---|---|
| You need a field the contract doesn't have | Contract change protocol (§3). Never invent a field in code. |
| A port method is awkward | Open a PR on `domain.port` with the change; the other approves. Same day. |
| Your tests pass and theirs fail after a merge | The person who merged fixes it within the hour, or reverts. |
| Deji wants a table Henry's code needs (or the other way) | Ask in chat first. Henry's tables stay in V100+, Deji's in V1–V99. Use `out-of-order=true`. |
| You disagree on a design | Write both options, cost and what would make you switch, in `decision-records.md`. Decide in 10 minutes or ask Laja. |

---

## 7. What Henry sends Ehimare today

1. `openapi/openapi.yaml`
2. The mock command: `npx @stoplight/prism-cli mock openapi/openapi.yaml -p 4010`
3. These five behaviours that are easy to miss:
   - `Idempotency-Key`: one UUID per form submission, reused on retry.
   - On `SLOT_TAKEN`, show `suggestedSlots`; never leave the patient on a spinner.
   - Position is a *position*; any wait time is an *estimate* and must show `estimate.basis`.
   - Live position: `EventSource` on `/events`, with a 10-second polling fallback and a visible "Reconnecting…" state.
   - Staff status buttons send `expectedStatus`; on `STALE_STATE` replace the row with `problem.current` and say what happened.
4. Demo credentials once the service is deployed.
5. The suggested optimistic-update action for the brief: **reception's check-in click** (row moves to WAITING immediately; reverts with a message on `STALE_STATE` or `INVALID_TRANSITION`).

---

## 8. up-next (side project) backend split, preview

Same shape: **Henry** = API, auth, pipeline, OpenAPI. **Deji** = schema and rules (one vote per user per request via unique constraint; version check on card moves; status history; duplicate-merge rule). Each gets ~1/3 of the time. I'll produce its contract and schema once ClinicFlow's seam is merged, so it doesn't distract you tonight.

---

Signed: Henry Emefo ___________ (date) · Ayodeji Oni ___________ (date) · Ehimare J. Okosun ___________ (date)
