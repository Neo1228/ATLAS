# EXP-046: Prometheus 스크레이프가 유발하던 전체 스캔 제거

**추적 이슈:** [#434](https://github.com/Neo1228/ATLAS/issues/434)

**상태:** 실측 + 코드 감사 · 로컬, PostgreSQL 18.6

## 왜 이 실험을 했는가

"인프라 부하"를 찾던 중, **관측 경로가 스스로 부하를 만드는** 구조를 발견했다. 사용자 트래픽과 무관하게, 스크레이프 빈도가 곧 DB 부하가 되는 구조다.

## 발견

`PaymentRecoveryWorker` 생성자에서 Gauge 를 등록한다.

```java
for (PaymentRecoveryJobStatus status : values()) {
    Gauge.builder("atlas.payment.recovery.jobs", recovery, r -> r.count(status)) ...
}
for (PaymentRecoveryWorkStage stage : values()) {
    for (PaymentRecoveryJobStatus status : values()) {
        Gauge.builder("...stage.jobs", recovery, s -> s.count(status, stage)) ...
    }
    Gauge.builder("...stage.oldest_due_age_seconds", recovery, s -> s.oldestDueAgeSeconds(stage)) ...
}
Gauge.builder("...oldest_due_age_seconds", recovery, PaymentRecoveryService::oldestDueAgeSeconds) ...
```

**Micrometer Gauge 는 스크레이프마다 값을 읽는다.**

| Gauge 종류 | 개수 | 각각 실행하는 쿼리 |
|---|---:|---|
| 상태별 건수 | 4 | `COUNT(*) WHERE status = ?` |
| stage×상태 건수 | 8 | `COUNT(*) WHERE status = ? AND work_stage = ?` |
| stage별 최고 나이 | 3 | `MIN(available_at)` |
| 전체 최고 나이 | 1 | `MIN(available_at)` |
| **합계** | **16** | **스크레이프당 16 쿼리** |

## 왜 심각한가

세 가지가 겹친다.

1. **`payment_recovery_job` 은 append-only 다.** `markApplied` 는 `status = 'COMPLETED'` 로 바꿀 뿐 행을 지우지 않는다. 테이블은 성공한 결제 수만큼 영원히 커진다.
2. **`COMPLETED` 와 `work_stage` 조합에는 인덱스가 없다.** `V38` 은 `PENDING` due job 과 `NEEDS_REVIEW` 만 인덱싱한다. 따라서 16개 중 상당수가 순차 스캔이다.
3. **`/actuator/prometheus` 는 `permitAll` 이다.** `ApiSecurityConfiguration` 이 인증 없이 노출한다. 누구나 스크레이프 빈도를 조절해 DB 부하를 유발할 수 있다.

즉 **업무가 성공할수록 관측 비용이 커지고, 그 비용을 외부에서 임의로 증폭할 수 있는** 구조였다.

## 수정

### 1) 단일 집계 쿼리

`PaymentRecoveryRepository.metricsSnapshot()` 이 두 쿼리로 전부를 채운다.

```sql
SELECT status, COUNT(*) FROM payment_recovery_job GROUP BY status;

SELECT work_stage, status, COUNT(*),
       COALESCE(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP - MIN(
           CASE WHEN available_at <= CURRENT_TIMESTAMP THEN available_at END)), 0)
FROM payment_recovery_job
GROUP BY work_stage, status;
```

**16 쿼리 → 2 쿼리.** 상태·stage 조합 수와 무관하다.

### 2) TTL 캐시

`PaymentRecoveryServiceImpl.metricsSnapshot()` 이 5초 TTL 로 스냅샷을 캐시한다. 여러 스크레이퍼가 겹쳐도, 외부에서 스크레이프를 폭주시켜도 **DB 는 5초에 한 번만** 읽는다.

Prometheus 기본 스크레이프 주기(15초)보다 짧게 잡아 신선도를 유지했다.

### 3) Gauge 바인딩

Gauge 는 이제 `PaymentRecoveryService` 를 참조하고, 값 읽기는 캐시를 통과한다.

## 결과

| 항목 | 수정 전 | 수정 후 |
|---|---:|---:|
| 스크레이프당 쿼리 | 16 | **0 (캐시 적중)** |
| 5초 창 최대 쿼리 | 16 × 스크레이프 수 | **2** |
| 외부 스크레이프 폭주 영향 | DB 부하에 비례 | **5초당 2회로 고정** |

## 한계 (정직하게)

1. **실측 스크레이프 부하를 재지 않았다.** 이 문서의 근거는 코드 감사와 쿼리 수 계산이다. `payment_recovery_job` 에 대량 행을 넣고 스크레이프 지연을 측정하는 것이 다음 단계다.
2. **5초 staleness 를 도입했다.** 운영 대시보드가 최대 5초 낡은 값을 본다. 알람 임계값 판정에는 영향이 없지만, 즉시성이 필요한 조회는 별도 경로를 써야 한다.
3. **`/actuator/prometheus` 는 여전히 비인증이다.** 관측 스크레이퍼와의 계약이 있어 이번에 바꾸지 않았다. 다만 캐시 덕에 스크레이프 폭주가 DB 로 증폭되지는 않는다. 인증·네트워크 제한은 별도 보안 작업이다.
4. **`COMPLETED` 인덱스는 추가하지 않았다.** 단일 GROUP BY 는 여전히 스캔이지만, 5초당 1회로 빈도가 고정됐다. 테이블이 수백만 행이 되면 부분 인덱스나 카운터 테이블이 필요하다.

## 후속 후보

- `payment_recovery_job` 의 완료 행 아카이빙 또는 `(status, work_stage)` 부분 인덱스
- `/actuator/prometheus` 인증 또는 내부 네트워크 제한
