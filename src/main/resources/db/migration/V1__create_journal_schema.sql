CREATE TABLE journals (
    id UUID NOT NULL,
    identity_user_id TEXT NOT NULL,
    type TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    occurred_local_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    occurred_time_zone TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_journals PRIMARY KEY (id),
    CONSTRAINT uq_journals_id_type UNIQUE (id, type),
    CONSTRAINT ck_journals_identity_user_id_nonblank CHECK (length(btrim(identity_user_id)) > 0),
    CONSTRAINT ck_journals_type CHECK (type IN ('investment', 'study')),
    CONSTRAINT ck_journals_time_zone_nonblank CHECK (length(btrim(occurred_time_zone)) > 0)
);

CREATE TABLE investment_journals (
    journal_id UUID NOT NULL,
    journal_type TEXT NOT NULL,
    asset_name TEXT NOT NULL,
    action TEXT NOT NULL,
    reasoning TEXT NOT NULL,
    emotion TEXT,
    CONSTRAINT pk_investment_journals PRIMARY KEY (journal_id),
    CONSTRAINT fk_investment_journals_journal
        FOREIGN KEY (journal_id, journal_type)
        REFERENCES journals (id, type)
        ON DELETE CASCADE,
    CONSTRAINT ck_investment_journals_type CHECK (journal_type = 'investment'),
    CONSTRAINT ck_investment_journals_asset_name CHECK (
        length(btrim(asset_name)) > 0 AND char_length(asset_name) <= 120
    ),
    CONSTRAINT ck_investment_journals_action CHECK (action IN ('interest', 'watching', 'buy', 'sell')),
    CONSTRAINT ck_investment_journals_reasoning CHECK (
        length(btrim(reasoning)) > 0 AND char_length(reasoning) <= 4000
    ),
    CONSTRAINT ck_investment_journals_emotion CHECK (
        emotion IS NULL OR emotion IN ('FOMO', '불안', '확신', '관망', '혼란')
    )
);

CREATE TABLE study_journals (
    journal_id UUID NOT NULL,
    journal_type TEXT NOT NULL,
    title TEXT NOT NULL,
    key_content TEXT NOT NULL,
    CONSTRAINT pk_study_journals PRIMARY KEY (journal_id),
    CONSTRAINT fk_study_journals_journal
        FOREIGN KEY (journal_id, journal_type)
        REFERENCES journals (id, type)
        ON DELETE CASCADE,
    CONSTRAINT ck_study_journals_type CHECK (journal_type = 'study'),
    CONSTRAINT ck_study_journals_title CHECK (
        length(btrim(title)) > 0 AND char_length(title) <= 120
    ),
    CONSTRAINT ck_study_journals_key_content CHECK (
        length(btrim(key_content)) > 0 AND char_length(key_content) <= 6000
    )
);

CREATE TABLE study_open_questions (
    study_journal_id UUID NOT NULL,
    position INTEGER NOT NULL,
    question TEXT NOT NULL,
    CONSTRAINT pk_study_open_questions PRIMARY KEY (study_journal_id, position),
    CONSTRAINT fk_study_open_questions_study
        FOREIGN KEY (study_journal_id)
        REFERENCES study_journals (journal_id)
        ON DELETE CASCADE,
    CONSTRAINT ck_study_open_questions_position CHECK (position >= 0),
    CONSTRAINT ck_study_open_questions_question CHECK (
        length(btrim(question)) > 0 AND char_length(question) <= 500
    )
);

CREATE INDEX ix_journals_owner_occurred_at
    ON journals (identity_user_id, occurred_at DESC, id DESC);
