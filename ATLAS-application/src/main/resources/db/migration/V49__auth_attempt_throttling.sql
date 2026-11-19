-- Authentication throttling. Two fixed-window counters so neither table grows without bound:
-- one row per subject (normalized email), one row per client origin, each reused when its
-- window rolls over.
CREATE TABLE auth_subject_bucket (
    subject_key CHAR(64) NOT NULL,
    scope VARCHAR(24) NOT NULL,
    window_started_at TIMESTAMPTZ NOT NULL,
    failures INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT auth_subject_bucket_pk PRIMARY KEY (subject_key, scope),
    CONSTRAINT auth_subject_bucket_scope_check CHECK (scope IN ('LOGIN', 'MFA')),
    CONSTRAINT auth_subject_bucket_failures_nonnegative CHECK (failures >= 0)
);

CREATE TABLE auth_origin_bucket (
    origin_key CHAR(64) NOT NULL,
    scope VARCHAR(24) NOT NULL,
    window_started_at TIMESTAMPTZ NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT auth_origin_bucket_pk PRIMARY KEY (origin_key, scope),
    CONSTRAINT auth_origin_bucket_scope_check CHECK (scope IN ('LOGIN', 'EMAIL')),
    CONSTRAINT auth_origin_bucket_attempts_nonnegative CHECK (attempts >= 0)
);

-- Counters expire from the API, but stale rows must not be kept forever. The sweeper index
-- lets an occasional bounded delete reclaim rows whose window closed long ago.
CREATE INDEX ix_auth_subject_bucket_stale ON auth_subject_bucket (updated_at);
CREATE INDEX ix_auth_origin_bucket_stale ON auth_origin_bucket (updated_at);
