-- V5의 되돌림 SQL(EXP-096 "되돌리기 훈련"). 새로 만든 것만 지우므로 기존 표·행은 그대로다.
DROP INDEX uq_settlement_payout_operator_action_approved;
DROP TRIGGER settlement_payout_operator_action_separation ON settlement_payout_operator_action;
DROP FUNCTION enforce_payout_operator_separation();
DROP TRIGGER seller_payout_profile_action_separation ON seller_payout_profile_action;
DROP FUNCTION enforce_profile_verifier_is_not_recipient();
DROP TABLE payout_operator_account;
