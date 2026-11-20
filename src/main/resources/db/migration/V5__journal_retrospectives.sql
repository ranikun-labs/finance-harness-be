CREATE TABLE journal_retrospectives (
    id UUID PRIMARY KEY,
    journal_id UUID NOT NULL REFERENCES journals (id) ON DELETE CASCADE,
    source_revision BIGINT NOT NULL CHECK (source_revision BETWEEN 0 AND 9007199254740991),
    idempotency_key VARCHAR(128) NOT NULL,
    body TEXT NOT NULL CHECK (char_length(body) BETWEEN 1 AND 4000),
    outcome_observation TEXT CHECK (char_length(outcome_observation) <= 2000),
    decision_quality TEXT CHECK (char_length(decision_quality) <= 2000),
    next_check TEXT CHECK (char_length(next_check) <= 2000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (journal_id, idempotency_key)
);

CREATE INDEX ix_journal_retrospectives_recent
    ON journal_retrospectives (journal_id, created_at DESC, id DESC);
