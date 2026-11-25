# ADR-0087: 소비자는 aggregateVersion으로 상태를 올리고 (workflowId, version)을 한 번만 적용한다

- 상태: Accepted(문서화, 테스트 전용 참조 소비자로 시험)
- 날짜: 2026-11-25
- 관련 결정: [ADR-0067](ADR-0067-event-log-kafka-relay.md), [ADR-0076](ADR-0076-reference-consumer-in-tests-only.md), [ADR-0086](ADR-0086-relay-chunked-send-for-first-delivery-order.md)
- 근거: [EXP-091](../../03-experiments/EXP-091-kafka-first-delivery-order.md), [EXP-082](../../03-experiments/EXP-082-event-consumer-contract.md), [EXP-089](../../03-experiments/EXP-089-kafka-outage-repeated.md)

## 문제

토픽을 읽는 소비자는 relay가 순서를 보장한다고 가정해도 되는가? [EXP-091](../../03-experiments/EXP-091-kafka-first-delivery-order.md)은 broker pause 뒤 높은 버전이 낮은 버전보다 먼저 첫 전달될 수 있고(수정 전 약 7.6%의 시행-단계), 수정([ADR-0086](ADR-0086-relay-chunked-send-for-first-delivery-order.md)) 뒤에도 재전달(낮은 버전이 높은 버전 뒤에 다시 옴), 재시작, 같은 그룹 리밸런스는 남는다는 것을 보였다. 소비자가 지켜야 할 것을 정한다.

## 결정 (소비자가 반드시 해야 하는 것)

1. 이벤트의 식별자는 `(workflowId, aggregateVersion)`(`event-id` header)이다. 같은 식별자는 한 번만 적용한다(적용 표시를 PostgreSQL 같은 소비자 저장소에 둔다).
2. 소비자가 가진 workflow 상태는 도착 순서가 아니라 `aggregateVersion`이 **더 높을 때만** 바꾼다. 이미 더 높은 버전이 반영됐다면 낮은 버전은 상태를 바꾸지 않고, 부수 효과(알림, 외부 호출, 집계)를 새로 만들지 않는다. 도착 순서(offset)로 "마지막 이벤트가 최신"이라고 가정하지 않는다.
3. 적용 표시, 상태 갱신, 부수 효과 기록은 한 트랜잭션이다. offset commit은 그 뒤에 따로 한다(at-least-once). 그러면 인스턴스가 죽어도, 그룹이 리밸런스해도 마지막 commit 이후가 다시 전달되어 한 번만 적용된다.
4. 효과가 소비자 DB 밖(HTTP 호출 등)이면 1~3이 성립하지 않는다. 그 경우 외부 쪽 멱등 키로 `event-id`를 써야 하며 이 ADR의 시험은 그 경우를 다루지 않는다.

## 근거

[EXP-091](../../03-experiments/EXP-091-kafka-first-delivery-order.md) 9절: 참조 소비자(테스트 소스)를 확장해 상태를 높은 버전으로만 올리게 하고, 합성 토픽(파티션 3, 홀수 workflow는 v2를 v1보다 먼저 싣고 일부는 통째로 한 번 더 싣음)에서 같은 그룹의 소비자 둘 중 하나가 그룹을 떠나지 않은 채 죽어 세션 만료로 파티션이 넘어가도록 했다. 고친 시험 7회 연속 통과: 최종 상태가 모든 workflow에서 v2, 효과가 `(workflowId, version)`마다 정확히 한 번, 낮은 버전 효과 없음. 변이(조건 `<` 제거)는 시험이 잡는다.

## 가정과 한계

- 가정: 소비자 저장소가 PostgreSQL이고 한 트랜잭션을 쓸 수 있다.
- 한계: 소비자 2개, 파티션 3개, crash 1회, 세션 6초. 이벤트 60건대. 부하, 대량 리밸런스, 긴 중단은 보지 않았다. 운영 소비자는 만들지 않았다([ADR-0076](ADR-0076-reference-consumer-in-tests-only.md)).

## 대안과 버린 이유

| 대안 | 버린 이유 |
| --- | --- |
| relay가 순서를 완전히 보장한다고 문서화 | 재전달(낮은 버전이 높은 버전 뒤에 다시 옴)은 at-least-once에서 남는다. 첫 전달만 보장한다 |
| 소비자가 중복 제거만 하고 상태는 도착 순서로 갱신 | EXP-091 수정 전 관측(높은 버전이 먼저)과 재전달에서 상태가 거꾸로 간다 |

## 실패 경로와 되돌리기

문서 결정이다. 참조 소비자는 테스트 소스이므로 되돌리는 것은 이 PR의 revert뿐이다.
