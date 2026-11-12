# ADR-0030: 애플리케이션 스케줄러 동시성 상한

- 상태: ✓ 확정
- 결정일: 2026-11-11

## 배경

`AtlasApplication`은 Spring 스케줄링을 활성화하며, `AdmissionServiceImpl`은 만료 작업을 주기적으로 실행합니다(`atlas.admission.expiry-poll-interval`, 기본값 `PT1S`). 애플리케이션에서 `TaskScheduler` bean을 정의하지 않았습니다. Spring의 `ThreadPoolTaskScheduler` 기본 스레드 수는 1이므로, 별도 설정이 없으면 스케줄된 작업들이 한 스레드를 공유합니다.

- Spring Framework [ThreadPoolTaskScheduler Javadoc](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/scheduling/concurrent/ThreadPoolTaskScheduler.html): 기본 pool size는 1.
- Spring Boot [스케줄링 문서](https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html): 기본 scheduler와 `spring.task.scheduling.pool.size` 설정.

보고 전용 측정 이슈 [#95](https://github.com/Neo1228/ATLAS/issues/95)는 PostgreSQL 환경에서 만료 주문 300건 sweep의 중앙값을 약 5.225초, admission 티켓 300건 sweep을 약 20.1ms로 기록했습니다. 이 수치는 로컬 측정값이지 운영 지연 목표나 운영 처리량 추정치가 아닙니다. 다만 긴 만료 작업 하나가 1초 간격의 독립 작업을 지연시킬 수 있음을 보여주는 근거입니다.

## 결정

애플리케이션 `taskScheduler` bean으로 `ThreadPoolTaskScheduler`를 정의하고 스레드 3개와 식별 가능한 스레드 이름 접두사를 사용합니다. 현재 애플리케이션에는 서로 독립적인 fixed-delay 만료 작업이 3개 있습니다. 한 작업이 지연돼도 나머지 작업이 진행될 수 있도록 하되, 개별 만료 로직과 1초 polling 간격은 변경하지 않습니다.

## 검증

[`SchedulingConcurrencyTest`](../../../ATLAS-application/src/test/java/com/atlas/application/config/SchedulingConcurrencyTest.java): 설정 pool 3과 테스트용 pool 1/3의 latch 실행 비교. 동시 실행·대기 동작만 검증하며 처리량 benchmark는 아님. 500 ms는 동기화 대기 구간이지 SLO가 아님.

- 구현: [SchedulingConfiguration](../../../ATLAS-application/src/main/java/com/atlas/application/config/SchedulingConfiguration.java) · [AdmissionServiceImpl](../../../ATLAS-admission/src/main/java/com/atlas/admission/service/impl/AdmissionServiceImpl.java)
- 테스트: [SchedulingConfigurationTest](../../../ATLAS-application/src/test/java/com/atlas/application/config/SchedulingConfigurationTest.java)

## 결과와 한계

- 스레드 3개는 현재 독립 스케줄 작업의 수를 근거로 한 상한이지, 부하 측정으로 도출한 최적 처리량이 아닙니다. 작업 수가 바뀌거나 운영 scheduler 지연 및 DB pool 측정 결과가 달라지면 재검토합니다.
- `ThreadPoolTaskScheduler`의 작업 대기열 크기는 제한하지 않습니다. 이 설정은 일반 작업 제출용 bounded queue가 아닙니다.
- 한 만료 작업 내부의 병렬 처리나 만료 처리량을 높이지 않으며 DB 트랜잭션과 잠금 동작도 바꾸지 않습니다. 같은 fixed-delay 작업은 이전 실행이 끝난 뒤 다음 실행을 예약합니다.
- Scheduler는 프로세스 단위입니다. 여러 애플리케이션 인스턴스는 각자 만료 작업을 실행할 수 있으며, 이 변경은 분산 리더 선출이나 인스턴스 간 조정을 추가하지 않습니다.
- 예약 작업의 동시 실행 수가 늘면 DB 경합이 증가할 수 있습니다. pool 크기는 JDBC connection 용량을 설정하거나 보장하지 않습니다.
- 후속 PostgreSQL 측정은 [EXP-026](../../03-experiments/EXP-026-scheduler-expiry-multi-instance.md) 참고. 두 Spring context 경합은 Admission 만료만 검증; Order·BuyerCheckout의 multi-instance 실행은 측정 범위 밖.
