package com.clinicflow.domain;

/**
 * Every SQL statement the real ports run, in one place, so it can be read, reviewed and run against a real
 * Postgres on its own (see db/verify_real_ports_sql.py). `?` placeholders are JDBC style.
 * OWNER: Deji (Data and rules). Written by Henry as a first draft for Deji to review and own.
 */
final class Sql {
    private Sql() {}

    // ------------------------------------------------------------------ calendar
    static final String ENSURE_DAY =
            "INSERT INTO clinic_day (clinic_date, is_open) VALUES (?, ?) ON CONFLICT DO NOTHING";

    /** 15-minute slots from 08:00 to 17:45 (40 a day), Lagos time. Args: date, date, date. */
    static final String ENSURE_SLOTS = """
            INSERT INTO slot (clinic_date, starts_at, ends_at)
            SELECT ?::date, t, t + interval '15 minutes'
            FROM generate_series((?::date + time '08:00') AT TIME ZONE 'Africa/Lagos',
                                 (?::date + time '17:45') AT TIME ZONE 'Africa/Lagos',
                                 interval '15 minutes') AS t
            ON CONFLICT (starts_at) DO NOTHING
            """;

    static final String DAY_OPEN = "SELECT is_open FROM clinic_day WHERE clinic_date = ?";

    // ------------------------------------------------------------------ reference data and slots
    static final String VISIT_TYPES =
            "SELECT id, code, label, duration_minutes FROM visit_type WHERE active ORDER BY id";

    static final String SLOTS_FOR = """
            SELECT s.id, s.starts_at, s.ends_at,
                   NOT EXISTS (SELECT 1 FROM appointment a WHERE a.slot_id = s.id AND a.status <> 'CANCELLED') AS available
            FROM slot s JOIN clinic_day d ON d.clinic_date = s.clinic_date
            WHERE s.clinic_date = ? AND d.is_open AND s.starts_at > now()
            ORDER BY s.starts_at
            """;

    static final String SLOT_BY_ID = "SELECT id, clinic_date, starts_at, ends_at FROM slot WHERE id = ?";

    /** Args: date, slotId to exclude, wanted start. Nearest free slots the same day. */
    static final String SUGGESTIONS = """
            SELECT s.id, s.starts_at, s.ends_at
            FROM slot s JOIN clinic_day d ON d.clinic_date = s.clinic_date
            WHERE s.clinic_date = ? AND d.is_open AND s.starts_at > now() AND s.id <> ?
              AND NOT EXISTS (SELECT 1 FROM appointment a WHERE a.slot_id = s.id AND a.status <> 'CANCELLED')
            ORDER BY abs(extract(epoch FROM (s.starts_at - ?::timestamptz)))
            LIMIT 3
            """;

    // ------------------------------------------------------------------ writes
    /**
     * THE RULE: a plain INSERT. No "is it free?" check first. The partial unique index
     * ux_appointment_active_slot refuses the second booking, whatever the application code does.
     */
    static final String INSERT_BOOKING = """
            INSERT INTO appointment (reference, manage_token_hash, patient_display_name, contact, visit_type_id,
                                     clinic_date, slot_id, is_walk_in, status)
            VALUES (?, ?, ?, ?, ?, ?, ?, false, 'BOOKED')
            RETURNING id
            """;

    static final String INSERT_WALK_IN = """
            INSERT INTO appointment (reference, patient_display_name, contact, visit_type_id, clinic_date,
                                     slot_id, is_walk_in, status, queue_rank, checked_in_at)
            VALUES (?, ?, ?, ?, ?, NULL, true, 'WAITING', ?, now())
            RETURNING id
            """;

    /** Serialises every queue change for one day. The unique rank index is the backstop if this is ever skipped. */
    static final String LOCK_DAY = "SELECT 1 FROM clinic_day WHERE clinic_date = ? FOR UPDATE";
    static final String LOCK_APPOINTMENT = "SELECT status FROM appointment WHERE id = ? FOR UPDATE";

    /** Atomic counters: each statement returns the value BEFORE the change, so two callers never get the same rank. */
    static final String NEXT_BACK_RANK =
            "UPDATE clinic_day SET back_rank = back_rank + 1 WHERE clinic_date = ? RETURNING back_rank - 1";
    static final String NEXT_FRONT_RANK =
            "UPDATE clinic_day SET front_rank = front_rank - 1 WHERE clinic_date = ? RETURNING front_rank + 1";

    static final String TO_WAITING = """
            UPDATE appointment SET status = 'WAITING', queue_rank = ?, checked_in_at = now(),
                   version = version + 1, updated_at = now() WHERE id = ?
            """;
    static final String TO_CONSULTATION = """
            UPDATE appointment SET status = 'IN_CONSULTATION', consultation_started_at = now(),
                   version = version + 1, updated_at = now() WHERE id = ?
            """;
    static final String TO_COMPLETED = """
            UPDATE appointment SET status = 'COMPLETED', completed_at = now(),
                   version = version + 1, updated_at = now() WHERE id = ?
            """;
    static final String TO_CANCELLED = """
            UPDATE appointment SET status = 'CANCELLED', cancelled_at = now(), cancel_reason = ?,
                   version = version + 1, updated_at = now() WHERE id = ?
            """;
    /** One UPDATE: the unique slot index rejects it if the new slot was just taken, and the old slot is untouched. */
    static final String RESCHEDULE = """
            UPDATE appointment SET slot_id = ?, clinic_date = ?, version = version + 1, updated_at = now() WHERE id = ?
            """;

    static final String ACTIVITY_INSERT = """
            INSERT INTO appointment_activity (appointment_id, event_type, from_status, to_status, actor, actor_role, request_id)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    // ------------------------------------------------------------------ reads
    /** Position is DERIVED: 1 + the WAITING patients that day with a lower rank. Never stored. */
    static final String BASE = """
            SELECT a.id, a.reference, a.patient_display_name, a.contact, vt.id AS vt_id, vt.code AS vt_code,
                   vt.label AS vt_label, vt.duration_minutes AS vt_minutes, a.slot_id, s.starts_at, s.ends_at,
                   a.is_walk_in, a.status, a.queue_rank, a.checked_in_at, a.consultation_started_at, a.completed_at,
                   a.cancelled_at, a.cancel_reason, a.version, a.created_at, a.updated_at, a.clinic_date,
                   CASE WHEN a.status = 'WAITING' THEN 1 + (SELECT count(*)::int FROM appointment o
                        WHERE o.clinic_date = a.clinic_date AND o.status = 'WAITING' AND o.queue_rank < a.queue_rank)
                   END AS queue_pos
            FROM appointment a
            JOIN visit_type vt ON vt.id = a.visit_type_id
            LEFT JOIN slot s ON s.id = a.slot_id
            """;
    static final String WHERE_ID = " WHERE a.id = ?";
    static final String WHERE_REFERENCE = " WHERE a.reference = ?";
    static final String WHERE_DAY_STATUS_ORDER_CONSULT =
            " WHERE a.clinic_date = ? AND a.status = 'IN_CONSULTATION' ORDER BY a.consultation_started_at, a.id";
    static final String WHERE_DAY_WAITING_ORDER =
            " WHERE a.clinic_date = ? AND a.status = 'WAITING' ORDER BY a.queue_rank";

    static final String SEARCH_COUNT = "SELECT count(*) FROM appointment a WHERE a.clinic_date = ?";
    static final String SEARCH_WHERE = " WHERE a.clinic_date = ?";
    static final String SEARCH_AND_Q =
            " AND (lower(a.patient_display_name) LIKE ? ESCAPE '\\' OR upper(a.reference) = upper(?))";
    static final String SEARCH_ORDER_PAGE = " ORDER BY COALESCE(s.starts_at, a.created_at), a.id LIMIT ? OFFSET ?";

    static final String TOKEN_BY_REFERENCE = "SELECT id, manage_token_hash FROM appointment WHERE reference = ?";
    static final String WAITING_REFS =
            "SELECT reference FROM appointment WHERE clinic_date = ? AND status = 'WAITING'";
    static final String COMPLETED_STATS = """
            SELECT count(*) AS n, avg(extract(epoch FROM (completed_at - consultation_started_at))) AS avg_seconds
            FROM appointment
            WHERE clinic_date = ? AND status = 'COMPLETED' AND consultation_started_at IS NOT NULL AND completed_at IS NOT NULL
            """;

    static final String DASH_COUNTS =
            "SELECT status, count(*) AS c FROM appointment WHERE clinic_date = ? GROUP BY status";
    static final String DASH_WALK_INS =
            "SELECT count(*) FROM appointment WHERE clinic_date = ? AND is_walk_in";

    static final String ACTIVITY_FOR = """
            SELECT x.id, x.appointment_id, a.reference, x.event_type, x.from_status, x.to_status, x.actor, x.actor_role, x.occurred_at
            FROM appointment_activity x JOIN appointment a ON a.id = x.appointment_id
            WHERE x.appointment_id = ? ORDER BY x.occurred_at, x.id
            """;
    static final String ACTIVITY_RECENT = """
            SELECT x.id, x.appointment_id, a.reference, x.event_type, x.from_status, x.to_status, x.actor, x.actor_role, x.occurred_at
            FROM appointment_activity x JOIN appointment a ON a.id = x.appointment_id
            ORDER BY x.id DESC LIMIT ?
            """;

    // ------------------------------------------------------------------ demo seed
    static final String DELETE_DAY_ACTIVITY =
            "DELETE FROM appointment_activity WHERE appointment_id IN (SELECT id FROM appointment WHERE clinic_date = ?)";
    static final String DELETE_DAY_APPOINTMENTS = "DELETE FROM appointment WHERE clinic_date = ?";
    static final String RESET_RANKS = "UPDATE clinic_day SET front_rank = 0, back_rank = 1 WHERE clinic_date = ?";
    static final String SET_RANKS = "UPDATE clinic_day SET front_rank = ?, back_rank = ? WHERE clinic_date = ?";
    static final String DAY_SLOT_IDS = "SELECT id FROM slot WHERE clinic_date = ? ORDER BY starts_at";
    static final String COUNT_DAY = "SELECT count(*) FROM appointment WHERE clinic_date = ?";

    /** Times are passed as "minutes ago" (or NULL). */
    static final String INSERT_SEED = """
            INSERT INTO appointment (reference, manage_token_hash, patient_display_name, contact, visit_type_id, clinic_date,
                                     slot_id, is_walk_in, status, queue_rank, checked_in_at, consultation_started_at,
                                     completed_at, cancelled_at, cancel_reason)
            VALUES (?, ?, ?, '0000-FICTIONAL', ?, ?, ?, ?, ?, ?,
                    CASE WHEN ?::int IS NULL THEN NULL ELSE now() - make_interval(mins => ?::int) END,
                    CASE WHEN ?::int IS NULL THEN NULL ELSE now() - make_interval(mins => ?::int) END,
                    CASE WHEN ?::int IS NULL THEN NULL ELSE now() - make_interval(mins => ?::int) END,
                    CASE WHEN ?::int IS NULL THEN NULL ELSE now() - make_interval(mins => ?::int) END,
                    ?)
            RETURNING id
            """;
}
