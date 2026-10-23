# ClinicFlow: intent

Why we are building this, for whom, what "done" means, and the path from today to final submission. `design.md` says *how*. This file says *what and why*, and it is the file you re-read when you are tired and unsure whether something is in scope.

**Team 2:** Henry Emefo · Ayodeji Oni (service) · Ehimare J. Okosun (interface) · Supervisor: Laja
**Cohort:** TeSA Africa cohort 6 capstone · **Side project:** up-next (separate intent, later)
**MVP checkpoint:** Monday 26 October 2026 · **Final submission:** to be confirmed by the facilitator (planning assumption: Day 14 = Tue 3 Nov 2026, counting Wed 21 Oct as Day 1)

---

## 1. The problem

Patients at a small clinic or diagnostic centre often don't know how to book a visit, and once they arrive they don't know where they stand in the queue. Reception relies on a paper list. People wait without information; staff lose track.

## 2. Who it is for

| User | What they need | How they access it |
|---|---|---|
| **Patient or carer** | Book a slot, get a reference, see their position (and watch it change), cancel or move a booking before arriving | Anonymous, by reference |
| **Reception staff** | See the day's appointments, check patients in, move them through the queue, admit walk-ins, cancel or reschedule | Staff login |
| **Clinic administrator** (demonstration role) | Daily counts and activity; reset the demo day | Staff login, ADMIN |

## 3. Product goal

A lightweight appointment-and-queue tool for **one demonstration clinic**. It organises information only. It is not a medical records system, holds no clinical information, and makes no clinical decision.

**Definition of success (from the brief):** a real user can complete the main task reliably; the data stays consistent when more than one person uses it; it is usable on a phone and by keyboard; and every engineer can explain the decisions behind the part they built. Feature count is not the measure.

## 4. Scope

### In scope (main product)
1. Patient views available slots and books; receives a reference. (Journey 1, **Monday**)
2. Reception lists the day's appointments; filters by date, status, patient name. (Journey 2)
3. Reception checks a patient in and moves them through WAITING → IN_CONSULTATION → COMPLETED; admits a walk-in ahead of the queue. (Journey 3, **Monday**)
4. Patient looks up their reference and sees position, updating live without reload. (Journey 4)
5. Dashboard: today's appointments, patients waiting, visits completed. (Journey 5)
6. Cancellation and rescheduling with confirmation.
7. Loading, empty, error, success and confirmation states on every screen.
8. Seed data covering every state, and a clearly marked demonstration mode.

### Explicitly out of scope (the answer to every "could we also…")
Health records or diagnosis · payments, insurance or pharmacy integration · SMS or WhatsApp · multiple clinics · any claim of compliance with healthcare privacy regulation · real patient data (fictional only) · payment gateways, delivery, live chat, real identity verification.

### Simulated, so labelled beside the feature
- Demonstration mode banner; all patients, names and contacts are invented.
- The wait time is an **estimate** with its basis shown; position is exact.
- The clinic is fictional: "Demo Community Clinic".
- `contact` is a fictional placeholder field, not a verified phone/email.

## 5. The one rule the product depends on (and how we prove it)

> **A slot cannot be booked twice, even when two bookings arrive in the same instant.**

- Protected **in the database** by a partial unique index (`ux_appointment_active_slot`), not by application checks.
- Proven by an automated test that fires simultaneous bookings at the **deployed** service and asserts exactly one succeeds and the rest receive `409 SLOT_TAKEN`.
- Then the protection is **removed**, the same test is shown **failing**, and the protection is restored. A test that passes both ways earns nothing.

Other rules, each with a test that fails when the rule is removed: status sequence · cancellation closes the gap behind it · two simultaneous check-ins never share a position · only staff change queue state · a patient never sees another patient's details · repeat submission records once.

### The design problem with no clean answer
Unlike the other products, ClinicFlow's tension is **how queue position should be represented**: stored positions that must be renumbered after every cancellation (easy to read, easy to corrupt under concurrency) versus a stored *rank* with position **derived** at read time (always consistent, a little more work per read). We chose derived. See ADR-002 for cost and the observation that would reverse it.

## 6. Questions for the client, and the assumption we proceed on

The brief asks teams to prepare questions for the client on day one. Put these to Laja (or the facilitator) and record the answers below. Until answered, the **assumption** column is what we build.

| # | Question | Assumption we build on | Who is affected |
|---|---|---|---|
| 1 | Is there one clinician or several rooms? May more than one patient be IN_CONSULTATION at once? | Several allowed; no limit enforced | Deji |
| 2 | Fixed slot length, or does it depend on visit type? | Fixed grid of 20-minute slots; visit type is informational | Deji, Ehimare |
| 3 | May a patient cancel/reschedule themselves, given "only staff change queue state"? | Yes, but **only while BOOKED** and only with the manage token. After check-in, staff only. | all |
| 4 | Clinic hours and days open? | 08:00–16:00 Mon–Sat, closed Sunday (seed shows a closed day) | Deji |
| 5 | How far ahead can patients book? | 14 days | Deji |
| 6 | What happens to BOOKED patients who never arrive? | Stay BOOKED until reception cancels; no automatic no-show (declared limitation) | all |
| 7 | Does a walk-in "ahead of the queue" go ahead of everyone waiting, or only behind scheduled patients already waiting? | Ahead of every WAITING patient, behind the one in consultation | Deji |
| 8 | Is a "contact" required for patients, and in what form? | Required free text, labelled fictional | Ehimare, Henry |
| 9 | Final submission date? | Tue 3 Nov (Day 14) | all |

Answers received: *(fill in with date and who answered)*

## 7. Constraints (from the brief)

- 13 engineers, 4 teams, two products per team (ClinicFlow main, up-next side). ~⅔ of time on main, ⅓ on side.
- Zero cost: free-tier hosting only. Read each provider's own pricing page; record what you found and the date in the README.
- Public repo, sensible commits, no secrets committed. Both tiers deployed by GitHub Actions with blocking gates.
- Everyone must be able to explain any part of the submission, including parts written with AI.
- Anything simulated is labelled; an undeclared stub costs the whole requirement.
- Never claim a relationship with a real clinic; no simulated activity presented as real impact.

## 8. Roadmap: from today to the end

> Day numbering assumes Wed 21 Oct = Day 1. The brief's own path assumes work started Wed 21; we start Thu 22, so **tonight absorbs a day**. Weekend work is by agreement only; nobody is pressed beyond what they agree to.

| When | Milestone | Done when |
|---|---|---|
| **Thu 22 (Day 2)** | Contract + seam + schema | `openapi.yaml` with Ehimare; mock running; `domain.port` merged; `V1` applied; repos + skeleton CI exist; split signed |
| **Fri 23 (Day 3)** | Core working | Auth and roles; booking + check-in work through real code; both tiers live (even if mostly empty); **Monday journey run once against the deployed service and divergences written down** |
| **Sat 24 (Day 4)** | Rule protected | Concurrency test passes against the deployed service; screens for the Monday journey; auth/privacy tests |
| **Sun 25 (Day 5)** | Proof + rehearsal | Protection removed → test fails (evidence kept) → restored; seed subset; five screen states on the journey; full rehearsal from a clean browser |
| **Mon 26** | **MVP checkpoint** | Slides + live demo of main product and side project |
| **Days 6–9** | Rules complete | Every rule enforced and tested; idempotency; dashboard derived; SSE hardened; side project MVP |
| **Days 10–12** | Awkward cases + measurement | Full seed; accessibility with a real screen reader; load test with a saturation point; performance measured on the deployed address |
| **Days 13–14** | Portfolio | README, screenshots, decision records, AI log, walkthrough video, per-engineer notes, rehearsal |

### What "MVP on Monday" means (three things from a clean browser on the deployed addresses)
1. **One complete journey end to end on the deployed service (not the mock):** patient books a slot and receives a reference; reception checks them in. Two browsers booking the same slot give one booking and one clear refusal.
2. **The one rule protected in the database**, with an automated concurrent test, and the team shows it failing with the protection removed.
3. **Both tiers deployed via GitHub Actions**, with seed data, demonstration credentials in the README, and every simulated feature labelled.

Not required Monday (but not skipped): full load test, screen-reader report, performance figures, full seed volumes.

## 9. Deliverables checklist

| Deliverable (from the brief) | Owner | Status |
|---|---|---|
| Two live addresses, clean-browser working | Henry (service) · Ehimare (interface) | ☐ |
| Public repo, sensible commits, no secrets | all | ☐ |
| README: problem, users, stacks, setup, demo credentials, screenshots, load/performance figures, free-tier terms + date, limitations | Henry (setup, deploy, free-tier) · Deji (data) · Ehimare (UI, a11y, screenshots) | ☐ |
| OpenAPI document (validates) | Henry + Ehimare | ✔ draft v0.1 validated |
| Mock server committed, one command | Henry | ☐ |
| Contract tests in pipeline | Henry | ☐ |
| Decisions file (every contract change, dated, two engineers) | Henry + Ehimare | ✔ started |
| Five decision records (context, decision, rejected alternatives, cost, reversal observation; ≥1 on protecting a business rule) | Henry + Deji (Ehimare for interface ones) | ✔ drafted |
| AI log (tools, asks, accepted, rejected, **≥1 verified-wrong case**) | each keeps a section | ☐ |
| Three complete user journeys demoable from clean start without hand-editing data | all | ☐ |
| Documented data model, five queries + indexes, one index not created | Deji | ✔ started in design §5 |
| Versioned, reversible migrations running in the pipeline | Deji (scripts) · Henry (job) | ✔ V1/V100 + downs tested |
| Concurrency test + removal evidence | Deji · Henry | ☐ |
| Wrong-role authorisation test (fails when check removed) | Henry | ☐ |
| Safe-to-repeat write (documented) | Henry | ☐ design done |
| Structured logs with request id across tiers | Henry (filter) · Deji (config) | ☐ |
| Load test with p50/p95/p99 and saturation point | Deji · Henry | ☐ |
| Seed data with awkward cases (≥38 appointments) | Deji | ☐ spec in design §11 |
| WCAG 2.2 AA with screen-reader findings | Ehimare | ☐ |
| LCP/INP targets set and measured on deployed address | Ehimare | ☐ |
| 10-minute walkthrough video | all | ☐ |
| One note per engineer: one thing the other tier cost you, one thing that saved you time | each | ☐ |
| **Side project** (up-next) to its MVP standard | all | ☐ |

## 10. How we will be marked (so we spend time where the marks are)

| Category | Points | Where we put the effort |
|---|---|---|
| Correctness under concurrent use | 22 | The partial unique indexes, the day lock, idempotency, derived position |
| Verification | 13 | Every rule has a test that *bites*; wrong-role test; real-DB integration tests; contract tests; load test with a real saturation point |
| Interface and accessibility | 15 | Five states, keyboard, screen reader, labelled estimates |
| Delivery | 10 | Gated pipeline, auto migrations, reproducible from clean clone, declared limits |
| Your own contribution | 15 | Your commit history and work record show the area you owned |
| Engineering judgement | 10 | Decision records with real rejected options and a named cost |
| Collaboration | 5 | The contract history and a friction note someone could act on |
| Defence | 10 | You can explain any part, including AI-written parts, and hold or revise a position |

60 marks to the team, 40 to the individual. The side project adds no marks to the 100 but is evidence for Contribution and Collaboration.

## 11. Working rules we hold ourselves to

- The OpenAPI file is the only agreement between tiers. No path that isn't in it is built or called.
- Henry never edits `domain/`; Deji never edits `web/`. Changes to `domain.port` need both.
- Rules are enforced by the database, not by "check then write" code.
- Anything simulated or estimated is labelled where the user sees it.
- Log AI mistakes the moment you catch them.
- If the main product is at risk, tell Laja **Friday**, protect the main product, shrink the side project.

## 12. Known limitations (declared up front)

- Single service instance; the live-position stream is in-memory, so a restart drops connections (the interface reconnects and falls back to polling).
- No automatic no-show handling.
- Wait time is an estimate from today's completed consultations.
- One clinic, one day-grid, fictional data only, no clinical content, no compliance claims.
- Free host may sleep; the first request after idle can be slow.
