-- =====================================================================
-- V1__clinicflow_core.sql        OWNER: Deji (Data and rules)
-- Everything fictional. No clinical data. All times are timestamptz (UTC);
-- clinic_date is the calendar date in Africa/Lagos.
-- The two rules the product depends on are protected HERE, not in Java:
--   ux_appointment_active_slot  -> a slot cannot be booked twice
--   ux_appointment_queue_rank   -> two check-ins can never share a queue rank
-- =====================================================================

CREATE TABLE visit_type (
    id               smallint     PRIMARY KEY,
    code             varchar(30)  NOT NULL UNIQUE,
    label            varchar(80)  NOT NULL,
    duration_minutes integer      NOT NULL CHECK (duration_minutes > 0),
    active           boolean      NOT NULL DEFAULT true
);

-- One row per operating day. Doubles as the lock target for queue changes
-- and holds the two rank counters (front counts down, back counts up).
CREATE TABLE clinic_day (
    clinic_date date     PRIMARY KEY,
    is_open     boolean  NOT NULL DEFAULT true,
    open_time   time     NOT NULL DEFAULT '08:00',
    close_time  time     NOT NULL DEFAULT '16:00',
    front_rank  bigint   NOT NULL DEFAULT 0,   -- next walk-in-ahead gets front_rank, then decrements
    back_rank   bigint   NOT NULL DEFAULT 1,   -- next normal check-in gets back_rank, then increments
    CHECK (close_time > open_time)
);

CREATE TABLE slot (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    clinic_date date        NOT NULL REFERENCES clinic_day(clinic_date),
    starts_at   timestamptz NOT NULL,
    ends_at     timestamptz NOT NULL,
    CONSTRAINT ck_slot_order CHECK (ends_at > starts_at),
    CONSTRAINT ux_slot_start UNIQUE (starts_at)
);
CREATE INDEX ix_slot_day ON slot (clinic_date, starts_at);

CREATE TABLE appointment (
    id                    uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    reference             varchar(12)  NOT NULL UNIQUE,          -- e.g. CF-7K3Q-9XMD
    manage_token_hash     char(64),                              -- sha-256 hex of the patient's manage token
    patient_display_name  varchar(120) NOT NULL,                 -- fictional
    contact               varchar(60)  NOT NULL,                 -- fictional placeholder
    visit_type_id         smallint     NOT NULL REFERENCES visit_type(id),
    clinic_date           date         NOT NULL REFERENCES clinic_day(clinic_date),
    slot_id               uuid         REFERENCES slot(id),      -- NULL for walk-ins
    is_walk_in            boolean      NOT NULL DEFAULT false,
    status                varchar(20)  NOT NULL
        CHECK (status IN ('BOOKED','WAITING','IN_CONSULTATION','COMPLETED','CANCELLED')),
    queue_rank            bigint,                                -- NULL until checked in; position is DERIVED from it
    checked_in_at         timestamptz,
    consultation_started_at timestamptz,
    completed_at          timestamptz,
    cancelled_at          timestamptz,
    cancel_reason         varchar(200),
    version               integer      NOT NULL DEFAULT 0,
    created_at            timestamptz  NOT NULL DEFAULT now(),
    updated_at            timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_walkin_slot CHECK ((is_walk_in AND slot_id IS NULL) OR (NOT is_walk_in AND slot_id IS NOT NULL)),
    CONSTRAINT ck_waiting_has_rank CHECK (status <> 'WAITING' OR queue_rank IS NOT NULL),
    CONSTRAINT ck_cancel_has_time CHECK (status <> 'CANCELLED' OR cancelled_at IS NOT NULL)
);

-- RULE 1: a slot cannot be booked twice. A cancelled booking frees the slot.
CREATE UNIQUE INDEX ux_appointment_active_slot
    ON appointment (slot_id)
    WHERE slot_id IS NOT NULL AND status <> 'CANCELLED';

-- RULE 2: no two people share a queue rank on the same day.
CREATE UNIQUE INDEX ux_appointment_queue_rank
    ON appointment (clinic_date, queue_rank)
    WHERE queue_rank IS NOT NULL;

-- Serves: staff list (date + status), dashboard counts, queue ordering/position count.
CREATE INDEX ix_appointment_day_status ON appointment (clinic_date, status);
CREATE INDEX ix_appointment_waiting    ON appointment (clinic_date, queue_rank) WHERE status = 'WAITING';

-- Append-only history. actor is a plain label (no FK) so this file does not depend on Henry's tables.
CREATE TABLE appointment_activity (
    id             bigserial    PRIMARY KEY,
    appointment_id uuid         NOT NULL REFERENCES appointment(id),
    event_type     varchar(30)  NOT NULL
        CHECK (event_type IN ('BOOKED','CHECKED_IN','CONSULTATION_STARTED','COMPLETED','CANCELLED','RESCHEDULED','WALK_IN_ADMITTED')),
    from_status    varchar(20),
    to_status      varchar(20)  NOT NULL,
    actor          varchar(120) NOT NULL,
    actor_role     varchar(20)  NOT NULL CHECK (actor_role IN ('PATIENT','RECEPTIONIST','ADMIN','SYSTEM')),
    request_id     varchar(64),
    occurred_at    timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX ix_activity_appointment ON appointment_activity (appointment_id, occurred_at);
CREATE INDEX ix_activity_recent      ON appointment_activity (occurred_at DESC);

-- Reference data
INSERT INTO visit_type (id, code, label, duration_minutes) VALUES
 (1, 'GENERAL',      'General consultation', 20),
 (2, 'FOLLOW_UP',    'Follow-up visit',      15),
 (3, 'DIAGNOSTIC',   'Diagnostic test',      30),
 (4, 'VACCINATION',  'Vaccination',          10);
