# Evidence: ClinicFlow's booking rule holds, and the test fails without its protection

**The rule the product depends on:** a slot cannot be booked twice, even when two bookings arrive at the same instant.
**How it is protected:** in the database, by a partial unique index, `ux_appointment_active_slot`, on `appointment (slot_id)` for every non-cancelled booking. The service does not check "is it free?" first. It inserts, and the database refuses the second booking.

- **Team 2, ClinicFlow** · **Date of runs:** 1 November 2026 (Lagos time)
- **Tool:** `RaceCheck.java`, run with `java RaceCheck.java <service address>`. It books **one slot** with **8 different patients at the same moment** (a start latch releases all eight requests together), each with its own `Idempotency-Key` and its own name, so the idempotency feature cannot be what is refusing them. Only the slot rule can.
- **What counts as a pass:** exactly 1 booking (HTTP 201), the other 7 refused with `409 SLOT_TAKEN`, each refusal offering alternative slots.

## 1. Summary

| # | Where | Protection | 8 simultaneous bookings of one slot | Result |
|---|---|---|---|---|
| A | **Deployed service** (Render, real ports on Postgres) | In place | **1 booked, 7 refused** | PASS |
| B | Local copy of the same code, real ports | **Index dropped** | **8 booked, 0 refused** | FAIL (the double-booking bug) |

The same test, the same code and the same database engine give opposite results depending only on whether the index exists. That is what shows the test measures the protection and not something incidental.

## 2. Run A: the deployed service, protection in place

- **Target:** `https://clinicflow-api-ytgg.onrender.com`, run at 2026-11-01 22:22 (Lagos).
- **Confirmed running on the real ports before the run:** the slot list for the test date returned **40** slots (the fake in-memory ports return 24).
- **Slot raced for:** one of 40 free slots on 2026-11-03.
- **Output:** booked (HTTP 201) 1 · refused (409 SLOT_TAKEN) 7, each offering alternative slots · anything else 0 · cleanup cancelled the winning booking (HTTP 200).
- **Verdict printed by the tool:** `RESULT: PASS - exactly one booking won; the rule held under simultaneous requests.`

![Screenshot A: deployed service, one booking won, seven refused](clinicflow/A-deployed-race-pass.png)

## 3. Run B: the same test with the protection removed

- **Target:** a local copy of the same code on `http://localhost:8080`, real ports, a local Postgres. The deployed database was never touched.
- **Change made:** `DROP INDEX ux_appointment_active_slot;` run against the local database after the app had started and applied its migrations.
- **Output:** 40 free slots, one raced for · booked (HTTP 201) **8** · refused **0** · anything else 0.
- **Verdict printed by the tool:** `RESULT: FAIL - 8 bookings won out of 8. The rule did NOT hold.`

Eight patients were each told they had the same slot. That is the defect the rule exists to prevent, so the test detects the loss of the protection.

![Screenshot B: protection removed locally, all eight bookings succeeded](clinicflow/B-local-race-fail-index-removed.png)

**Recovery:** the local database was discarded with `docker compose down -v` so no damaged index or duplicate rows remain. [confirm]

## 4. How this fits with the rest of the tests

| Test | Level | What it proves | Owner |
|---|---|---|---|
| `RaceCheck` against the deployed URL | Deployed service, real HTTP | The rule holds in production conditions (this document) | Henry |
| `PostgresClinicRulesTest.eight_simultaneous_bookings_of_one_slot_create_exactly_one` | Service code against a real Postgres | Same rule, at the port level, with 3 alternative slots offered on refusal | Deji |
| `PostgresClinicRulesTest.simultaneous_check_ins_never_share_a_position` | Real Postgres | Two check-ins never get the same queue position | Deji |
| `PostgresClinicRulesTest.cancelling_mid_queue_closes_the_gap_behind_it` | Real Postgres | Position is derived, so a cancellation closes the gap | Deji |
| `ApiSecurityAndSafetyTest` (access, privacy, idempotency, race over HTTP) | Real HTTP | Wrong role is refused; patient view never leaks contact details; same key twice books once | Henry |
| `ArchitectureTest` | Build | `web` code cannot reach past the `domain.port` seam | Henry |

The full suite was reported green on the merged `main` after the real ports were added. [paste the last lines of the final `.\mvnw.cmd -B verify`: `Tests run: __, Failures: 0, Errors: 0`, with date and time]

## 5. Honest limits of this evidence
- **One rule has been shown failing without its protection in this document** (the booking rule, run B). The queue-position and wrong-role tests are written to check their rules, but each has not yet been put through its own removal experiment. They are listed in the plan, not claimed here.
- Run A was done on the free hosting tier after a restart. An earlier attempt appeared to hang while the service was starting; after a redeploy it answered normally and the check passed. The first request after the service has been idle can be slow, which is a property of the free tier.
- `RaceCheck` leaves one booking cancelled after each run. In run B only the last of the eight winners was cancelled, which is why the local database was discarded.

## 6. Reproduce it

```powershell
# A: deployed service (the first request may take up to a minute while the host wakes up)
Invoke-RestMethod https://<your-service>.onrender.com/api/v1/meta
java RaceCheck.java https://<your-service>.onrender.com

# B: local, protection removed
docker compose up -d
$env:CLINICFLOW_PORTS = "real"
.\mvnw.cmd spring-boot:run                       # wait until it has started
docker exec -it clinicflow-db psql -U clinicflow -d clinicflow -c "DROP INDEX ux_appointment_active_slot;"
java RaceCheck.java http://localhost:8080        # expect FAIL
docker compose down -v                            # discard the damaged local database
```
