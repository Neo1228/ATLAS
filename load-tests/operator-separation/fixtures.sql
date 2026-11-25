-- EXP-096 고정 데이터. 실제 V1 표에 프로필·지급·outbox를 한 벌 만든다(시나리오와 되돌림 훈련이 함께 쓴다).
-- 고정 데이터: 프로필 v3(ACTIVE, 현재 행 verified_by = current_verifier), v3의 PROFILE_VERIFIED 행(actor = bound_verifier),
-- 그 버전에 묶인 outbox가 있는 지급.
CREATE OR REPLACE FUNCTION exp_seed(bound_verifier text, current_verifier text, OUT seller uuid, OUT payout uuid)
LANGUAGE plpgsql AS $$
DECLARE
    account uuid := gen_random_uuid();
BEGIN
    seller := gen_random_uuid();
    payout := gen_random_uuid();
    INSERT INTO seller(seller_id) VALUES (seller);
    INSERT INTO user_account(account_id, email, password_hash, email_verified_at)
        VALUES (account, account || '@exp.test', 'h', now());
    INSERT INTO seller_account(account_id, seller_id) VALUES (account, seller);
    INSERT INTO seller_payout_profile(seller_id, recipient_account_id, country_code, payout_currency, status,
        profile_version, created_at, updated_at, verified_at, verified_by)
        VALUES (seller, account, 'US', 'USD', 'ACTIVE', 3, now(), now(), now(), current_verifier);
    INSERT INTO seller_payout_profile_action(seller_id, profile_version, action, idempotency_key,
        request_facts_sha256, actor, reason, created_at)
        VALUES (seller, 3, 'PROFILE_VERIFIED', 'v3-' || seller, repeat('0', 64), bound_verifier, 'case', now());
    INSERT INTO settlement_payout(payout_id, seller_id, period_start, period_end, amount_won, state,
        idempotency_key, requested_at, updated_at)
        VALUES (payout, seller, now() - interval '1 day', now() + interval '1 day', 1000, 'APPROVED',
        'exp-' || payout, now(), now());
    INSERT INTO settlement_payout_dispatch_outbox(payout_id, profile_version, recipient_email_ciphertext,
        recipient_country_code, requested_amount_won, provider_amount_minor, fx_rate_won_per_usd, fx_source,
        fx_as_of_date, provider_debit_book_value_won, payout_fx_rounding_expense_won, sender_batch_id,
        sender_item_id, paypal_request_id, request_payload_sha256, status, available_at, first_enqueued_at,
        created_at, updated_at)
        VALUES (payout, 3, 'x', 'US', 1000, 100, 1000, 'exp', current_date, 1000, 0, 'b-' || payout,
        'i-' || payout, 'r-' || payout, repeat('a', 64), 'PENDING', now(), now(), now(), now());
END $$;
