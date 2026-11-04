CREATE TABLE settlement_journal (
    journal_id UUID PRIMARY KEY DEFAULT uuidv7(),
    source_order_id UUID NOT NULL,
    source_attempt_id UUID NOT NULL UNIQUE,
    seller_id UUID NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'KRW',
    gross_amount_won BIGINT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT settlement_journal_amount_positive CHECK (gross_amount_won > 0),
    CONSTRAINT settlement_journal_krw_only CHECK (currency = 'KRW')
);

CREATE TABLE settlement_entry (
    entry_id UUID PRIMARY KEY DEFAULT uuidv7(),
    journal_id UUID NOT NULL REFERENCES settlement_journal (journal_id),
    account_code VARCHAR(32) NOT NULL,
    entry_side VARCHAR(6) NOT NULL,
    amount_won BIGINT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT settlement_entry_account_side CHECK (
        (account_code = 'PSP_CLEARING' AND entry_side = 'DEBIT')
        OR (account_code = 'SELLER_PAYABLE' AND entry_side = 'CREDIT')
    ),
    CONSTRAINT settlement_entry_amount_positive CHECK (amount_won > 0),
    CONSTRAINT settlement_entry_account_once UNIQUE (journal_id, account_code)
);
