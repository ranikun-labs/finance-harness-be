-- Finance owns onboarding state. A row does not grant access or record consent.
CREATE TABLE finance_membership_onboarding (
    account_id UUID PRIMARY KEY,
    onboarding_status VARCHAR(32) NOT NULL CHECK (onboarding_status IN ('PENDING')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
