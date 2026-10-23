# Contract decisions

Every change to `openapi/openapi.yaml` gets one dated line: **what changed, why, and which two engineers agreed it.** The service does not implement a path that is not in the document, and the interface does not call one.

| Date | Change | Why | Agreed by |
|---|---|---|---|
| 2026-10-22 | v0.1.0 initial contract: 20 operations (meta, visit types, slots, patient booking/lookup/stream/cancel/reschedule, auth, staff list/detail/status/slot/activity, walk-ins, queue, dashboard, activity, demo reset) | Starting point so Ehimare can build against the mock | Henry Emefo, Ehimare J. Okosun *(confirm when Ehimare approves the PR)* |
