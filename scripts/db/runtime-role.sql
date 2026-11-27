-- 런타임 DB 역할 설정(옵트인). 소유 계정(Flyway 계정)으로 마이그레이션 적용 뒤에 실행하고, 같은 파일을 다시
-- 실행해도 결과가 같다(멱등).
--
--   psql -v ON_ERROR_STOP=1 -v runtime_role=atlas_runtime -v runtime_password=<비밀번호> \
--        -h <host> -U <소유 계정> -d atlas -f scripts/db/runtime-role.sql
--
-- 결과: 런타임 역할은 SELECT·INSERT·UPDATE·DELETE 를 받지만 추가 전용 테이블(원장 3개 settlement_journal,
-- settlement_entry, settlement_seller_allocation과 감사·이벤트 12개, 목록은 아래 REVOKE)에서는 UPDATE·DELETE 를
-- 받지 못하고 TRUNCATE 는 어느 테이블에서도 받지 않는다. 비소유 역할이라 트리거 비활성화, 제약 변경,
-- session_replication_role 설정도 못 한다. 감사·이벤트 12개는 운영 코드의 UPDATE·DELETE 사용처가 0건이다(EXP-116).
-- Flyway 이력 테이블도 회수한다. 앱은 SPRING_DATASOURCE_* 를 이 역할로, SPRING_FLYWAY_URL/USER/PASSWORD 를
-- 소유 계정으로 준다(EXP-111).
-- 소유 계정이 만든 새 테이블은 기본 권한으로 DML 이 자동 부여된다. 그래서 첫 기동(마이그레이션) 뒤에 한 번 더
-- 실행해야 회수가 적용된다. 추가 전용인 새 테이블은 이 파일의 REVOKE 목록에 넣고 다시 실행한다.

SELECT format('CREATE ROLE %I LOGIN PASSWORD %L NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT',
              :'runtime_role', :'runtime_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'runtime_role') \gexec

SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'runtime_role') \gexec

GRANT USAGE ON SCHEMA public TO :"runtime_role";
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO :"runtime_role";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"runtime_role";

-- 테이블이 아직 없으면(첫 기동 전) 건너뛰므로 마이그레이션 전에도, 뒤에도 실행할 수 있다.
SELECT format('REVOKE UPDATE, DELETE ON %I FROM %I', t, :'runtime_role')
FROM unnest(ARRAY['settlement_journal', 'settlement_entry', 'settlement_seller_allocation',
                  'domain_audit_event', 'order_inventory_event',
                  'inventory_capacity_adjustment', 'inventory_transfer',
                  'account_suspension_action', 'payment_recovery_operator_action',
                  'paypal_webhook_retry_operator_audit', 'seller_payout_profile_action',
                  'settlement_payout_operator_action', 'paypal_balance_report_run',
                  'paypal_balance_report_row', 'paypal_balance_report_disposition']) AS t
WHERE to_regclass(t) IS NOT NULL \gexec
REVOKE TRUNCATE ON ALL TABLES IN SCHEMA public FROM :"runtime_role";
SELECT format('REVOKE ALL ON flyway_schema_history FROM %I', :'runtime_role')
WHERE to_regclass('flyway_schema_history') IS NOT NULL \gexec
