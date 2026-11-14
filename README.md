# ClinicFlow API

Appointment booking and live queue tracking for a small clinic. This is the backend service of **ClinicFlow**, the Team 2 capstone for TeSA Africa cohort 6.

> **Demonstration project.** The clinic ("Demo Community Clinic"), its patients, names and contacts are all fictional. ClinicFlow organises appointment and queue information only. It holds no clinical data, makes no clinical decisions, and claims no compliance with any healthcare privacy regulation.

| | |
|---|---|
| **Live API** | https://clinicflow-api-ytgg.onrender.com (free tier: the first request after idle can take up to a minute) |
| **Health check** | `GET /api/v1/meta` |
| **Web app** | `clinicflow-web` repository (Ehimare J. Okosun) — *[add link]* |
| **API contract** | [`openapi/openapi.yaml`](openapi/openapi.yaml) |

---

## The problem

Patients at a small clinic often don't know how to book a visit, and once they arrive they don't know where they stand in the queue. Reception relies on a paper list. People wait without information and staff lose track.

## Who it is for

| User | What they can do | Access |
|---|---|---|
| **Patient or carer** | View free slots, book, get a reference, watch their queue position update live, cancel or reschedule while still `BOOKED` | Anonymous, by reference |
| **Reception staff** | List and filter the day's appointments, check patients in, move them through the queue, admit walk-ins, cancel or reschedule | Staff login (`RECEPTIONIST`) |
| **Clinic administrator** | Everything reception can do, plus the dashboard and the demo-day reset | Staff login (`ADMIN`) |

## The one rule the product depends on

> **A slot cannot be booked twice, even when two bookings arrive in the same instant.**

It is enforced **in the database**, not in application code, by a partial unique index:

```sql
CREATE UNIQUE INDEX ux_appointment_active_slot
  ON appointment (slot_id)
  WHERE slot_id IS NOT NULL AND status <> 'CANCELLED';
```

The service never asks "is it free?" first. It inserts, and the database refuses the second booking, which the API returns as `409 SLOT_TAKEN` with alternative slots.

**Proof** ([full write-up](docs/evidence/clinicflow-evidence.md)), using [`RaceCheck.java`](RaceCheck.java) to book one slot with 8 different patients at the same moment:

| Where | Protection | Result |
|---|---|---|
| Deployed service | Index in place | **1 booked, 7 refused** (pass) |
| Local copy, same code | Index dropped | **8 booked, 0 refused** (fail: the double-booking bug) |

Identical test, code and database engine; only the index differs. Screenshots are in [`docs/evidence/`](docs/evidence/).

---

## Tech stack

- **Java 21**, **Spring Boot 4.1.1** (Web MVC, Security, Validation, JDBC, Actuator)
- **PostgreSQL 16** with **Flyway** migrations (plain SQL through `JdbcTemplate`, no ORM)
- **JWT** authentication for staff, **bcrypt** password hashes
- **Server-Sent Events** for live queue position
- **Testing:** JUnit 5, Testcontainers (real Postgres), ArchUnit
- **Delivery:** Docker, GitHub Actions, Render (service) and Neon (database)

## Architecture

One Spring Boot app, two sides, one seam between them.

```
   web package              THE SEAM               domain package
   HTTP, JSON, auth,    domain.port interfaces     Rules, SQL, transactions,
   errors, idempotency, ──► records + exceptions ◄── concurrency, seed data
   SSE
```

- `com.clinicflow.web` — controllers, DTOs, JWT security, error mapping (RFC 7807 problem responses), idempotency, SSE.
- `com.clinicflow.domain.port` — the interfaces (`BookingPort`, `QueuePort`, `SlotPort`, …), records and domain exceptions. The only thing `web` may import from `domain`.
- `com.clinicflow.domain` — `PostgresClinic`, the real implementation of the ports.
- `com.clinicflow.fake` — `InMemoryClinic`, an in-memory implementation used for building against the contract without a database.

An **ArchUnit test fails the build** if `web` imports anything from `domain` other than `domain.port`, so the seam is enforced by the pipeline.

### Switching between fake and real ports

| `clinicflow.ports` | Behaviour |
|---|---|
| `fake` (default) | In-memory data, no database needed for the domain. Returns 24 slots per day. |
| `real` | Postgres-backed ports. Returns 40 slots per day. Creates the next 15 days of slots at startup and seeds today's demo clinic day if the database is empty. |

Set it with the environment variable `CLINICFLOW_PORTS=real`. **Any deployed environment must use `real`.**

### Key design decisions

Full records, including rejected alternatives, costs and what would make us reverse each one, are in [`docs/decision-records.md`](docs/decision-records.md). In short:

- **Double-booking** is prevented by the partial unique index above (ADR-001).
- **Queue position is derived, not stored.** Patients get a stored `queue_rank`; position is computed at read time, so a cancellation mid-queue can never leave a half-renumbered queue. Queue changes serialise per clinic day with `SELECT … FOR UPDATE` (ADR-002).
- **Live updates use SSE with a polling fallback**, not WebSockets (ADR-003). The in-memory stream registry means a single instance only.
- **Migrations are Flyway forward scripts plus hand-written down scripts** in [`db/down/`](db/down/) (ADR-004).
- **Repeat submissions record once** via an `Idempotency-Key` header, backed by a primary key on `(key, scope)`.

More detail in [`docs/design.md`](docs/design.md) and [`docs/intent.md`](docs/intent.md).

---

## Getting started

### Prerequisites

- JDK 21
- Docker (for the local database and for the Testcontainers-based tests)

### Run locally

```bash
# 1. Start Postgres
docker compose up -d

# 2. Run the API against it with the real ports
CLINICFLOW_PORTS=real ./mvnw spring-boot:run
```

On Windows PowerShell:

```powershell
docker compose up -d
$env:CLINICFLOW_PORTS = "real"
.\mvnw.cmd spring-boot:run
```

The API is then at `http://localhost:8080`. Check it with:

```bash
curl http://localhost:8080/api/v1/meta
```

To run with no database-backed domain at all, skip the `CLINICFLOW_PORTS` variable and the app uses the in-memory fake ports. Note that Flyway and the datasource are still configured, so keep `docker compose up -d` running.

To reset the local database completely: `docker compose down -v`.

### Run the tests

```bash
./mvnw -B verify
```

This runs the full suite, including the ArchUnit seam check and the Testcontainers tests against a real Postgres, so Docker must be running.

### Run with Docker

```bash
docker build -t clinicflow-api .
docker run -p 8080:8080 \
  -e CLINICFLOW_PORTS=real \
  -e DB_URL=jdbc:postgresql://host.docker.internal:5432/clinicflow \
  -e DB_USER=clinicflow -e DB_PASSWORD=clinicflow \
  -e CLINICFLOW_JWT_SECRET=<a long random secret> \
  clinicflow-api
```

---

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8080` | HTTP port |
| `CLINICFLOW_PORTS` | `fake` | `fake` or `real` (see above) |
| `DB_URL` | `jdbc:postgresql://localhost:5432/clinicflow` | JDBC URL (use your Neon URL when deployed) |
| `DB_USER` / `DB_PASSWORD` | `clinicflow` / `clinicflow` | Database credentials |
| `CLINICFLOW_JWT_SECRET` | *development-only value* | **Must be set in every deployed environment.** The default is public. |
| `CLINICFLOW_CORS_ORIGINS` | `http://localhost:5173,http://localhost:3000` | Comma-separated browser origins allowed to call the API |
| `DEMO_RECEPTION_PASSWORD` / `DEMO_ADMIN_PASSWORD` | see below | Demo staff passwords |

Other settings in `application.properties`: JWT lifetime is 60 minutes, and `clinicflow.closed-days=SUNDAY` (set it empty to rehearse on a Sunday).

## Demo credentials

**For demonstration only.** These accounts are created at startup if they don't exist. Override the passwords through environment variables in any shared deployment.

| Role | Email | Password |
|---|---|---|
| Reception | `reception@clinicflow.demo` | `Reception-Demo-1` |
| Admin | `admin@clinicflow.demo` | `Admin-Demo-1` |

```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"reception@clinicflow.demo","password":"Reception-Demo-1"}'
```

---

## API overview

The contract is [`openapi/openapi.yaml`](openapi/openapi.yaml) (v0.1.0, 20 operations). A path that is not in that file is not implemented. Every change to it gets a dated line in [`docs/contract-decisions.md`](docs/contract-decisions.md).

| Area | Endpoints | Auth |
|---|---|---|
| Public | `GET /api/v1/meta`, `/visit-types`, `/slots?date=` | None |
| Patient | `POST /api/v1/patient/appointments`, `GET …/{reference}`, `GET …/{reference}/events` (SSE), `POST …/{reference}/cancellation`, `PUT …/{reference}/slot` | None; cancel and reschedule need the manage token |
| Auth | `POST /api/v1/auth/login`, `GET /api/v1/auth/me` | Login open; `me` needs a token |
| Staff | `GET /api/v1/appointments`, `GET …/{id}`, `PUT …/{id}/status`, `PUT …/{id}/slot`, `GET …/{id}/activity`, `POST /walk-ins`, `GET /queue`, `GET /dashboard`, `GET /activity` | `RECEPTIONIST` or `ADMIN` |
| Demo | `POST /api/v1/demo/reset` | `ADMIN` only |

**Appointment lifecycle:** `BOOKED → WAITING → IN_CONSULTATION → COMPLETED`, with `CANCELLED` as the alternative exit. Patients can cancel or reschedule only while `BOOKED`.

**Errors** use RFC 7807 problem responses with a stable `code`:

| Status | Code | Meaning |
|---|---|---|
| 409 | `SLOT_TAKEN` | Someone booked it first; response offers alternative slots |
| 409 | `INVALID_TRANSITION` | Status change not allowed from the current status |
| 409 | `STALE_STATE` | The appointment changed since it was read |
| 409 | `CLINIC_CLOSED` | The clinic is closed that day |
| 422 | `SLOT_IN_PAST` | Slot has already started |
| 422 | `IDEMPOTENCY_KEY_MISMATCH` | Same `Idempotency-Key` reused with a different body |
| 422 | `VALIDATION_FAILED` | Request body failed validation |
| 401 | `UNAUTHENTICATED` | Missing, expired or bad credentials |
| 404 | `NOT_FOUND` | Unknown resource |

Every response carries a request id (`RequestIdFilter`) for tracing across tiers.

### Privacy

The patient-facing view deliberately has no contact field and nothing about other patients. Queue state can be changed only by authenticated staff. `ApiSecurityAndSafetyTest` covers both.

---

## Testing and verification

| Test | What it proves |
|---|---|
| `PostgresClinicRulesTest` (real Postgres) | Eight simultaneous bookings of one slot create exactly one; simultaneous check-ins never share a queue position; cancelling mid-queue closes the gap |
| `ApiSecurityAndSafetyTest` | Wrong role is refused; patient view never leaks contact details; the same idempotency key twice books once; race over HTTP |
| `JwtServiceTest` | Token issue and validation |
| `ArchitectureTest` | `web` cannot reach past the `domain.port` seam |
| [`RaceCheck.java`](RaceCheck.java) | The booking rule holds on a deployed service (usage below) |
| [`db/verify_schema_concurrency.py`](db/verify_schema_concurrency.py), [`db/verify_real_ports_sql.py`](db/verify_real_ports_sql.py) | Schema-level concurrency and SQL checks against Postgres |

```bash
java RaceCheck.java https://clinicflow-api-ytgg.onrender.com
```

`RaceCheck` leaves one booking cancelled after each run. See the [evidence document](docs/evidence/clinicflow-evidence.md) for how to reproduce the failing run by dropping the index on a *local* database.

### Load test

[`load-tests/appointments-test.js`](load-tests/appointments-test.js) is a [k6](https://k6.io) script in which each virtual user books its own slot. It reads `TEST_DATE` (default: today) and currently targets `http://localhost:8080`.

```bash
k6 run load-tests/appointments-test.js
```

Recorded so far ([screenshots](docs/load-tests/)):

| Run | Result |
|---|---|
| 10 concurrent users, local | 10/10 bookings returned 201; p95 = 110 ms; 0% failed requests |
| 100 concurrent users, local | Aborted in setup: the script needs 10 future slots and found 3 |

**Not yet measured:** a saturation point, and results against the deployed service. See *Limitations*.

---

## Delivery pipeline

[`.github/workflows/ci.yml`](.github/workflows/ci.yml):

1. **build-test** — `./mvnw -B verify` on every push to `main` and every pull request (includes the ArchUnit seam check).
2. **deploy** — on a push to `main`, after build-test passes, triggers a Render deploy through a hook (`RENDER_DEPLOY_HOOK_URL` secret).
3. **smoke** — polls `GET {API_URL}/api/v1/meta` until it returns 200 (up to 40 attempts, 15 s apart; `API_URL` repository variable).

**Migrations** live in `src/main/resources/db/migration` and run automatically at startup. Henry's stream is `V100–V199` (`staff_user`, `idempotency_key`) and Deji's is `V1–V99`, with `out-of-order` enabled so the two can interleave. Matching reversals are in [`db/down/`](db/down/).

## Repository layout

```
├── src/main/java/com/clinicflow/
│   ├── web/            controllers, DTOs, security, errors, idempotency, SSE
│   ├── domain/         real (Postgres) implementation of the ports
│   │   └── port/       the seam: interfaces, records, exceptions
│   └── fake/           in-memory ports
├── src/main/resources/db/migration/   Flyway migrations
├── src/test/           rule, security, JWT and architecture tests
├── openapi/            API contract
├── db/                 down scripts and Postgres verification scripts
├── load-tests/         k6 script
├── docs/               intent, design, decision records, evidence, team split
├── RaceCheck.java      concurrent-booking proof tool
├── Dockerfile
└── docker-compose.yml  local Postgres 16
```

## Documentation

| Document | Contents |
|---|---|
| [`docs/intent.md`](docs/intent.md) | Why, for whom, scope, out-of-scope, assumptions about the clinic |
| [`docs/design.md`](docs/design.md) | System design, data model, concurrency and idempotency, SSE, test strategy |
| [`docs/decision-records.md`](docs/decision-records.md) | Architecture decisions with rejected alternatives and reversal triggers |
| [`docs/contract-decisions.md`](docs/contract-decisions.md) | Dated log of every API contract change |
| [`docs/evidence/clinicflow-evidence.md`](docs/evidence/clinicflow-evidence.md) | The double-booking rule, passing and failing |
| [`docs/team-split.md`](docs/team-split.md) | Who owns what and the working agreements |

---

## Assumptions about the clinic

These were built on until the client confirms otherwise (see `docs/intent.md` §6): 20-minute slots on a fixed grid; open 08:00–16:00 Monday–Saturday and closed Sunday; bookable up to 14 days ahead; several patients may be `IN_CONSULTATION` at once; a walk-in goes ahead of every `WAITING` patient but behind the one in consultation; `contact` is a required, fictional free-text field.

## Simulated and labelled

- The clinic, patients, staff and contacts are invented.
- The wait time is an **estimate** with its basis shown; queue position is exact.
- The demo reset endpoint and demo staff accounts exist for demonstration only.

## Limitations

- **Single instance.** SSE streams are held in memory, so scaling out or restarting drops live connections (clients fall back to polling).
- **No automatic no-shows.** A `BOOKED` patient who never arrives stays `BOOKED` until reception cancels.
- **The booking calendar rolls forward only on restart** or when the demo day is reset.
- **Free-tier hosting.** The service sleeps when idle, so the first request after a quiet period can be slow.
- **Load testing is incomplete.** Only a 10-user local baseline is recorded; there is no saturation point and no deployed-service figure yet.
- **Only the booking rule has a recorded failure-without-protection experiment.** Other rules have tests, but each hasn't yet been put through its own removal experiment.
- **Contract tests (Schemathesis) and a rollback workflow are planned in the design docs but not present in the repository.**


## Team

**Team 2**, TeSA Africa cohort 6 · Supervisor: Laja

| Name | Area |
|---|---|
| Henry Emefo | API, auth, idempotency, SSE, pipeline, deployment |
| Ayodeji Oni | Data model, business rules, seed data, concurrency tests |
| Ehimare J. Okosun | Web interface (`clinicflow-web`) |
