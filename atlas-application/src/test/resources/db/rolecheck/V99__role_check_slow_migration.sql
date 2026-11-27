-- EXP-111: 데이터소스 URL의 statement_timeout(1.5초)보다 긴 마이그레이션이 Flyway 전용 연결에서는 취소되지 않는지 확인한다.
-- SettlementLedgerRoleSeparationIntegrationTest 만 이 위치를 연다.
SELECT pg_sleep(3);
