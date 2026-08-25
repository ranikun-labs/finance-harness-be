CREATE TABLE journal_idempotency_records (
    id BIGINT GENERATED ALWAYS AS IDENTITY,
    identity_user_id TEXT NOT NULL,
    operation TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    request_fingerprint TEXT NOT NULL,
    journal_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_journal_idempotency_records PRIMARY KEY (id),
    CONSTRAINT uq_journal_idempotency_records_namespace
        UNIQUE (identity_user_id, operation, idempotency_key),
    CONSTRAINT uq_journal_idempotency_records_journal_id UNIQUE (journal_id),
    CONSTRAINT fk_journal_idempotency_records_journal
        FOREIGN KEY (journal_id)
        REFERENCES journals (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_journal_idempotency_records_identity_user_id_nonblank
        CHECK (length(btrim(identity_user_id)) > 0),
    CONSTRAINT ck_journal_idempotency_records_operation
        CHECK (operation = 'journal.create.v1'),
    CONSTRAINT ck_journal_idempotency_records_idempotency_key
        CHECK (
            char_length(idempotency_key) BETWEEN 1 AND 128
            AND idempotency_key ~ '^[A-Za-z0-9][A-Za-z0-9._~-]{0,127}$'
        ),
    CONSTRAINT ck_journal_idempotency_records_request_fingerprint
        CHECK (request_fingerprint ~ '^v1:sha256:[0-9a-f]{64}$')
);
