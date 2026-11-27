# ADR-0102: 인증 메일은 같은 transaction의 알림 outbox를 기본으로(연결 반환 뒤 직접 전송은 되돌림 경로), SMTP timeout을 두고, 운영자 조치 감사는 트리거로 한 추가 전용 로그에 모은다

- 상태: Accepted(사용자 승인 전 에이전트 제안; 승인 여부는 확인하지 못함)
- 날짜: 2026-11-27
- 관련 결정: [ADR-0067](ADR-0067-event-log-kafka-relay.md)(예약 이벤트 로그), [ADR-0099](ADR-0099-location-call-bulkhead-opt-in.md)(옵트인 기본값 규칙), D-181
- 근거: [EXP-113](../../03-experiments/EXP-113-account-notification-delivery-and-audit-stream.md), 이슈 [#718](https://github.com/Neo1228/ATLAS/issues/718)

## 문제

이메일 확인·비밀번호 재설정 메일은 계정 transaction이 커밋된 뒤 `afterCommit` 콜백에서 요청 스레드가 SMTP로 보내고, 실패는 WARN 한 줄로 삼킨다. JavaMail timeout 설정이 없어 SMTP가 멈추면 무기한 기다린다. 평문 토큰은 메모리에만 있어 전송이 실패하면 시스템이 같은 메일을 다시 만들 수 없다.

측정(EXP-113)에서 확인한 것: 커밋 직후 `kill -9`에서 커밋된 메일 30/30 유실, SMTP 오류 30초 동안 요청한 메일 60/60 유실(재시도 없음), SMTP 정지 시 메일 요청 10건이 Hikari 연결 10개를 모두 쥐어(Spring `afterCommit`은 연결 반환 전에 실행된다) 무관한 `GET /api/products`까지 10/10 503.

## 결정

1. **기본값: 알림 outbox**. `atlas.account.notification.outbox.enabled`의 기본은 `true`다. 요청 transaction이 `account_notification_outbox`(V9)에 (계정, 목적) 행만 쓰고, 전용 scheduler 스레드의 worker가 행을 하나씩 청구해 토큰을 발급하고 보낸다. 실패는 지수 backoff(5초부터 두 배, 상한 5분)로 재시도하고 10회 뒤 `FAILED`로 남긴다. 같은 (계정, 목적)의 재요청은 대기 행 하나로 합친다. 지표 `atlas.account.notification.*`. 근거는 측정 전 고정한 채택 규칙이다: 직접 전송(A1b)이 유실을 남기면(H19·H20) outbox를 기본으로 둔다. A1b는 C1 60/60, C3 60/60을 잃었다.
2. **되돌림 경로와 SMTP timeout**: 플래그 `false`는 되돌림 스위치다. 이때는 `TransactionTemplate.execute`가 끝나 DB 연결을 돌려준 뒤 같은 요청 스레드에서 보내고 실패는 WARN 한 줄이다(풀 고갈은 없고 유실은 남는다). JavaMail 연결·읽기·쓰기 timeout 5초(`spring.mail.properties`)는 두 경로 모두의 기본값이다.
3. **토큰 저장**: outbox에는 토큰·토큰 해시·암호문·수신 주소를 두지 않는다(참조 방식). worker가 전송 직전에 토큰을 발급하고 해시만 `account_action_token`에 쓴다. 암호화 저장은 키 관리와 "DB 사본 + 키 유출 시 살아 있는 재설정 링크 복원" 위험을 더하고, 참조 방식의 비용(응답 유실 뒤 재전송 시 앞 메일의 토큰 무효)은 사용자가 재요청할 때와 같은 동작이라 받아들인다.
4. **쓰지 않는 것**: `@TransactionalEventListener(AFTER_COMMIT)` + `@Async`는 쓰지 않는다.
5. **통합 감사·도메인 이벤트 로그(항상 켜짐)**: V9의 `domain_audit_event`(추가 전용)에 운영자 조치 6개 테이블의 `AFTER INSERT` 트리거가 같은 transaction에서 한 행씩 쓴다. 원장 분개와 재고 조정·이동은 대상이 아니다. 행위자·집계(대상)·이벤트 종류·`tx_id`를 열로 두고 `payload`에서 자유 문자열 `reason`과 멱등 키·요청 해시는 뺀다. 아래 절 참조.

## 근거 (EXP-113, 단일 호스트, PostgreSQL 18.6, 가짜 SMTP)

| 칸 | A0 현행 | A1 timeout | A1b timeout + 연결 반환 뒤 전송 | A2 outbox | A3 AFTER_COMMIT + @Async |
|---|---|---|---|---|---|
| C1 커밋 직후 종료, 유실/커밋 | 30/30 | 30/30 | 60/60 | 0/60 | 60/60 |
| C2 SMTP 정지, 무관 탐침 성공(3 run 합) | 0/30 | 18/30 | 30/30 | 30/30 | 30/30 |
| C2 가입 503(3 run 합) | 114 | 114 | 0 | 0 | 0 |
| C2 SMTP에 묶인 스레드(10초 시점) | Tomcat 10 | 0(5초에 풀림) | Tomcat 8 | worker 1 | 새 스레드 48 |
| C3 SMTP 오류 뒤 전달 | 0/60 | 0/60 | 0/60 | 60/60(복구 뒤 ≤ 7.2초) | 0/60 |
| C4 정상, 요청 p95 중앙값 | 114.3 ms | 101.9 ms | 59.2 ms | 76.2 ms | 112.6 ms |
| C4 전달 p95 중앙값 | 108.9 ms | 100.4 ms | 57.2 ms | 2405.9 ms | 130.4 ms |
| C5 응답 유실 뒤 종료, 중복 | - | - | - | run마다 1(최신 토큰만 유효) | - |

n = 3 run, 단일 호스트, 잠금 아래. 시간 값은 맥락용이고 결론은 건수로 낸다.

## 대안과 기각 사유

- 현행 유지(A0): 커밋 직후 종료·SMTP 오류에서 메일을 잃고, SMTP 정지가 DB 연결 풀을 고갈시켜 무관한 API까지 503이 된다(EXP-113 C1·C2·C3).
- timeout만(A1): 무한 대기는 끝나지만 전송 동안 DB 연결을 쥐어 SMTP 정지 동안 풀 고갈이 계속되고, 유실은 그대로다.
- Spring 내부 이벤트(A3): 요청 지연은 줄지만 이벤트가 메모리에만 있어 프로세스 종료 시 잃고 재시도도 없다. 실행기 설정을 따로 두지 않으면 전송마다 새 스레드를 만든다(EXP-113 C2 스레드 덤프).
- 암호화 payload outbox: 위 3번.
- 외부 메일 큐·브로커: 인프라 추가, 이번 측정에서 필요가 드러나지 않았다.
- outbox를 옵트인으로 두고 직접 전송을 기본으로(이 PR의 이전 안): 직접 전송은 풀 고갈을 고쳤지만(C2 503 0) 커밋 직후 종료·SMTP 오류에서 120/120을 잃었다. outbox의 비용(전달 p95 약 2.4초, 응답 유실 뒤 종료 시 중복 1통)이 유실보다 작다고 보고 기본을 바꿨다. 다중 인스턴스 worker 경합과 실제 메일 제공자에서의 지연은 재지 않았다.

## 감사·분석 스트림 (리뷰 3.3)

- 요구 출처(✓ 확정, 사용자 승인): 2026-11-27 사용자 지시 "감사 도메인 이벤트 로그는 필요해 도입해도 괜찮아". 저장소 문서에는 교차 조회 요구가 없었다(EXP-113 H11). 도입 자체는 이 지시로 확정이고, 아래 설계 세부(대상 범위, 트리거 방식, payload 제외 열, 색인)는 ✓ 결정(가정)이며 재검토 조건을 따른다.
- 원장 제외: `settlement_journal`은 V7로 이미 추가 전용이고 돈의 정본이다. 이 로그가 답할 질문(운영자별 조치, 대상별 조치 이력)에는 분개 행이 필요 없으므로 두 번째 사본을 만들지 않는다. 운영자 조치가 낳은 지급 분개는 `payout` 집계 ID로 원장에서 찾는다.
- 재고 제외: 판매자 재고 조정 쓰기(`CapacityAdjustmentRepository.record`의 SQL)에서 트리거가 p99를 +44.8%(c = 1), p50을 +48.7%·p99를 +136.3%(c = 8) 늘렸고 `(tx_id)` 색인을 빼도 c = 1 p50 +49.0%로 사전 등록 임계(p50 ≤ +25%·+0.3 ms, p99 ≤ +25%)를 넘었다(EXP-113 H21). 그래서 `inventory_capacity_adjustment`·`inventory_transfer`는 로그에 넣지 않고 원본 테이블에서 읽는다. 남은 6개는 운영자 예외 조치라 요청 경로의 쓰기가 아니다(같은 함수의 행당 비용은 같으므로 드문 쓰기라는 점이 근거다).
- 방식: 원본 테이블의 `AFTER INSERT` 트리거(`record_domain_audit_event`). 쓰기 코드를 고치지 않으므로 새 쓰기 경로도 빠지지 않고, 원본 쓰기가 롤백되면 로그도 없다. 앱 코드로 기록하는 안은 쓰기 경로마다 수정과 모듈 공용 기록기가 필요해 기각했고, 기존 `order_inventory_event`는 예약 workflow 전용 열 구조라 재사용하지 않았다. CDC·Kafka는 인프라 추가라 기각했다. `tx_id`가 있어 ADR-0067의 xmin 안전 cursor로 소비자를 붙일 수 있다(소비자는 이번 범위 밖).
- 한계: 행위자 열이 없는 원본이 대상에 없으므로 `actor`는 원본에 행위자가 있는 행만 채워진다. 원본 테이블 일부는 UPDATE·DELETE 가드가 없어(후속 [#720](https://github.com/Neo1228/ATLAS/issues/720)) 원본이 바뀌어도 로그는 처음 값을 유지한다. 로그 INSERT가 실패하면 원본 쓰기도 실패한다.
- 근거 수치: EXP-113 H14~H18, H21.

## 롤백

- SMTP timeout: `ATLAS_SMTP_*_TIMEOUT_MS`로 값을 바꾼다. 무한 대기로 되돌리는 것은 권하지 않는다.
- 연결 반환 뒤 전송: 되돌림 경로의 코드라 그대로 둔다. 데이터 변화가 없다.
- outbox: `ATLAS_ACCOUNT_NOTIFICATION_OUTBOX_ENABLED=false` 후 재시작하면 연결 반환 뒤 직접 전송으로 돌아가고 남은 행은 보내지 않고 남는다(이 경로는 종료·SMTP 오류에서 메일을 잃는다). 테이블은 남긴다. V9에는 감사 로그도 있어 `flyway_schema_history`의 V9 행을 지우면 다음 기동에서 V9가 다시 실행돼 실패한다.
- 감사 로그: 트리거만 끄려면 `DROP TRIGGER <table>_audit ON <table>;`(6개), 전부 없애려면 이어서 `DROP TABLE domain_audit_event; DROP FUNCTION record_domain_audit_event(); DROP FUNCTION reject_domain_audit_event_mutation();`. 원본 테이블은 그대로다.
- V9는 V8(#717)에 기대지 않는다. 새 테이블 둘과 새 트리거라 V8 앞뒤 어느 순서로 적용돼도 결과가 같다. 다만 Flyway 순서 검증 때문에 V8 병합 뒤에 병합한다.

## 재검토 조건

- 알림: `atlas.account.notification.failed > 0`이 지속되면 SMTP 제공자·backoff 상한을 다시 본다. worker 재시작 뒤 중복 메일 문의가 생기거나 `atlas.account.notification.delivery.delay` p95가 사용자 흐름 한도를 넘으면 폴링 주기·worker 병렬도를 먼저 보고, 그래도 안 되면 플래그를 끈다(유실을 받아들이는 결정이므로 기록한다).
- 알림: 메일 외 알림 채널(SMS, 푸시)이 생기면 같은 outbox에 채널 열을 더할지, 채널별 테이블을 둘지 다시 정한다.
- 감사: 로그 행이 하루 100만 건을 넘거나 표 크기가 10 GB를 넘으면 월 단위 분할과 보존 기간을 정한다. 외부 감사·분석 소비자가 생기면(리뷰 T3) `tx_id` cursor 소비자나 Kafka relay(ADR-0067) 확장을 붙인다. 새 감사 테이블을 만들 때는 `record_domain_audit_event`에 매핑을 더하고 시험의 대상 목록을 고친다. 요청마다 쓰는 테이블(재고 조정 등)을 넣으려면 H21과 같은 방법으로 쓰기 비용을 다시 재고 임계 안일 때만 넣는다.
