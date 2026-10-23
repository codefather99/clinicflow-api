-- =====================================================================
-- V100__api_support.sql          OWNER: Henry (API, auth and pipeline)
-- Flyway number blocks: Deji uses V1..V99, Henry uses V100..V199.
-- Set spring.flyway.out-of-order=true so the two streams can interleave.
-- Nothing here references Deji's tables, so neither stream blocks the other.
-- =====================================================================

CREATE TABLE staff_user (
    id            uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    email         varchar(160) NOT NULL UNIQUE,
    password_hash varchar(100) NOT NULL,              -- bcrypt
    display_name  varchar(120) NOT NULL,
    role          varchar(20)  NOT NULL CHECK (role IN ('RECEPTIONIST','ADMIN')),
    active        boolean      NOT NULL DEFAULT true,
    created_at    timestamptz  NOT NULL DEFAULT now()
);

-- Makes POST /patient/appointments (and POST /walk-ins) safe to submit twice.
-- The primary key is the guard: two concurrent inserts of the same key cannot both win.
CREATE TABLE idempotency_key (
    key           varchar(64)  NOT NULL,
    scope         varchar(120) NOT NULL,              -- e.g. 'POST /api/v1/patient/appointments'
    request_hash  char(64)     NOT NULL,              -- sha-256 of the request body
    status_code   integer,                            -- NULL while the first request is still in flight
    response_body jsonb,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (key, scope)
);
CREATE INDEX ix_idempotency_created ON idempotency_key (created_at);
