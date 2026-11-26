# EXP-109: 회로 차단·timeout 단축·적응형 동시 한도를 외부 사례와 실측으로 다시 판단한다

- 상태: ✓ 완료(실제 Sandbox 지연 1,498호출, 용량 제한 가짜 서버, 단일 호스트, 칸당 n=2~3). 가설과 반증 조건은 첫 측정 실행 전 커밋으로 고정했고 보강 두 건은 해당 칸 측정 전에 커밋했다. 실행하지 못한 칸은 `한계`에 이유와 함께 적었다.
- 날짜: 2026-11-26
- 관련: [#706](https://github.com/Neo1228/ATLAS/issues/706), [EXP-108](EXP-108-dependency-slowness-thread-saturation.md), [ADR-0098](../02-decisions/adr/ADR-0098-paypal-call-bulkhead-opt-in.md), [ADR-0055](../02-decisions/adr/ADR-0055-extreme-load-boundaries-and-infra-gates.md), [EXP-050](EXP-050-toxiproxy-postgres-fault-probe.md), [ADR-0084](../02-decisions/adr/ADR-0084-checkout-ttl-authorize-slo-and-load-contract.md)

## 문제 한 문장

EXP-108이 옵트인 동시 상한(`PayPalCallBulkhead`)을 정했지만 (a) 문턱이 용량 제한 없는 가짜 서버와 인위적 지연에서 나왔고 실제 Sandbox 지연 분포와 평상시 동시 호출 수를 몰랐으며, (b) breaker 진동을 만들지 못했고, (c) 구매자 결제 경로를 부하로 지나지 않았고, (d) 위치 서비스 20/s를 재지 않았고, (e) read timeout 단축을 측정하지 않았다. 이 실험은 외부 사례를 열어 읽고, 실제 지연 분포로 용량 제한 있는 가짜 서버를 맞추고, 같은 장애 시나리오에서 보호 방식 여섯 가지를 비교해 가장 작은 설계를 고른다.

## 외부 사례 조사 (측정 전)

조사는 중국·인도·글로벌 세 갈래로 나눠 페이지를 직접 열어 문장을 추출했다. 검증 수준: `열어서 읽음`(본문 읽음), `열어서 읽음(직접 403, 읽기 전용 프록시 경유)`(직접 요청은 403, `fetch-doc.py`의 읽기 전용 프록시로 본문을 읽음), `일부만 읽음`, `검색 스니펫만`(본문을 못 읽음, 인용하지 않음), `차단(403)`. 열지 않은 것은 인용하지 않았다. NPCI 통합 회람 PDF는 npci.org.in 직접 요청이 403이었고 같은 URL의 본문을 읽기 전용 프록시(HTTP 200)로 읽었다. 원문 자체는 NPCI의 것이지만 npci.org.in에서 직접 받은 사본이 아니다.

| # | 지역 | 출처 | 검증 수준 | URL |
|---|---|---|---|---|
| R1 | 중국 | Sentinel wiki 熔断降级 (circuit breaking, zh) | 열어서 읽음 | <https://raw.githubusercontent.com/wiki/alibaba/Sentinel/%E7%86%94%E6%96%AD%E9%99%8D%E7%BA%A7.md> |
| R2 | 중국 | Sentinel official site 'Circuit breaking' (en) | 열어서 읽음 | <https://sentinelguard.io/en-us/docs/circuit-breaking.html> |
| R3 | 중국 | Sentinel source: AbstractCircuitBreaker.java (half-open mechanics) | 열어서 읽음 | <https://raw.githubusercontent.com/alibaba/Sentinel/master/sentinel-core/src/main/java/com/alibaba/csp/sentinel/slots/block/degrade/circuitbreaker/AbstractCircuitBreaker.java> |
| R4 | 중국 | Sentinel wiki 流量控制 (flow control, zh) – concurrency/thread-count control | 열어서 읽음 | <https://raw.githubusercontent.com/wiki/alibaba/Sentinel/%E6%B5%81%E9%87%8F%E6%8E%A7%E5%88%B6.md> |
| R5 | 중국 | Sentinel wiki 系统自适应限流 (system adaptive protection, zh) | 열어서 읽음 | <https://raw.githubusercontent.com/wiki/alibaba/Sentinel/%E7%B3%BB%E7%BB%9F%E8%87%AA%E9%80%82%E5%BA%94%E9%99%90%E6%B5%81.md> |
| R6 | 중국 | Dubbo 为服务调用指定 timeout (timeout semantics, zh) | 열어서 읽음 | <https://cn.dubbo.apache.org/zh-cn/overview/mannual/java-sdk/tasks/framework/timeout/> |
| R7 | 중국 | Dubbo 集群容错 (retries / failfast, zh) | 열어서 읽음 | <https://cn.dubbo.apache.org/zh-cn/overview/mannual/java-sdk/tasks/framework/fault-tolerent-strategy/> |
| R8 | 중국 | Dubbo 框架内置并发控制 (executes / actives, zh) | 열어서 읽음 | <https://cn.dubbo.apache.org/zh-cn/overview/mannual/java-sdk/tasks/rate-limit/concurrency-control/> |
| R9 | 중국 | Dubbo 自适应限流 (adaptive concurrency, zh) | 열어서 읽음 | <https://cn.dubbo.apache.org/zh-cn/overview/mannual/java-sdk/tasks/rate-limit/adaptive-concurrency-control/> |
| R10 | 중국 | Dubbo 动态调整超时 (Dubbo-Admin rule) | 열어서 읽음 | <https://cn.dubbo.apache.org/zh-cn/overview/mannual/java-sdk/tasks/traffic-management/timeout/> |
| R11 | 중국 | Netflix/Hystrix README 'Hystrix Status' | 열어서 읽음 | <https://raw.githubusercontent.com/Netflix/Hystrix/master/README.md> |
| R12 | 중국 | 美团点评智能支付核心交易系统的可用性实践 (Meituan Dianping payments) | 열어서 읽음 | <https://tech.meituan.com/2018/04/19/trade-high-availability-in-action.html> |
| R13 | 중국 | 阿里云 AHAS 配置熔断规则 (circuit-breaking rule) | 열어서 읽음 | <https://help.aliyun.com/zh/ahas/user-guide/configure-circuit-breaking-rules> |
| R14 | 중국 | 阿里云 AHAS 应用防护规则选型与适用场景 | 열어서 읽음 | <https://help.aliyun.com/zh/ahas/user-guide/scenarios-of-application-protection-rules> |
| R15 | 중국 | 蚂蚁 SOFARPC 自动故障剔除 (single-node fault eviction) | 열어서 읽음 | <https://www.sofastack.tech/projects/sofa-rpc/fault-tolerance> |
| R16 | 중국 | 蚂蚁 技术风口上的限流 (Ant Mesh rate limiting, adaptive) | 열어서 읽음 | <https://www.sofastack.tech/blog/restricted-flow-on-the-technology-windfall> |
| R17 | 중국 | 京东云 稳定性建设之弹性设计 | 열어서 읽음 | <https://developer.jdcloud.com/article/3308> |
| R18 | 중국 | 微信支付 V2 查询订单 (orderquery) | 열어서 읽음 | <https://pay.weixin.qq.com/doc/v2/merchant/4011941128> |
| R19 | 중국 | 微信支付 V2 统一下单 (unifiedorder error handling) | 열어서 읽음 | <https://pay.weixin.qq.com/doc/v2/merchant/4011935214> |
| R20 | 중국 | 微信支付 V2 付款码支付 (micropay) | 열어서 읽음 | <https://pay.weixin.qq.com/doc/v2/partner/4011941293> |
| R21 | 중국 | 微信支付 付款码支付 常见问题 (cross-border developer center) | 열어서 읽음 | <https://pay.weixin.qq.com/doc/global/v3/zh/4012355014> |
| R22 | 중국 | 微信支付 APIv3 开发指引 支付下单 (partner doc) | 열어서 읽음 | <https://pay.weixin.qq.com/doc/v3/partner/4012088031> |
| R23 | 중국 | 支付宝 当面付接入注意事项 | 열어서 읽음 | <https://opendocs.alipay.com/open/194/105322> |
| R24 | 중국 | 支付宝 alipay.trade.pay 接口文档 | 열어서 읽음 | <https://opendocs.alipay.com/open/02ekfp> |
| R25 | 중국 | 支付宝 轮询机制介绍 and 付款码支付快速接入 (search snippets only) | 검색 스니펫만 | <https://opendocs.alipay.com/support/01rfnt> |
| R26 | 인도 | NPCI UPI Consolidated Circular v1.0 (NPCI/UPI/CC/01), 18 Sep 2026 (PRIMARY) | 열어서 읽음(직접 403, 읽기 전용 프록시 경유) | <https://www.npci.org.in/uploads/UPI_Consolidated_Circular_0641e910f7.pdf> |
| R27 | 인도 | DD News: NPCI directs banks to limit 'check transaction' API usage (29/04/25) | 열어서 읽음 | <https://ddnews.gov.in/en/npci-directs-banks-to-limit-check-transaction-api-usage-to-avoid-upi-outage/> |
| R28 | 인도 | NPCI UPI Procedural Guidelines (early version, mirror on yashada.org) | 열어서 읽음 | <https://yashada.org/yashada_2019/pdfs/e_library_cit/edpri_UPI_Procedural_Guidelines.pdf> |
| R29 | 인도 | NPCI OC-128A (Addendum, deemed-debit response codes for mandate execution), 3 Jul 2026 | 차단(403) | <https://www.npci.org.in/uploads/UPI_OC_128_A_Addendum_to_OC_128_Extension_of_additional_response_codes_under_Deemed_Debit_for_mandate_execution_3b1e59eab2.pdf> |
| R30 | 인도 | NPCI NETC OC-007 Guidelines for Transaction Re-initiation | 차단(403) | <https://www.npci.org.in/uploads/NETC_OC_007_Guidelines_for_Transaction_Re_initiation_b00d25cbf5.pdf> |
| R31 | 인도 | Slicker: UPI error BT (secondary) | 열어서 읽음 | <https://www.slickerhq.com/payment-failed/upi/bt> |
| R32 | 인도 | zyroai: UPI error codes list (secondary) | 일부만 읽음 | <https://zyroai.com/upi-error-codes> |
| R33 | 인도 | RBI/2019-20/67 DPSS.CO.PD No.629/02.01.014/2019-20, 20 Sep 2019 (TAT harmonisation) | 열어서 읽음 | <https://littdb.sfo2.cdn.digitaloceanspaces.com/litt/IN/RBI/notifications/RBI_11693.html> |
| R34 | 인도 | Hyperswitch Auth-Rate Routing | 열어서 읽음 | <https://docs.hyperswitch.io/integration-guide/workflows/intelligent-routing/routing-strategies/auth-rate-based-routing> |
| R35 | 인도 | Hyperswitch Volume-Based Routing | 열어서 읽음 | <https://docs.hyperswitch.io/integration-guide/workflows/intelligent-routing/routing-strategies/volume-based-routing> |
| R36 | 인도 | Juspay Dynamic Routing doc | 열어서 읽음 | <https://juspay.io/in/docs/resources/docs/dynamic-routing/dynamic-routing> |
| R37 | 인도 | Juspay Order Status API: 'API Latency Guideline' (PRIMARY for percentile-based timeouts) | 열어서 읽음 | <https://juspay.io/in/docs/ec-api/docs/base-integration/order-status-api> |
| R38 | 인도 | Juspay transaction-status resource | 열어서 읽음 | <https://juspay.io/in/docs/ec-api/docs/resources/transaction-status> |
| R39 | 인도 | Juspay Outages (description) | 열어서 읽음 | <https://juspay.io/outages/docs/outages/description> |
| R40 | 인도 | Razorpay docs: Late Payment Authorisations | 열어서 읽음 | <https://razorpay.com/docs/payments/payments/late-authorisation> |
| R41 | 인도 | Razorpay Learn: Payment Timeout vs Late Authorisation (17 Sep 2026) | 열어서 읽음 | <https://razorpay.com/learn/payment-timeout/> |
| R42 | 인도 | Razorpay Engineering: Detecting downtimes to improve payment experiences (2021) | 열어서 읽음(직접 403, 읽기 전용 프록시 경유) | <https://engineering.razorpay.com/detecting-downtimes-to-improve-payments-experience-3bc2814152c> |
| R43 | 인도 | PhonePe Tech Blog: Living on the 'Edge' | 열어서 읽음 | <https://tech.phonepe.com/living-on-the-edge> |
| R44 | 인도 | Flipkart Tech: Proxies for resilience and fault tolerance in distributed SOA (Phantom) | 열어서 읽음(직접 403, 읽기 전용 프록시 경유) | <https://blog.flipkart.tech/proxies-for-resilience-and-fault-tolerance-in-distributed-soa-8a849bcf4873> |
| R45 | 인도 | Dream11 Tech: Break circuits, save Kong (2021) | 열어서 읽음 | <https://tech.dream11.in/blog/2021-06-21_Break-circuits--save-Kong----3680d88a0639> |
| R46 | 인도 | Lenskart blog: Circuit breakers, the saviour at Lenskart | 열어서 읽음(직접 403, 읽기 전용 프록시 경유) | <https://blog.lenskart.com/circuit-breakers-the-saviour-at-lenskart-7490bbc1ae3> |
| R47 | 인도 | Pratilipi Tales: Evolution of a Payment Worker Engine | 열어서 읽음(직접 403, 읽기 전용 프록시 경유) | <https://tales.pratilipi.com/evolution-of-a-payment-worker-engine-solving-for-scale-reliability-and-isolation-4a51fec0381a> |
| R48 | 글로벌 | Amazon Builders' Library: Timeouts, retries, and backoff with jitter | 열어서 읽음 | <https://aws.amazon.com/builders-library/timeouts-retries-and-backoff-with-jitter/> |
| R49 | 글로벌 | Amazon Builders' Library: Using load shedding to avoid overload | 열어서 읽음 | <https://aws.amazon.com/builders-library/using-load-shedding-to-avoid-overload/> |
| R50 | 글로벌 | Amazon Builders' Library: Avoiding fallback in distributed systems | 열어서 읽음 | <https://aws.amazon.com/builders-library/avoiding-fallback-in-distributed-systems/> |
| R51 | 글로벌 | Amazon Builders' Library: Implementing health checks | 열어서 읽음 | <https://aws.amazon.com/builders-library/implementing-health-checks/> |
| R52 | 글로벌 | Google SRE book ch.21 Handling Overload | 열어서 읽음 | <https://sre.google/sre-book/handling-overload/> |
| R53 | 글로벌 | Google SRE book ch.22 Addressing Cascading Failures | 열어서 읽음 | <https://sre.google/sre-book/addressing-cascading-failures/> |
| R54 | 글로벌 | Netflix/concurrency-limits README | 열어서 읽음 | <https://github.com/Netflix/concurrency-limits> |
| R55 | 글로벌 | Netflix Tech Blog: Performance Under Load (via r.jina.ai proxy) | 열어서 읽음(직접 403, 읽기 전용 프록시 경유) | <https://netflixtechblog.medium.com/performance-under-load-3e6fa9a60581> |
| R56 | 글로벌 | Envoy: Circuit breaking | 열어서 읽음 | <https://www.envoyproxy.io/docs/envoy/latest/intro/arch_overview/upstream/circuit_breaking> |
| R57 | 글로벌 | Envoy: Outlier detection | 열어서 읽음 | <https://www.envoyproxy.io/docs/envoy/latest/intro/arch_overview/upstream/outlier> |
| R58 | 글로벌 | Envoy: Adaptive concurrency filter | 열어서 읽음 | <https://www.envoyproxy.io/docs/envoy/latest/configuration/http/http_filters/adaptive_concurrency_filter> |
| R59 | 글로벌 | Resilience4j CircuitBreaker docs | 열어서 읽음 | <https://resilience4j.readme.io/docs/circuitbreaker> |
| R60 | 글로벌 | Resilience4j Bulkhead docs | 열어서 읽음 | <https://resilience4j.readme.io/docs/bulkhead> |
| R61 | 글로벌 | Resilience4j: Comparison to Netflix Hystrix | 열어서 읽음 | <https://resilience4j.readme.io/docs/comparison-to-netflix-hystrix> |
| R62 | 글로벌 | Zalando Engineering: Thundering herd of circuit breakers (2026-10) | 열어서 읽음 | <https://engineering.zalando.com/posts/2026/10/thundering-herd-of-circuit-breakers.html> |
| R63 | 글로벌 | Zalando Skipper docs: filters (fadeIn, fifo/lifo, admissionControl) | 열어서 읽음 | <https://opensource.zalando.com/skipper/reference/filters/> |
| R64 | 글로벌 | Microsoft Azure Architecture Center: Circuit Breaker pattern | 열어서 읽음 | <https://learn.microsoft.com/en-us/azure/architecture/patterns/circuit-breaker> |
| R65 | 글로벌 | Microsoft Azure Architecture Center: Bulkhead pattern | 열어서 읽음 | <https://learn.microsoft.com/en-us/azure/architecture/patterns/bulkhead> |
| R66 | 글로벌 | Stripe: Scaling your API with rate limiters | 열어서 읽음 | <https://stripe.com/blog/rate-limiters> |
| R67 | 글로벌 | Uber: Cinnamon: Using Century Old Tech to Build a Mean Load Shedder (via r.jina.ai proxy) | 열어서 읽음(직접 403, 읽기 전용 프록시 경유) | <https://www.uber.com/ug/en/blog/cinnamon-using-century-old-tech-to-build-a-mean-load-shedder> |
| R68 | 글로벌 | gRPC guide: Deadlines | 열어서 읽음 | <https://grpc.io/docs/guides/deadlines/> |
| R69 | 글로벌 | gRPC guide: Retry | 열어서 읽음 | <https://grpc.io/docs/guides/retry/> |
| R70 | 글로벌 | gRPC guide: Request hedging | 열어서 읽음 | <https://grpc.io/docs/guides/request-hedging/> |
| R71 | 글로벌 | Martin Fowler: CircuitBreaker bliki | 열어서 읽음 | <https://martinfowler.com/bliki/CircuitBreaker.html> |
| R72 | 글로벌 | Nygard, Release It! 2nd ed., publisher excerpt (Pragmatic Bookshelf PDF) | 열어서 읽음 | <https://media.pragprog.com/titles/mnee/mnee-antipatterns.pdf> |

차단·미열람(인용하지 않음): NPCI OC-128A(검색 스니펫만, 직접 403), NPCI NETC OC-007(403), 알리페이 轮询机制介绍(자바스크립트 껍데기, 스니펫만), 京东 InfoQ Sentinel 글(85바이트 stub), Dubbo 적응형 한도 설계 글(404), 직접 URL을 추측한 Uber 글(406, 404), Netflix 블로그 직접 URL(403, 프록시로 읽음). 지역별 검증 수준 개수는 위 표의 `검증 수준` 열을 센 값이다.

### 핵심 진술 (원문 그대로, 출처는 위 표의 이름)

- Amazon Builders' Library, Timeouts, retries, and backoff with jitter: "we choose an acceptable rate of false timeouts (such as 0.1%). Then, we look at the corresponding latency percentile on the downstream service (p99.9 in this example)." 같은 글: "This approach doesn't work in cases where clients have substantial network latency, such as over the internet." 같은 글: "circuit breakers introduce modal behavior into systems that can be difficult to test, and can introduce significant addition time to recovery."
- Zalando Engineering, Thundering herd of circuit breakers: "The circuit breaker in the client stays open for hours, blocking roughly 95% of requests."
- Resilience4j CircuitBreaker: "If only 9 calls have been recorded the CircuitBreaker will not transition to open even if all 9 calls have failed." (minimumNumberOfCalls 10일 때)
- Envoy circuit breaking: "Cluster maximum requests: The maximum number of requests that can be outstanding to all hosts in a cluster at any given time."
- Google SRE book, Handling Overload: "Clients can continue to issue requests to the backend until requests is K times as large as accepts." 이어서 "Once that cutoff is reached, the client begins to self-regulate and new requests are rejected locally"
- Netflix concurrency-limits README: "For large and complex distributed systems it's impossible to know all the hard resources." 같은 README: "Limit = Average RPS * Average Latency"
- 美团点评 결제 시스템 가용성 글: "统计这个接口99%的响应时间是多少，设置的超时时间在这个基础上加50%。如果接口依赖第三方，而第三方的波动比较大，也可以按照95%的响应时间。"
- Juspay Order Status API 문서: "The recommended timeouts are based on TP99.9 data, though edge cases (0.01% of requests) may still exceed these limits" (TP99.9 594.42 ms, TP99.99 1431.42 ms)
- NPCI UPI 통합 회람(읽기 전용 프록시 경유): "The PSPs shall request for status only after 60 seconds of the initiation/ authentication of the original transaction."

### 조사가 말하는 것과 말하지 않는 것

1. **정적 호출 측 상한은 업계의 기본 수단이다.** Envoy의 "circuit breaking"은 연결·대기 요청·동시 요청의 고정 상한이고(R 글로벌), Resilience4j `SemaphoreBulkhead`는 고정 `maxConcurrentCalls`이며, Sentinel 동시성 제어와 AHAS 격리 규칙은 "조용히 느려지는 호출(第三方)로부터 업무 스레드 풀을 지키는" 용도로 쓴다(R 중국). 반대로 breaker는 AHAS 문서가 "弱依赖降级" 용도라고 적었다. PayPal은 결제 경로의 강한 의존성이라 대체 응답이 없다.
2. **적응형 한도의 근거는 Netflix·Envoy·Uber·Hystrix 후속 권고에 있고, 그 모두가 "수동으로 잰 고정 한도는 낡는다"는 것이다.** 어느 출처도 변동이 큰 제3자 결제 제공자 하나에 대한 적응형 한도를 시험하지 않았다. 중국 쪽 적응형 한도(Sentinel 시스템 보호, Dubbo 자동 동시성 제한, 蚂蚁 Mesh)는 모두 수신측 보호이거나 제공자 노드가 여러 개인 전제다.
3. **timeout은 지연 백분위에서 구한다.** 美团(결제)은 P99에 50%를 더하고 변동이 큰 제3자는 P95, AWS Builders' Library는 허용 가능한 거짓 timeout 비율(예: 0.1%)에 해당하는 백분위(p99.9)를 읽되 인터넷 지연이 큰 호출에는 맞지 않는다고 적는다. Juspay는 주문 상태 API에 TP99.9 594 ms에서 1000 ms, TP99.99 1431 ms에서 2000 ms를 권한다. PayPal 같은 인터넷 너머 제3자 결제 API 하나에 대한 값은 어느 출처에도 없다. 이 실험의 3a가 그 값을 잰다.
4. **timeout은 실패가 아니라 모름이다.** 알리페이는 "不能简单的推断为付款失败", 위챗페이는 SYSTEMERROR 뒤 조회, Razorpay는 timeout 뒤 Failed로 처리한 결제가 이후 승인되는 late authorisation을 문서화했고(전체의 0.5% 미만), NPCI 통합 회람은 상태 조회를 60초 뒤부터 최대 3회로 제한하며 조회 폭주가 5시간 UPI 장애를 일으켰다고 DD News가 보도했다(2차 출처). ATLAS의 UNKNOWN + 복구 worker 재조회 + 멱등 키 설계와 같은 방향이고, 조회 빈도를 바운드해야 한다는 경고를 준다. 글로벌 출처 중 결제 상태 조회를 직접 다룬 것은 없다.
5. **breaker 진동의 직접 증거는 Zalando 한 건이다.** 클라이언트 breaker가 몇 시간 열려 요청의 약 95%를 막았다(드문 half-open 시험은 건강해 보여 닫고, 닫힌 뒤 풀린 전체 트래픽이 timeout을 내 다시 열림). AWS는 breaker가 시스템에 "modal behavior"를 넣고 복구 시간을 늘린다며 로컬 재시도 토큰 버킷을 권한다. Sentinel의 half-open은 시험 호출 하나와 고정 복구 시간이고 진동을 논의하지 않는다(진동 추론은 조사자의 것이라고 표시돼 있다). 점진 복구 장치는 Skipper `fadeIn`, SRE의 적응형 throttle, Dubbo 자동 동시성 제한의 탐색 비율, SOFARPC 가중치 복구, Hyperswitch의 탐색 비율에 있다. 인도 출처에는 breaker 진동을 직접 잰 글이 없다.

## 이 실험이 닫으려는 EXP-108의 미확인 점

| # | EXP-108의 미확인 점 | 이 실험의 닫는 방법 | 못 닫는 경우의 처리 |
|---|---|---|---|
| a | 가짜 서버의 지연이 인위적이고 평상시 동시 호출 수를 몰랐다 | 3a: 실제 Sandbox 지연을 연산 4종에 n≥200, 3개 이상 시간대, ≤3 req/s로 잰다. 3b의 가짜 서버가 그 표본에서 지연을 뽑는다. 3d: 평상시 동시 수는 도착률 × 평균 지연이고 대조 칸에서 실측과 비교한다 | 운영 PayPal은 이 환경에서 잴 수 없다(계정·트래픽 없음). 해당 주장은 not confirmed로 남기고 운영 지표 수치 재검토 조건을 쓴다 |
| b | breaker 진동을 못 만들었다 | 3b: 고정 worker와 대기열이 있는 가짜 서버에서 처리 용량이 떨어지는 시나리오(`cap`)와 깜빡임(`flap`) | 진동이 안 생기면 안 생겼다고 쓰고 반증 조건 표에 반영 |
| c | 웹훅 검증 경로만 부하로 지났다 | 구매자 경로 `POST /api/buyers/{ref}/cart/checkout` 뒤 `POST /api/checkouts/{id}/payment`(create order)를 실제 앱·PostgreSQL로 지난다. authorize·capture는 구매자 브라우저 승인이 필요해 가짜 서버로는 지나지 않는다 | authorize·capture·return 경로는 같은 `PayPalCallBulkhead.call` 한 지점을 지나지만 부하로는 재지 않았다고 쓴다 |
| d | 위치 서비스 20/s와 결함 미수정 | 3c: 10·20·40 호출/s(필요하면 30), 읽기 2 s, 용량 제한 있는 가짜 서버. 증거가 정당화할 때만 옵트인 상한을 구현 | 정당화되지 않으면 수치 재검토 조건으로 닫는다 |
| e | read timeout 단축을 측정하지 않았다 | A2: 실측 백분위에서 구한 timeout을 같은 장애 시나리오에 적용하고 정상 구간 UNKNOWN 비율과 복구 부하를 잰다 | |

## 코드 읽기 결과 (EXP-108 이후 추가로 확인한 것, 기준 커밋 `63be23da`)

- 모든 PayPal HTTP 호출은 `PayPalRestClient`에서 `PayPalCallBulkhead.call`/`run` 한 지점을 지난다(생성, 조회, authorize, capture, void, refund, 환불 조회, 웹훅 검증). 새 실험 arm은 이 한 지점에만 들어가므로 모든 연산에 같은 보호가 걸린다.
- 구매자가 요청 스레드에서 PayPal을 만나는 첫 경로는 `POST /api/buyers/{ref}/cart/checkout`(PayPal 호출 없음) 뒤 `POST /api/checkouts/{id}/payment`다. 이 요청은 FX 스냅샷이 없으면 환율 API를 먼저 부른 뒤(캐시 60 s, 실패 뒤 15 s 억제) PayPal create order를 부른다. 이 실험에서 환율 공급자는 가짜 서버로 돌린다.
- OAuth 토큰은 `OAuth2AuthorizedClientManager`가 캐시하며 `PayPalRestClient.api()` 안에서 요청 스레드가 호출한다. 토큰 요청도 읽기 timeout 안이다.
- UNKNOWN 복구 worker는 `payment_recovery_job`을 처리하고 같은 `PayPalRestClient`를 거친다. 요청 스레드가 아니므로 어떤 arm도 worker 호출을 거절하지 않는다. 복구 호출은 장애 중에도 계속 나가고 가짜 서버의 부하가 된다(이 부하는 `payment_recovery_job` 건수와 가짜 서버 호출 수로 센다).

## 측정 방법 (사전 등록)

### 3a 실제 Sandbox 지연

- 도구: 로컬 전용 파이썬 클라이언트(저장소에 올리지 않음). Sandbox 자격 증명은 `.env.local`에서 이 프로세스 안에서만 읽고 출력·기록하지 않는다. 연결 하나를 재사용(keep-alive)하며 호출 간격은 최소 1/3 s(≤3 req/s), 순차 실행이다.
- 연산 5종: OAuth 토큰 발급, create order(`intent=AUTHORIZE`, 구매자 미승인 상태로만 만든다. 실제 승인·결제가 없다), get order, 미승인 주문의 authorize(422 `ORDER_NOT_APPROVED`가 정상 응답. 요청 스레드가 이 호출의 대기 시간을 겪는 점은 같다), 가짜 서명 웹훅 검증. 구매자 승인이 필요한 authorize 성공·capture·refund는 브라우저 승인 없이 할 수 없어 3a에서 재지 않는다(`load-tests/paypal-real-fault`의 Playwright 흐름이 필요하고 호출 수가 비용이다).
- 표본: 연산당 시간대 4개 × 75 = 300 (n ≥ 200, 시간대 ≥ 3). 시간대 사이는 약 23분. 각 호출의 시각, 상태 코드, 지연, 예외, load1을 기록한다. 보고: p50/p90/p99/p99.9/max(nearest-rank), 5xx·예외·10 s 초과 건수, 총 호출 수.
- 시간대 1(20:38~20:44)은 사전 등록 전에 이미 보았다(create order에 11.14 s 한 건). 3a는 서술 측정이고 가설 검정이 아니다.

### 3b 용량 제한 가짜 서버

- 파이썬 asyncio 서버 하나(저장소에 올리지 않음): PayPal(토큰, 주문 생성·조회, 웹훅 검증, 그 밖 경로)과 환율(`/v1/latest`)과 위치 서비스. PayPal과 위치 서버는 각각 고정 worker 수와 유한한 대기열을 갖는다. 요청은 대기열에서 FIFO로 worker를 얻고, 서비스 시간은 3a 표본에서 복원 추출한 값에 ±5% 균등 잡음을 곱한 값이다(모수 적합 대신 경험 분포 사용: 꼬리 모양을 가정하지 않기 위해서이며 표본 최댓값 너머의 꼬리는 모사하지 않는다). 대기열이 가득 차면 즉시 503. 클라이언트가 끊으면 대기 중인 요청은 버린다(`dead_queue=drop`, 호출 로그에 `dropped_dead`로 남음).
- 제어: worker 수, 대기열 길이, 지연 배수(`mult`), 오류 비율, 모드(`ok`, `hang`: 연결은 받고 응답하지 않음, `refuse`: 수신 소켓을 닫음), 위치 서버 고정 서비스 시간.
- 앱: bootJar(기준 커밋)에 실험 arm을 한 클래스에 얹은 jar(저장소에 올리지 않음). 칸마다 새 JVM(`-Xms1g -Xmx1g`)과 칸마다 새 PostgreSQL 데이터베이스(시드 복사), 환율·PayPal·위치 모두 가짜 서버. 시드: Seller 1, 상품 1, SKU 1(재고 1,000,000), 구매자 180명(이메일 인증 완료).
- 부하: 구매자 세션 8/s(`cart PUT` → `checkout POST` → `payment POST`, 세션마다 다른 구매자·멱등 키) + 의존성을 쓰지 않는 `GET /api/products?limit=20` 20/s, 40 s. 장애는 5 s 시점에 시작해 25 s 시점에 끝난다(장애 구간 20 s). 칸 뒤 20 s 더 표집해 복구 worker 호출과 복구 시간을 본다.
- 장애 시나리오: `step`(지연 배수 8, 용량 사실상 무제한), `cap`(worker 16→4, 대기열 64: 용량 저하로 대기 지연이 올라가는 부하 유도 느려짐), `hang`(연결 수락 후 무응답), `flap`(5 s 간격으로 배수 8과 1을 두 번 반복), `brown`(오류 30% + 배수 3), `refuse`(연결 거부), 대조군 `none`(장애 없음, 실제 지연 분포).
- 지표: Tomcat busy 스레드(Micrometer 1 Hz), 카탈로그 저하 비율 D(지연 1 s 초과 또는 비2xx, 장애 시작 + 3 s부터 장애 끝까지)와 p95(맥락), 결제 POST 결과(`AUTHORIZATION_PENDING` 성공, `UNKNOWN`, 503 등), 가짜 서버가 받은 호출 수·중도 이탈 수·큐 대기 p95, arm 상태 전이 로그(진동 횟수), arm 카운터(수락·거절·성공·실패·느린 호출), 실패한 호출이 스레드를 묶은 총 시간(doomed thread-seconds = 예외로 끝난 호출 지속 시간의 합), `payment_attempt` 상태와 `payment_recovery_job` 건수, 장애 끝 뒤 결제 성공이 90% 이상으로 돌아오는 데 걸린 초(복구 초).
- 호스트 규약: 모든 측정 칸은 `measure-lock.sh`를 쥔 동안에만 실행하고 칸마다 `status`(load와 다른 라벨 컨테이너)를 기록한다. 시작 load1 > 12이거나 다른 스택과 겹치면 오염으로 별도 표시한다. 컨테이너는 잠금을 쥔 동안에만 띄운다. 결론은 건수와 불변식으로 내고 시간 값은 맥락이다.
- 하네스 확인: 측정 전에 `A0 step`, `A4 cap`, 위치 칸 smoke 각 1회를 돌려 지표가 나오는지 본다. 이 smoke 값은 표에 쓰지 않는다. 이 smoke 중 A0 step 한 번(호스트 잠금을 쥐지 않은 하네스 확인, load1 11.8~12.2)에서 장애 구간 카탈로그 D가 0.69였고 결제 UNKNOWN이 많이 생겨 탐지기가 반응함을 봤지만 어떤 판정에도 쓰지 않는다.

### Arm 정의 (모두 요청 스레드 호출에만 적용, worker 호출은 대상이 아님, 거절은 보내기 전)

| arm | 정의 |
|---|---|
| A0 | 보호 없음. 읽기 timeout 10 s(현재 기본) |
| A1 | 현재의 정적 동시 상한(EXP-108 `PayPalCallBulkhead`). 상한 10, 20, 30 |
| A2 | 읽기 timeout을 3a의 연산별 분포에서 구한 값으로 줄임. 규칙: T2 = 3a 전체 시간대 합친 create order 지연의 p99(nearest-rank)를 올림한 정수 초, 단 3 s 미만이면 3 s. 이 앱은 연산 종류별 timeout을 지원하지 않아 create order(구매자 경로에서 부하로 쓰는 유일한 연산) 기준으로 한 값을 모든 연산에 쓴다. 다른 연산의 p99와 비교해 T2가 그보다 작으면 표에 적는다 |
| A3 | 느린 호출 비율 breaker, 단순(Resilience4j 의미): 최근 완료 호출 10개(COUNT_BASED), 호출이 예외로 끝나거나 2 s 이상이면 나쁨, 10개 중 50% 이상이 나쁘면 OPEN. OPEN 5 s 동안 모든 요청 스레드 호출을 보내기 전에 거절. 그 뒤 시험 호출 3개를 통과시켜 나쁜 비율이 50% 미만이면 CLOSED |
| A4 | A3의 열림 규칙 + 점진 복구(ADR-0055의 33/67/100): OPEN 뒤 33% → 67% → 100%를 단계마다 최소 5 s, 단계 안에서 나쁜 완료가 3건 이상이거나 완료 3건 이상에서 나쁜 비율이 50% 이상이면 다시 OPEN. 첫 단계는 시험 호출 3개로 제한 |
| A5 | 적응형 동시 한도(AIMD, Netflix `AIMDLimit`와 같은 형태): 초기 20, 최소 2, 최대 40. 나쁜 완료(예외 또는 2 s 이상)이면 한도 × 0.9(1 s에 한 번), 좋은 완료이고 시작 시 in-flight ≥ 한도/2이면 + 1/한도. 한도 이상이면 보내기 전에 거절 |
| A6 | 조합: A1 상한 20을 먼저 확인하고 A4 breaker를 적용 |

실험 구현은 한 클래스(`PayPalCallBulkhead`에 시스템 속성 `exp.arm`으로 선택)에 있고 저장소에 올리지 않는다. 증거가 하나를 고르면 그 하나만 계층 규칙에 맞게 다시 구현한다.

## 가설과 반증 조건 (측정 실행 전 등록)

판정 규칙: 칸마다 n = 3. 비율 판정은 "3개 중 2개 이상". 오염 칸은 별도 열에 표시하고 전체와 오염 제외 두 집계를 적는다. 장애 구간 D는 장애 시작 + 3 s부터 장애 끝까지의 카탈로그 요청 중 저하 비율이다.

| # | 가설 | 반증 조건 | 지표 |
|---|---|---|---|
| H1 | 실제 Sandbox에서 create order·get order·토큰·웹훅 검증의 p99가 모두 4 s 이하이고 10 s를 넘는 호출은 연산당 전체의 1% 미만이다(현재 10 s read timeout이 정상 지연에 여유 있는 값이다) | 어느 연산이든 p99 > 4 s이거나 10 s 초과가 전체의 1% 이상(시간대 4개 합쳐 연산당 n=300 기준 3건 이상) | 3a 백분위, 초과 건수 |
| H2 | 평상시(`none`) 구매자 세션 8/s에서 PayPal create order의 동시 in-flight는 도착률 × 평균 지연(= 8 × 평균 지연 ≈ 11)의 ±25% 안이고 최댓값은 20 미만이다. A1 상한 20은 대조 칸에서 거절 0건(3/3)이고 상한 10은 거절 ≥ 1건(2/3 이상) | A0 대조 칸 in-flight 평균이 예측의 ±25% 밖인 반복이 2개 이상, 또는 A1-20 대조 칸에서 거절이 나온 반복이 하나라도 있음(상한 20이 평상시를 건드림) | arm 카운터, 표집기 |
| H3 | 용량 제한 서버(`cap`)에서 단순 breaker(A3)는 진동한다: 장애 구간 20 s 안에 OPEN 전이 ≥ 3회가 3개 반복 중 2개 이상 | A3의 OPEN 전이가 2회 이하인 반복이 2개 이상. 반증되면 "진동은 이 조건에서 만들어지지 않았다"로 쓰고 ADR-0055의 진동 경고를 이 조건에 대해서는 근거 없다고 적는다 | 상태 전이 로그, 가짜 서버 초당 도착 호출 수 |
| H4 | 점진 복구(A4)는 단순(A3)보다 `cap`에서 OPEN 전이 수가 적거나 같고 장애 끝 뒤 복구 초가 길지 않다(30% 이내) | A4의 OPEN 전이가 A3보다 많은 반복이 2개 이상, 또는 A4의 복구 초가 A3 × 1.3 초과인 반복이 2개 이상 | OPEN 전이 수, 복구 초 |
| H5 | 완료한 호출로 판단하는 breaker(A3, A4)는 `hang`·`step`에서 첫 열림이 읽기 timeout(10 s) 직전에는 오지 않아 그 전에 busy가 38 이상에 닿는다(EXP-108 H4a를 구매자 경로에서 반복) | 3개 반복 중 2개 이상에서 첫 열림이 장애 시작 후 7 s 이내이거나 첫 10 s의 busy 최댓값이 38 미만 | 첫 열림 시각, busy |
| H6 | 정적 상한 20(A1-20)은 `step`·`cap`·`hang` 모두에서 D < 0.05(각각 3개 중 2개 이상)를 지키고 상한을 넘은 호출은 PayPal에 도달하지 않는다 | 어느 시나리오든 D ≥ 0.20인 반복이 2개 이상 | D, 가짜 서버 시작 호출 수 |
| H7 | 백분위로 구한 timeout(A2)은 고정 10 s(A0)보다 낫다: `step`에서 A2의 D < 0.05(2/3 이상)이고 A0는 D ≥ 0.20(2/3 이상, 탐지기 양성 대조). 단 정상(`none`) 구간에서 A2가 만드는 UNKNOWN 결제 비율은 2% 이하다 | A2의 `none` UNKNOWN 비율이 5% 초과인 반복이 하나라도 있거나, A2의 `step` D ≥ 0.20인 반복이 2개 이상. 둘 중 하나가 일어나면 "백분위 timeout이 10 s보다 낫다"는 반증이고 지표로 얼마나 나쁜지를 적는다 | 결제 UNKNOWN 건수, D |
| H8 | 적응형 한도(A5)는 `cap`에서 정적 상한 20(A1-20)보다 실패한 호출이 스레드를 묶은 시간(doomed thread-seconds)이 30% 이상 적고 D < 0.05를 유지한다. 한도는 장애 구간에서 20 미만으로 내려가고 장애 후 다시 오른다 | doomed thread-seconds가 A1-20의 70% 초과인 반복이 2개 이상, 또는 D ≥ 0.05인 반복이 2개 이상, 또는 `none` 대조 칸에서 거절이 나옴(평상시를 건드림) | arm 카운터, 한도 로그 |
| H9 | 위치 서비스(읽기 timeout 2 s, 가짜 서비스 시간 5 s라 h = 2 s)는 10/s(λ·h = 20)와 20/s(λ·h = 40)에서 카탈로그를 밀지 않고(D < 0.05, 2/3 이상) 40/s에서는 민다(D ≥ 0.20, 2/3 이상). 20/s는 PayPal 4/s × 10 s(EXP-108에서 D = 0)와 같은 경계다 | 20/s에서 D ≥ 0.20인 반복이 2개 이상(그러면 위치 서비스는 계약 도착률 안에서 포화하는 곳이 있다), 또는 40/s에서 D < 0.20인 반복이 2개 이상 | D, busy, 위치 스레드 |
| H10 | 탐지기가 유효하다: `none`에서 A0의 D < 0.05, `step`의 A0는 busy 최댓값 ≥ 38 | `none`의 D ≥ 0.05인 반복이 하나라도 있으면 그 시나리오 해석을 중단 | D, busy |

### 사전 등록 보강 (2026-11-26 21:20, A2 측정 전, c1~c3 칸 시작 전)

3a의 시간대 1~2(n = 150/연산)를 본 뒤의 보강이다. create order의 지연은 이봉 분포다: 2 s 초과 5건 중 2건이 3 s와 5 s와 10 s를 모두 넘고(11.1 s와 12.5 s) 나머지 3건은 2~3 s이며 이 둘 사이 값이 없다. 그래서 위 규칙의 T2(= create order p99 올림)는 시간대가 쌓이면 10 s 이상이 될 수 있다. 그 경우 A2는 현재 10 s보다 긴 timeout이라 "백분위로 줄인 timeout"이 되지 못한다. 이 결과를 숨기지 않고 두 arm을 함께 측정한다.

- A2: 위 규칙 그대로(3a 전체 시간대 합산 create order p99 올림, 최소 3 s). 규칙이 10 s 이상을 내면 그 값을 쓰고 "백분위 규칙이 10 s보다 짧은 값을 주지 못했다"로 기록한다.
- A2b: 읽기 timeout 3 s 고정(꼬리 이벤트가 아닌 호출의 최댓값(웹훅 검증 2.90 s) 바로 위의 정수 초이고 EXP-108 A4와 같은 값). 규칙이 아니라 선택한 값이며 이름에 그것을 드러냈다.
- H7 보강: A2b의 `none` UNKNOWN 비율은 가짜 서버의 모델 분포에서 3 s를 넘는 비율(window 1~2 표본 기준 create order 1.3%, 연산 평균은 더 낮다)과 A0의 `none` UNKNOWN 비율의 차이가 1 %p 이내일 것이다(꼬리가 이봉이라 3 s를 넘은 호출은 거의 다 10 s도 넘는다는 예측). 반증: 차이가 1 %p를 넘는 반복이 2개 이상이면 3 s는 정상 응답을 끊는다. A2b `step` D < 0.05(2/3 이상)는 예측(λ·h = 8 × 3 = 24 < 40).
- H2/H7 해석 보강: 모델의 create order 10 s 초과 확률이 1.3%라 A0도 `none`에서 UNKNOWN을 만든다. UNKNOWN 비율은 A0 대비 차이로 읽는다.
- 가짜 서버의 지연 모델은 3a 시간대 1~2의 표본(연산당 150건)에서 뽑는다. 시간대 3~4는 모델에 넣지 않고 모델 검증(H1)에만 쓴다.

### 위치 서비스 상한 정당화 규칙 (3c)

옵트인 상한을 구현하려면 (1) 위치 서비스 칸에서 계약 안 도착률(ADR-0084의 버스트 30 세션/s 이하에서 세션당 위치 호출이 1건 이상)의 어떤 점에서 D ≥ 0.20이 2/3 이상이어야 한다. 20/s에서 D < 0.05이고 40/s에서만 포화하면 포화 문턱 λ·h = 40은 위치 호출 20/s이고 버스트 30 세션/s는 그보다 크므로, 세션당 호출이 1건 이상인지 코드로 확인해 정당화 여부를 정한다. (2) 구현은 요청 스레드만 세고 worker(`LocationReservationServiceImpl.recoverDue`, `LocationRestockServiceImpl`)는 상한 대상이 아니며 거절은 보내기 전 `definitely-not-sent`로 분류돼 기존 복구 경로로 수렴해야 한다.

### 사전 등록 보강 (2026-11-26 23:36, 위치 상한·크기 산정·brownout·연결 거부 칸의 측정 전)

위치 상한 칸(`LOC20c20`, `LOC40c20`, `LOCctlc20`), 크기 산정(`Z5`), `brown`, `refuse`, A6 `cap`·`none`, A2 `cap`은 모두 이 커밋 뒤에 실행한다(실행 시각은 결과 표의 칸 시작 시각이 근거).

| # | 가설 | 반증 조건 | 지표 |
|---|---|---|---|
| H11 | 위치 호출 옵트인 상한 20(`LocationCallBulkhead`)은 40/s × 5 s(h = 2 s)에서 무관한 카탈로그를 지킨다(D < 0.05, 3개 중 2개 이상). 거절은 위치 서비스로 가기 전이라 가짜 위치 서버가 받은 호출 수가 수락 수와 같고, 20/s와 장애 없음(`LOCctlc20`)에서는 거절 0건이다 | 40/s에서 D ≥ 0.20인 반복이 2개 이상, 또는 장애 없음·20/s에서 거절이 나옴 | D, 가짜 위치 서버 호출 수, 거절 계수기 |
| H12 | 크기 산정: 평상시 요청 스레드의 PayPal in-flight 평균은 세션 도착률 × 평균 지연(= λ × 1.24 s, 3a 전체 시간대의 create order 평균)의 ±25% 안이다. λ = 12/s(예측 14.9)에서 상한 20은 거절하지 않고, λ = 16/s(예측 19.9)에서 상한 20은 거절하며(거절 ≥ 1건, 2/3 이상), 상한 30은 거절하지 않는다(3/3) | 평균이 예측의 ±25% 밖인 반복이 2개 이상, 또는 λ = 16/s에서 상한 20이 거절하지 않음, 또는 상한 30이 거절함. 반증되면 "평상시 in-flight = 도착률 × 평균 지연" 공식이 이 환경에서 맞지 않는다고 쓴다 | arm 카운터의 in-flight 평균·최댓값, 거절 수 |
| H13 | brownout(`brown`: 오류 30% + 지연 3배)에서 A0은 D < 0.05이고(40개를 채우지 못함: 8/s × 3.9 s ≈ 31 < 40) 어떤 보호 arm도 D를 더 낮출 필요가 없다. 같은 칸에서 A3·A4·A5는 `none`에서 없던 거절을 만든다(거절 ≥ 1건)가 A0보다 성공한 결제가 적다 | A0의 D ≥ 0.05인 반복이 2개 이상(보호가 필요한 장애가 됨), 또는 A3·A4·A5가 A0보다 성공 결제를 늘림. 반증 방향 어느 쪽이든 지표로 적는다 | D, 결제 성공 수, 거절 수 |
| H14 | 연결 거부(`refuse`)는 모든 arm에서 포화를 만들지 않는다: 연결 거부는 즉시 실패(수 ms)라 in-flight 최댓값이 10 미만이고 D < 0.05(3/3). breaker·한도가 만드는 추가 이득은 없다 | in-flight 최댓값이 20 이상이거나 D ≥ 0.05인 반복이 하나라도 있으면 "거부도 스레드를 묶는다"로 쓴다 | in-flight, D |

### 위치 서비스 호출 수 코드 읽기 (위치 칸 측정 전)

`CartCheckoutServiceImpl.remoteCandidates`(`:440-465`)는 장바구니 Seller의 활성 location마다 SKU마다 `availableStock`을 순차로 부른다(위치 서비스 endpoint를 설정한 배포에서만). 이어서 child order마다 `reserve`, 결제 직전 `claim`이 요청 스레드에서 나간다. 따라서 location 1개·SKU 1개인 최소 세션도 위치 호출이 3건(가용 조회, 예약, claim)이다. ADR-0084의 지속 10 세션/s는 위치 호출 30건/s이고, 위치 호출 하나가 읽기 상한 2 s까지 걸리면 λ·h = 60으로 포화 문턱(40)을 넘는다. 그래서 H9의 "20/s에서 D < 0.05" 예측이 맞더라도 계약 범위 안의 세션 도착률이 위치 호출 20/s를 넘으므로 정당화 규칙 (1)의 판정은 측정이 20/s에서 포화하지 않아도 30/s 이상의 점이 필요하다. 이 지점을 측정하는 칸은 40/s(포화 예측)와 20/s(경계)다. 30/s는 처음에 시간 예산상 빼려 했으나 사용자 요청으로 마지막 보강 묶음에서 측정했다.

### 설계 선택 규칙 (측정 뒤 적용)

1. "현재 옵트인 상한을 유지하고 다른 것은 바꾸지 않는다"가 기본이다. 다른 arm이 이를 이기려면 (가) `step`·`cap`·`hang` 중 두 곳 이상에서 D < 0.05를 유지하고, (나) `none` 대조 칸에서 정상 요청을 거절하지 않고(거절 0, UNKNOWN 증가 ≤ 2%), (다) A1-20 대비 doomed thread-seconds 또는 장애 뒤 복구 초에서 30% 이상 우월해야 한다.
2. 새 의존성(Resilience4j)은 ADR로 대안을 비교하고 직접 구현이 작으면 직접 구현한다.
3. 기본값은 동작을 바꾸지 않는다(0 = 꺼짐). 0이 아닌 기본값은 3a·3b의 정상 in-flight 최댓값에 안전 여유(최소 2배)가 있다는 증거가 있을 때만 정한다.
4. 요청 스레드 호출만 대상이고 거절은 보내기 전이다. 결제 모듈은 Micrometer에 의존하지 않는다(평범한 훅).

## 대안

| 대안 | 평가 |
|---|---|
| 측정 없이 Resilience4j를 붙인다 | 새 의존성이고 근거가 없다. 기각 |
| 가짜 서버만으로 결론낸다 | EXP-108의 한계를 반복한다. 3a로 지연 분포를 실제에 맞춘다 |
| 실제 Sandbox에 부하를 건다 | 속도 한도·계정 위험이 있어 3a는 ≤ 3 req/s로 제한한다. 포화 시나리오는 가짜 서버에서만 만든다 |

## 한계 (실행 전에 알고 있는 것)

- 실제 PayPal Sandbox 지연은 운영 지연과 같지 않다. 운영 PayPal은 이 환경에서 잴 수 없다.
- 3a의 연산은 승인 없이 가능한 5종이다. authorize 성공·capture·refund의 실제 지연은 재지 않는다.
- 호스트가 공유되어 시간 값은 맥락이다.
- 구매자 승인이 필요한 authorize·capture·return 경로는 부하로 지나지 않는다.

## 결과

실행: 2026-11-26 20:38 ~ 2026-11-27 03:23 KST, Apple Silicon Mac(Colima VM) 한 대, 호스트 잠금(`measure-lock.sh`)을 쥔 구간에서만 측정했다. 앱은 기준 커밋 bootJar에 실험 arm 한 클래스를 얹은 jar(위치 상한 칸은 이 PR의 운영 코드 jar), PostgreSQL 18.7, JDK 25. 칸마다 새 JVM과 새 데이터베이스(시드 복사). 컨테이너는 잠금을 쥔 동안에만 띄웠다. 총 153칸(구매자 경로 칸 123, 위치 칸 30)을 모두 n=3으로 실행했다. 마지막 보강 묶음(크기 산정, brownout, 연결 거부, A6, A2 `cap`, 위치 30/s, 부족한 3번째 반복)은 2026-11-27 02:19~03:23에 실행했다.

### 3a 실제 Sandbox 지연 (총 1,498호출, 연산당 n = 299~300, 시간대 4개)

| 연산 | n | 시간대 | p50 | p90 | p99 | p99.9 | max | 5xx·예외 | >3 s | >5 s | >10 s |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OAuth 토큰 | 300 | 4 | 0.68 | 0.97 | 1.51 | 1.89 | 1.89 | 1 | 0 | 0 | 0 |
| create order | 300 | 4 | 1.19 | 1.49 | 2.10 | 12.53 | 12.53 | 1 | 2 | 2 | 2 |
| get order | 299 | 4 | 0.78 | 0.99 | 1.51 | 1.68 | 1.68 | 0 | 0 | 0 | 0 |
| authorize(미승인 주문, 422가 정상) | 299 | 4 | 0.89 | 1.13 | 1.89 | 2.16 | 2.16 | 0 | 0 | 0 | 0 |
| 웹훅 검증(가짜 서명) | 300 | 4 | 0.68 | 1.01 | 1.49 | 2.90 | 2.90 | 0 | 0 | 0 | 0 |

| 시간대 | 시작 | 호출 수 | create order p50 | p99 | max | >10 s | load1 범위 |
|---|---|---|---|---|---|---|---|
| w1 | 20:38 | 375 | 1.27 | 11.14 | 11.14 | 1 | 4.8~8.3 |
| w2 | 21:06 | 375 | 1.28 | 12.53 | 12.53 | 1 | 4.3~7.4 |
| w3 | 21:35 | 373 | 1.27 | 1.87 | 1.87 | 0 | 6.2~13.2 |
| w4 | 22:03 | 375 | 1.10 | 1.59 | 1.59 | 0 | 2.6~4.5 |

- 시간대 4개는 20:38, 21:06, 21:35, 22:03 시작이고 각 약 5분이며 호출 간격은 최소 1/3 s(≤3 req/s)다. 총 호출 수 1,498(연산 5종). 5xx는 토큰 1건(시간대 1, 503)과 create order 1건(시간대 3, 503, 0.21 s)이고 시간 초과 예외는 0건이다. 호출 지연은 대부분 1~2 s이고 create order에만 10 s 넘는 호출이 시간대 1과 2에 한 건씩(11.14 s, 12.53 s) 있다. 시간대 3과 4에는 5 s 넘는 호출이 없다.
- 사전 등록 보강은 시간대 1~2(n = 150)만 보고 만들었다. 그 표본의 create order p99는 11.14 s라 T2 규칙이 12 s를 줬다. 시간대 3~4를 합친 n = 300에서는 p99가 2.10 s이고 p99.9가 12.53 s다. 즉 상한 꼬리 두 건(0.67%)이 p99 규칙의 값을 정한다. 이 표본 크기로는 p99와 p99.9를 구분할 수 없다.
- 구매자 승인 없이 잴 수 없는 연산(authorize 성공, capture, refund)은 재지 못했다(시도한 호출과 오류는 `삭제한 파일의 내용`).

### 3b 구매자 결제 경로, 용량 제한 가짜 서버

열 정의: D = 장애 시작 + 3 s ~ 장애 끝 구간에서 의존성을 쓰지 않는 카탈로그 요청 중 지연 > 1 s 또는 비2xx 비율. PayPal in-flight 최댓값은 요청 스레드가 동시에 PayPal을 기다린 호출 수(arm 카운터, 1 Hz). Tomcat busy 열은 앱에서 지표가 나오지 않은 앞 24칸 안팎에서 `-`다(실행 중간에 `mbeanregistry`를 켜서 고쳤다. 그 칸의 busy는 PayPal in-flight와 D로 갈음했다). 결제 POST는 장애 구간(20 s, 약 160건)의 응답을 `AUTHORIZATION_PENDING`(성공)/`UNKNOWN`/503으로 센 값이고 반복 셋이 `/`로 이어진다. 거절 수와 doomed 스레드 s(예외로 끝난 호출이 스레드를 묶은 시간)는 arm 카운터, 도달 호출은 가짜 서버가 장애 구간에 받은 PayPal 호출 수, 복구 s는 장애 끝 뒤 결제 성공이 90% 이상으로 돌아온 시각. 각 칸 값은 반복 순서대로 `/`로 이어붙였다(n = 반복 수).

#### 시나리오 `none`

| arm | n | D(장애구간) | Tomcat busy 최댓값 | PayPal in-flight 최댓값(요청 스레드) | 결제 POST 장애구간 성공/UNKNOWN/503 | 거절 수(전체) | OPEN 전이(장애구간) | 첫 열림 s | doomed 스레드 s | 가짜 서버 장애구간 도달 호출 | 복구 s | 전체 UNKNOWN/결제 | load1 시작 | 오염 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A0 | 3 | 0/0/0 | 13/13/14 | 13/13/13 | 157/3/0/159/1/0/158/2/0 | 0/0/0 | 0/0/0 | -/-/- | 50/10/40 | 162/161/161 | 0/0/0 | 0/325/0/324/0/325 | 5.2/4.4/3.1 | 아니오/아니오/아니오 |
| A1-10 | 3 | 0/0/0 | 11/12/11 | 10/10/10 | 123/37/0/127/33/0/126/34/0 | 63/74/49 | 0/0/0 | -/-/- | 50/70/40 | 134/137/136 | 9/-/3 | 50/325/65/325/32/324 | 4/4.1/3.6 | 아니오/아니오/아니오 |
| A1-20 | 3 | 0/0/0 | 16/14/14 | 14/15/12 | 160/0/0/153/7/0/159/1/0 | 0/0/0 | 0/0/0 | -/-/- | 10/100/30 | 160/162/162 | 0/0/0 | 0/324/1/324/0/325 | 6.1/4.5/4.5 | 아니오/아니오/아니오 |
| A2 | 3 | 0/0/0 | 15/12/15 | 14/12/14 | 160/0/0/160/0/0/158/2/0 | 0/0/0 | 0/0/0 | -/-/- | 48/12/24 | 164/163/163 | 0/0/0 | 3/324/1/325/1/324 | 4.7/3.2/3.6 | 아니오/아니오/아니오 |
| A2b | 3 | 0/0/0 | 13/13/14 | 12/12/13 | 159/1/0/157/3/0/156/4/0 | 0/0/0 | 0/0/0 | -/-/- | 9/15/21 | 160/163/163 | 0/0/0 | 0/325/0/325/0/324 | 4.2/4.7/4.5 | 예( claims)/예( claims)/아니오 |
| A5 | 3 | 0/0/0 | 13/14/13 | 13/13/11 | 158/2/0/158/2/0/160/0/0 | 0/0/0 | 0/0/0 | -/-/- | 40/60/30 | 161/162/160 | 0/0/0 | 0/325/1/325/0/324 | 4.2/5.2/5.6 | 예( claims)/예( claims)/아니오 |
| A6 | 3 | 0/0/0 | 14/13/13 | 14/11/13 | 157/3/0/160/0/0/157/3/0 | 0/0/0 | 0/0/0 | -/-/- | 70/0/50 | 161/160/161 | 0/0/0 | 3/324/0/324/0/324 | 5.1/6.1/4.2 | 아니오/아니오/아니오 |

#### 시나리오 `nonebig`

| arm | n | D(장애구간) | Tomcat busy 최댓값 | PayPal in-flight 최댓값(요청 스레드) | 결제 POST 장애구간 성공/UNKNOWN/503 | 거절 수(전체) | OPEN 전이(장애구간) | 첫 열림 s | doomed 스레드 s | 가짜 서버 장애구간 도달 호출 | 복구 s | 전체 UNKNOWN/결제 | load1 시작 | 오염 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A0r12 | 3 | 0/0/0 | 18/20/20 | 16/18/20 | 239/1/0/239/1/0/236/4/0 | 0/0/0 | 0/0/0 | -/-/- | 20/50/80 | 240/242/242 | 0/0/0 | 1/485/2/485/3/485 | 3.2/3.1/3.2 | 아니오/아니오/아니오 |
| A0r16 | 3 | 0/0/0 | 27/24/27 | 27/25/25 | 312/8/0/314/6/0/316/4/0 | 0/0/0 | 0/0/0 | -/-/- | 120/120/110 | 322/322/322 | 0/0/0 | 4/645/6/644/5/645 | 3.1/4.8/3.1 | 아니오/아니오/아니오 |
| A1-20r16 | 3 | 0/0/0 | 21/21/21 | 20/20/20 | 264/56/0/272/48/0/287/33/0 | 78/76/59 | 0/0/0 | -/-/- | 70/60/40 | 275/279/296 | 1/0/0 | 73/644/67/645/48/645 | 4.4/2.5/3.8 | 아니오/아니오/아니오 |
| A1-30r16 | 3 | 0/0/0 | 25/25/24 | 24/25/23 | 316/4/0/314/6/0/317/3/0 | 0/0/0 | 0/0/0 | -/-/- | 130/70/60 | 322/322/322 | 0/0/0 | 7/645/3/645/2/645 | 4.8/4/2.9 | 아니오/아니오/아니오 |

#### 시나리오 `step`

| arm | n | D(장애구간) | Tomcat busy 최댓값 | PayPal in-flight 최댓값(요청 스레드) | 결제 POST 장애구간 성공/UNKNOWN/503 | 거절 수(전체) | OPEN 전이(장애구간) | 첫 열림 s | doomed 스레드 s | 가짜 서버 장애구간 도달 호출 | 복구 s | 전체 UNKNOWN/결제 | load1 시작 | 오염 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A0 | 3 | 0.712/0.718/0.719 | -/-/40 | 40/40/40 | 68/39/0/75/45/0/64/45/0 | 0/0/0 | 0/0/0 | -/-/- | 440.2/480.3/490.2 | 96/89/91 | 0/0/0 | 31/300/27/299/37/299 | 5.7/4.3/3 | 아니오/아니오/아니오 |
| A1-10 | 3 | 0/0/0 | 12/12/11 | 10/10/10 | 10/150/0/14/146/0/15/145/0 | 186/170/172 | 0/0/0 | -/-/- | 170.1/110/120.1 | 27/24/26 | -/-/6 | 187/324/169/324/175/325 | 3.1/3.5/3.3 | 아니오/아니오/아니오 |
| A1-20 | 3 | 0/0/0 | 21/22/22 | 20/20/20 | 34/126/0/21/139/0/24/136/0 | 124/118/115 | 0/0/0 | -/-/- | 240.1/320.1/280.1 | 56/51/49 | 4/1/1 | 131/325/132/325/128/325 | 4.3/3.5/3 | 아니오/아니오/아니오 |
| A2 | 3 | 0.704/0.657/0.73 | 40/40/40 | 40/40/40 | 87/12/0/113/13/0/84/6/0 | 0/0/0 | 0/0/0 | -/-/- | 156/168.1/84 | 88/92/89 | 0/1/0 | 1/298/4/296/1/298 | 4.6/4.1/3.9 | 아니오/아니오/아니오 |
| A2b | 3 | 0/0/0 | 25/26/25 | 24/25/24 | 0/160/0/0/160/0/0/160/0 | 0/0/0 | 0/0/0 | -/-/- | 483.4/483.4/498.4 | 163/160/165 | 0/0/0 | 145/325/144/325/153/325 | 5/4.8/3.4 | 아니오/아니오/아니오 |
| A3 | 3 | 0.158/0.142/0.117 | -/-/- | 40/40/40 | 23/128/0/30/125/0/24/132/0 | 203/193/191 | 1/1/1 | 10.1/9/8.8 | 240.2/170.1/260.1 | 49/48/48 | 13/11/11 | 217/315/198/319/202/321 | 4.2/4.1/4.1 | 아니오/아니오/아니오 |
| A4 | 3 | 0.023/0.132/0.012 | -/-/40 | 40/40/40 | 26/134/0/27/130/0/28/132/0 | 212/213/209 | 1/1/1 | 6.8/8.7/6.4 | 200.1/200.1/180.1 | 46/48/46 | -/-/- | 218/325/218/322/215/325 | 3.2/3.4/3.3 | 아니오/아니오/아니오 |
| A5 | 3 | 0/0/0 | -/-/19 | 17/16/18 | 15/145/0/19/141/0/17/143/0 | 167/186/176 | 0/0/0 | -/-/- | 140/120/150.1 | 29/33/32 | 13/-/11 | 163/324/186/325/174/324 | 9.1/3.9/4.3 | 아니오/아니오/아니오 |
| A6 | 3 | 0/0/0 | 21/21/21 | 20/20/20 | 17/143/0/15/145/0/15/145/0 | 238/233/238 | 1/1/1 | 8.7/8.1/8 | 100/100.1/120.1 | 26/26/26 | -/-/- | 226/325/236/325/236/325 | 3.3/3.3/3.4 | 아니오/아니오/아니오 |

#### 시나리오 `cap`

| arm | n | D(장애구간) | Tomcat busy 최댓값 | PayPal in-flight 최댓값(요청 스레드) | 결제 POST 장애구간 성공/UNKNOWN/503 | 거절 수(전체) | OPEN 전이(장애구간) | 첫 열림 s | doomed 스레드 s | 가짜 서버 장애구간 도달 호출 | 복구 s | 전체 UNKNOWN/결제 | load1 시작 | 오염 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A0 | 3 | 0.565/0.517/0.553 | -/-/40 | 40/40/40 | 82/26/0/114/8/0/80/29/0 | 0/0/0 | 0/0/0 | -/-/- | 280.1/100.1/350.2 | 103/109/101 | 0/0/0 | 14/302/2/308/19/302 | 19.5/4.1/3.9 | 예(load1 19.5)/아니오/아니오 |
| A1-20 | 3 | 0/0/0 | 21/22/21 | 20/20/20 | 72/88/0/74/86/0/72/88/0 | 90/91/93 | 0/0/0 | -/-/- | 20/20/30 | 75/76/74 | 1/1/1 | 82/324/79/325/75/325 | 7.5/4.4/4 | 아니오/아니오/아니오 |
| A2 | 3 | 0.476/0.519/0.521 | 40/40/40 | 40/40/40 | 122/1/0/110/13/0/113/2/0 | 0/0/0 | 0/0/0 | -/-/- | 36/192.1/48 | 111/97/103 | 0/1/0 | 0/308/6/300/2/303 | 4.6/4/3.3 | 아니오/아니오/아니오 |
| A2b | 3 | 0/0/0 | 26/25/25 | 25/25/24 | 16/144/0/14/146/0/15/145/0 | 0/0/0 | 0/0/0 | -/-/- | 441.6/441.4/447.4 | 163/164/164 | 0/0/0 | 132/325/134/325/135/325 | 4.6/3/3.5 | 아니오/아니오/아니오 |
| A3 | 3 | 0/0/0 | -/-/24 | 20/31/24 | 28/132/0/28/132/0/66/94/0 | 139/147/102 | 2/2/2 | 3.3/4.6/3.6 | 30/150.1/30 | 34/43/71 | 2/4/2 | 125/325/143/324/87/324 | 4.7/4.2/3.7 | 아니오/아니오/아니오 |
| A4 | 3 | 0/0/0 | -/-/- | 26/29/18 | 22/138/0/34/126/0/55/105/0 | 178/175/159 | 2/2/2 | 4/4.4/3.4 | 130.1/70/50 | 37/40/59 | 11/11/11 | 180/324/164/325/148/324 | 2.4/2.9/4.6 | 아니오/아니오/아니오 |
| A5 | 3 | 0/0/0 | -/-/18 | 17/15/18 | 63/97/0/67/93/0/97/63/0 | 120/115/74 | 0/0/0 | -/-/- | 40/10/10 | 68/71/101 | 11/8/5 | 110/325/99/324/58/325 | 3.1/3.6/2.4 | 아니오/아니오/아니오 |
| A6 | 3 | 0/0/0 | 20/20/16 | 19/19/18 | 59/101/0/58/102/0/33/127/0 | 167/163/137 | 2/2/1 | 3.5/3.7/3.9 | 40/40/30 | 64/62/41 | 13/12/4 | 151/325/146/325/119/325 | 3.2/3.7/3.3 | 아니오/아니오/아니오 |

#### 시나리오 `hang`

| arm | n | D(장애구간) | Tomcat busy 최댓값 | PayPal in-flight 최댓값(요청 스레드) | 결제 POST 장애구간 성공/UNKNOWN/503 | 거절 수(전체) | OPEN 전이(장애구간) | 첫 열림 s | doomed 스레드 s | 가짜 서버 장애구간 도달 호출 | 복구 s | 전체 UNKNOWN/결제 | load1 시작 | 오염 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A0 | 3 | 0.725/0.686/0.738 | 40/40/40 | 40/40/40 | 22/80/0/25/80/0/21/81/0 | 0/0/0 | 0/0/0 | -/-/- | 810.3/820.4/840.3 | 81/81/80 | 0/0/0 | 65/291/71/293/69/291 | 5.1/4.3/4.6 | 아니오/아니오/아니오 |
| A1-20 | 3 | 0/0/0 | 22/22/21 | 20/20/20 | 0/160/0/0/160/0/0/160/0 | 122/121/122 | 0/0/0 | -/-/- | 430.1/430.1/410.2 | 42/42/41 | 0/0/0 | 157/325/156/324/145/325 | 3.6/3.6/3.1 | 아니오/아니오/아니오 |
| A4 | 3 | 0.195/0.211/0.199 | 40/40/40 | 40/40/40 | 0/149/0/0/149/0/0/149/0 | 220/215/218 | 1/1/1 | 10.5/10.5/10.4 | 430.1/470.2/430.2 | 44/48/44 | -/-/- | 247/313/252/314/246/313 | 4.2/4.1/3.4 | 예( claims)/예( claims)/아니오 |
| A5 | 3 | 0/0/0 | 21/20/21 | 20/18/20 | 0/160/0/0/160/0/0/160/0 | 139/135/140 | 0/0/0 | -/-/- | 330.1/340.1/350.1 | 33/34/33 | 2/1/2 | 164/325/149/324/160/325 | 3.8/4.3/2.9 | 아니오/아니오/아니오 |

#### 시나리오 `flap`

| arm | n | D(장애구간) | Tomcat busy 최댓값 | PayPal in-flight 최댓값(요청 스레드) | 결제 POST 장애구간 성공/UNKNOWN/503 | 거절 수(전체) | OPEN 전이(장애구간) | 첫 열림 s | doomed 스레드 s | 가짜 서버 장애구간 도달 호출 | 복구 s | 전체 UNKNOWN/결제 | load1 시작 | 오염 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A0 | 3 | 0.517/0.628/0.607 | 40/40/40 | 40/40/40 | 97/35/0/68/36/0/98/33/0 | 0/0/0 | 0/0/0 | -/-/- | 390.2/380.1/380.2 | 110/103/112 | 0/0/0 | 19/310/18/306/19/308 | 3.6/2.9/4.7 | 아니오/아니오/아니오 |
| A3 | 3 | 0.072/0.147/0.138 | 40/40/40 | 40/40/40 | 30/129/0/21/133/0/27/128/0 | 178/195/183 | 1/1/1 | 8/9.3/9 | 170.1/270.1/210.1 | 48/51/48 | 9/12/10 | 179/324/205/319/197/320 | 5/3.5/4.4 | 예( claims)/아니오/아니오 |
| A4 | 3 | 0.103/0.104/0.157 | 40/40/40 | 40/40/40 | 32/125/0/30/128/0/28/127/0 | 212/216/215 | 1/1/1 | 8.6/8.5/9.4 | 180.1/190.1/210.1 | 54/50/50 | -/-/- | 212/321/217/323/220/320 | 5/5.8/5.3 | 예( claims)/예( claims)/아니오 |

#### 시나리오 `brown`

| arm | n | D(장애구간) | Tomcat busy 최댓값 | PayPal in-flight 최댓값(요청 스레드) | 결제 POST 장애구간 성공/UNKNOWN/503 | 거절 수(전체) | OPEN 전이(장애구간) | 첫 열림 s | doomed 스레드 s | 가짜 서버 장애구간 도달 호출 | 복구 s | 전체 UNKNOWN/결제 | load1 시작 | 오염 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A0 | 3 | 0/0/0 | 33/33/37 | 31/30/36 | 105/55/0/108/52/0/103/57/0 | 0/0/0 | 0/0/0 | -/-/- | 40.1/50.1/70.1 | 164/161/163 | 0/0/0 | 49/324/39/325/51/321 | 3.8/3/2.6 | 아니오/아니오/아니오 |
| A1-20 | 3 | 0/0/0 | 20/21/21 | 20/20/20 | 89/71/0/96/64/0/87/73/0 | 35/22/55 | 0/0/0 | -/-/- | 50.1/20.1/20.1 | 132/145/117 | 2/1/3 | 71/325/55/325/73/324 | 3.8/3.1/3.7 | 아니오/아니오/아니오 |
| A4 | 3 | 0/0/0 | 16/13/15 | 15/8/15 | 17/143/0/9/151/0/21/139/0 | 220/227/225 | 2/2/2 | 2.5/1.3/2.6 | 30/10/0 | 30/20/32 | -/-/- | 216/324/221/324/213/325 | 3.3/3.5/2.9 | 아니오/아니오/아니오 |
| A5 | 3 | 0/0/0 | 16/15/15 | 15/14/15 | 43/117/0/39/121/0/45/115/0 | 169/170/154 | 0/0/0 | -/-/- | 50/30.1/20 | 63/60/66 | -/-/- | 168/325/177/325/159/325 | 4/3.2/3.4 | 아니오/아니오/아니오 |

#### 시나리오 `refuse`

| arm | n | D(장애구간) | Tomcat busy 최댓값 | PayPal in-flight 최댓값(요청 스레드) | 결제 POST 장애구간 성공/UNKNOWN/503 | 거절 수(전체) | OPEN 전이(장애구간) | 첫 열림 s | doomed 스레드 s | 가짜 서버 장애구간 도달 호출 | 복구 s | 전체 UNKNOWN/결제 | load1 시작 | 오염 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A0 | 3 | 0/0/0 | 7/2/10 | 8/8/10 | 0/160/0/0/160/0/0/160/0 | 0/0/0 | 0/0/0 | -/-/- | 20.2/20.3/20.3 | 0/0/0 | 0/0/3 | 146/324/146/325/161/324 | 6.3/3.9/3.8 | 아니오/아니오/아니오 |
| A1-20 | 3 | 0/0/0 | 5/7/6 | 9/10/6 | 0/160/0/0/160/0/0/160/0 | 0/0/0 | 0/0/0 | -/-/- | 0.2/40.3/10.4 | 0/0/0 | 3/0/- | 171/325/142/325/282/325 | 3.4/4.3/2.4 | 아니오/아니오/아니오 |
| A4 | 3 | 0/0/0 | 9/6/13 | 4/1/4 | 0/160/0/0/160/0/0/160/0 | 213/213/213 | 4/4/4 | 0.5/0.5/0.6 | 0/20/20 | 0/0/0 | 13/13/13 | 219/325/223/325/214/325 | 8.2/3.5/3.2 | 아니오/아니오/아니오 |

#### 위치 서비스 `locslow`

| arm | n | D(장애구간) | 위치 POST 장애구간 2xx/503/기타 | 위치 POST p50 ms | 카탈로그 p95 ms | 가짜 위치 서버 max in-flight | load1 시작 | 오염 |
|---|---|---|---|---|---|---|---|---|
| LOC10 | 3 | 0/0/0 | 200/0/0/200/0/0/200/0/0 | 2013/2014/2014 | 8/9/10 | 0/0/0 | 5.3/4.9/3.6 | 예( claims)/예( claims)/아니오 |
| LOC20 | 3 | 0/0/0 | 400/0/0/400/0/0/401/0/0 | 2086/2090/2089 | 165/169/173 | 0/0/0 | 3.2/3.1/2.8 | 아니오/아니오/아니오 |
| LOC30 | 3 | 0.993/0.993/0.99 | 510/0/0/510/0/0/510/0/0 | 4342/4341/4341 | 4585/4581/4564 | 0/0/0 | 4.1/3.3/3.8 | 아니오/아니오/아니오 |
| LOC40 | 3 | 1/1/1 | 530/0/0/530/0/0/530/0/0 | 5621/5607/5628 | 6953/6951/6993 | 0/0/0 | 6.1/4.4/3.8 | 아니오/아니오/아니오 |
| LOC30c20 | 3 | 0/0/0 | 600/0/0/600/0/0/601/0/0 | 9/9/9 | 6/6/6 | 0/1/0 | 5.1/3.6/3.3 | 아니오/아니오/아니오 |
| LOC20c20 | 3 | 0/0/0 | 400/0/0/400/0/0/399/0/0 | 2008/2010/25 | 8/9/9 | 0/0/0 | 5.2/4.7/3.1 | 아니오/아니오/아니오 |
| LOC40c20 | 3 | 0/0/0 | 800/0/0/800/0/0/800/0/0 | 9/9/8 | 8/8/6 | 0/0/0 | 3.2/4.9/3.1 | 아니오/아니오/아니오 |

#### 위치 서비스 `none`

| arm | n | D(장애구간) | 위치 POST 장애구간 2xx/503/기타 | 위치 POST p50 ms | 카탈로그 p95 ms | 가짜 위치 서버 max in-flight | load1 시작 | 오염 |
|---|---|---|---|---|---|---|---|---|
| LOCctl | 3 | 0/0/0 | 800/0/0/800/0/0/800/0/0 | 9/9/9 | 6/6/6 | 0/0/0 | 6.1/3.4/4.5 | 아니오/아니오/아니오 |
| LOCctlc20 | 3 | 0/0/0 | 800/0/0/800/0/0/799/0/0 | 9/9/9 | 6/6/6 | 0/0/0 | 4.7/3.5/3.6 | 아니오/아니오/아니오 |

### 오염 칸

오염 기준은 시작 load1 > 12 또는 다른 에이전트의 라벨 컨테이너(`claims`)가 겹침(이 호스트에서 항상 있는 유휴 `pool110`은 제외). 오염 칸 12개: `A0 cap` 반복 1(load1 19.5), `A2b none` 반복 1·2, `A3 flap` 반복 1, `A4 flap` 반복 1·2, `A4 hang` 반복 1·2, `A5 none` 반복 1·2, `LOC10 locslow` 반복 1·2(`claims` 컨테이너가 겹침). 같은 칸의 오염되지 않은 반복과 같은 방향의 값이라 어떤 판정도 바뀌지 않지만 표의 `오염` 열에 표시했다. 나머지 칸은 시작 load1이 2.4~9.1이었다.

### 사전 등록 규칙 평가

| 가설 | 판정 | 근거(개수) |
|---|---|---|
| H1 실제 지연 p99 ≤ 4 s, 10 s 초과 < 1% | 지지 | 연산 5종 모두 p99 1.49~2.10 s(n = 299~300). 10 s 초과는 create order 2/300(0.67%, 반증 기준 3건 미만). 시간대별로는 시간대 1·2에 한 건씩이라 시간대에 의존한다 |
| H2 평상시 in-flight ≈ 도착률 × 평균 지연, 상한 20은 평상시를 건드리지 않고 10은 건드린다 | 지지 | 예측 8 × 1.24 = 9.9. `none` in-flight 평균 9.6~11.8(예측 ±25% 안), 최댓값 12~16. A1-20은 3/3 거절 0, A1-10은 3/3 거절 49·63·74건 |
| H3 단순 breaker(A3)는 `cap`에서 진동한다(OPEN ≥ 3회, 2/3) | 반증 | A3 `cap` OPEN 2·2·2회. `flap`·`step`에서는 1회 |
| H4 점진 복구(A4)는 A3보다 OPEN이 적거나 같고 복구가 30% 이상 늦지 않다 | 반증 | OPEN 2 대 2로 같음. 복구 s는 A4 11·11·11 대 A3 2·4·2로 3/3에서 1.3배 초과 |
| H5 breaker는 첫 열림이 7 s 이내가 아니고 그 전에 포화한다 | 부분 반증 | A3 `step` 첫 열림 10.1·9.0·8.8 s, A4 `hang` 10.5·10.5·10.4 s로 지지. A4 `step`은 6.8·8.7·6.4 s로 2/3이 7 s 이내라 반증. 모든 `step`에서 열리기 전에 PayPal in-flight가 40에 닿았다 |
| H6 상한 20은 `step`·`cap`·`hang`에서 D < 0.05 | 지지 | 3/3씩 D = 0, in-flight 최댓값 20. 장애 구간에 PayPal에 도달한 호출 `step` 49~56, `cap` 74~76, `hang` 41~42건 |
| H7 백분위 규칙 timeout(A2 = 12 s)이 10 s보다 낫다 | 반증 | 규칙이 12 s(10 s보다 길다). A2 `step` D = 0.704·0.657·0.730(A0은 0.712~0.719), A2 `cap` D = 0.476·0.519·0.521(A0 `cap` 0.517~0.565). 포화를 막지 못했다 |
| H7 보강: 3 s 고정(A2b)은 `none`에서 UNKNOWN을 A0보다 1 %p 넘게 늘리지 않고 `step`에서 D < 0.05 | 지지(비용 있음) | `none` 장애 구간 UNKNOWN 응답 A2b 1·3·4건 대 A0 3·1·2건(약 160건 중). `step` D = 0 3/3, `cap` D = 0 3/3. 대가: `step`에서 결제 POST 160/160이 UNKNOWN(A0은 39~45건), doomed 스레드 s 483~498로 A0(440~490)보다 작지 않다 |
| H8 적응형(A5)이 `cap`에서 정적 20보다 doomed 스레드 s가 30% 이상 적다 | 반증 | D = 0은 3/3이고 한도가 5~7까지 내려갔다가 18까지 올랐지만(방향 전환 8~10회) doomed 스레드 s는 A5 40·10·10(평균 20) 대 A1-20 20·20·30(평균 23.3)으로 14% 적을 뿐이다. `step`에서 거절이 A5 167~186건으로 A1-20(115~124)보다 많다. `none` 거절 0 3/3 |
| H9 위치 10·20/s는 D < 0.05, 40/s는 D ≥ 0.20 | 지지 | LOC10 D = 0, LOC20 D = 0(위치 POST p50 2.09 s = 읽기 상한), LOC40 D = 1.0(p50 5.6 s), 각 3/3. 추가로 LOC30 D = 0.990~0.993(3/3, p50 4.3 s)이라 위치 호출 30/s에서 이미 포화한다 |
| H10 탐지기 유효 | 지지 | `none`의 A0 D = 0(3/3), `step` A0 PayPal in-flight 40·40·40 |
| H11 위치 상한 20은 40/s를 지키고 20/s·평상시에서는 거절하지 않는다 | 부분 지지 | 40/s와 30/s 모두 D = 0(3/3씩), 위치 POST p50 9 ms, 가짜 위치 서버가 장애 구간에 받은 호출 210건(상한 없음 410건). 20/s도 D = 0 3/3. 평상시(`LOCctlc20`)는 D = 0 3/3이지만 가짜 서버가 받은 호출이 2198·2179·2200건(상한 없음 2200건 3/3)이라 한 칸에서 21건 적었다. 거절일 수 있으나 거절 계수기를 수집하지 못해 확인하지 못했다 |
| H12 크기 산정: 평균 in-flight ≈ λ × 1.24 s, λ = 16/s에서 상한 20은 거절하고 30은 거절하지 않는다 | 지지(λ = 12/s 상한 20은 미측정) | A0 λ = 12/s 평균 14.7·15.9·16.2(예측 14.9, ±25% 안), 최댓값 19·18·20. λ = 16/s 평균 22.5·21.1·21.5(예측 19.9, ±25% 안), 최댓값 27·25·25. λ = 16/s에서 A1-20은 3/3 거절 78·76·59건, A1-30은 3/3 거절 0. 상한 20을 λ = 12/s에서 직접 재지는 않았다(A0의 최댓값 20이 한 칸에서 상한에 닿았으므로 거절 가능) |
| H13 brownout에서 A0은 D < 0.05이고 A4·A5는 거절을 만들며 A0보다 성공 결제가 적다 | 지지(A3 미실행) | A0 D = 0 3/3(in-flight 평균 20.9~24.9). A4 거절 220·227·225건, 성공 결제 17·9·21건, A5 거절 154~170건, 성공 39~45건, 둘 다 A0의 성공 103~108건보다 적다. A1-20도 거절 22~55건, 성공 87~96건. A3의 brownout 칸은 실행하지 않았다 |
| H14 연결 거부는 모든 arm에서 포화를 만들지 않는다 | 지지 | A0·A1-20·A4 각 3/3에서 D = 0, 장애 구간 in-flight 평균 0.2~0.9. 장애 구간 결제는 전부 UNKNOWN(160/160, A0 포함). A4는 거절 213건과 OPEN 4회를 만들고 복구에 13 s가 걸려 A0(0~3 s)보다 느렸다 |

A6 `cap`·`none`(보강 칸): A6 `cap` D = 0 3/3, 성공 결제 33·58·59건(A1-20 `cap` 72~74건, A4 `cap` 22~55건)으로 정적 상한 단독보다 성공이 적다. A6 `none`은 거절 0 3/3(UNKNOWN 응답 0·3·3건)이라 평상시를 건드리지 않았다. A6 `step`은 D = 0이지만 거절 233~238건으로 A1-20 `step`(115~124건)보다 많다.

## 해석

- **bulkhead는 이 환경에서도 가장 작고 충분하다.** 정적 상한 20은 `step`·`cap`·`hang`에서 D = 0(3/3), 평상시(`none`)와 세션 16/s에서도 상한 30은 거절 0이다. 상한 10은 평상시에도 거절하고(49~74건) 20은 16/s에서 거절한다(59~78건). 도착률 × 평균 지연이 상한의 약 2/3 이하여야 평상시에 거절하지 않는다.
- **breaker는 여전히 늦고 이득이 작다.** 완료한 호출로 판단해 첫 열림이 6.4~10.5 s이고 열리기 전에 40이 찼다(D 0.12~0.16, A4 0.01~0.13, `hang` A4 0.20). 점진 복구는 단순보다 복구가 느리다(11 s 대 2~4 s). 진동은 용량 저하(`cap`)에서도 만들어지지 않았다(OPEN 2회). brownout에서는 A0이 이미 D = 0인데 A4가 성공 결제를 100건 넘게 줄였다(103~108건에서 9~21건). 연결 거부에서도 이득이 없고 복구만 늦췄다. 상한과 합친 A6는 거절이 더 많고 `cap`에서 성공이 적다.
- **적응형 한도(A5)는 D 면에서 정적과 같고 비용이 더 크다.** `step`에서 정적 20보다 거절이 많았고 brownout에서 성공 결제를 40건대로 줄였다.
- **timeout 단축은 포화를 막지만 비용이 크다.** 3 s는 `step`에서 D = 0이지만 장애 구간 결제 전부가 UNKNOWN이 된다. 연결 거부에서는 어떤 arm이든 결제가 UNKNOWN이 되고 복구 worker가 처리한다. 백분위 규칙은 꼬리 이벤트 두 건 때문에 10 s보다 긴 12 s를 줬고 도움이 되지 않았다. UNKNOWN 결제의 복구 부하(복구 worker가 PayPal에 다시 보내는 호출 수)는 칸 메타에만 있고 집계하지 않았다.
- **위치 서비스: 30/s부터 포화한다.** 20/s는 D = 0, 30/s는 D ≈ 0.99, 40/s는 1.0이고 경계는 PayPal과 같다(λ·h = 40 부근). 코드 읽기상 최소 세션이 위치 호출 3건이라 계약의 지속 10 세션/s는 위치 호출 30/s다. 옵트인 상한 20은 30/s와 40/s에서 D = 0으로 지켰다.

## 결정

1. PayPal: **ADR-0098의 옵트인 상한을 유지하고 다른 것은 바꾸지 않는다**(기본 0 유지). breaker, 적응형 한도, 읽기 timeout 변경은 도입하지 않는다. 기본값을 켜지 않는 이유: 평상시 in-flight는 도착률 × 평균 지연(8/s에서 평균 약 10, 최댓값 12~16)이고 상한 20은 16/s에서 거절한다. 운영 PayPal의 도착률과 지연을 이 환경에서 측정할 수 없어 안전한 고정값을 정할 근거가 없다.
2. 위치 서비스: **옵트인 상한을 추가한다**(`atlas.order-inventory-reservation.bulkhead.max-concurrent-calls`, 기본 0). `LocationCallBulkhead`(application 모듈 `service.impl`, 약 35줄)가 요청 스레드 호출만 세고 넘으면 위치 서비스에 보내기 전에 `RETRYABLE_UNKNOWN`(`INVENTORY_BUSY`)으로 거절한다. 복구 worker는 세지도 거절하지도 않는다. 근거: ADR-0099.
3. 크기 산정 공식: 평상시 in-flight ≈ 호출 도착률 × 평균 지연(PayPal Sandbox 평균 1.24 s: λ = 12/s에서 14.7~16.2, 16/s에서 21.1~22.5로 확인). 상한은 `도착률 × 평균 지연 × 1.5` 이상 Tomcat 최대 스레드(40) 미만으로 둔다(8/s에서 최소 약 15, 12/s에서 약 22). 16/s까지 거절 없이 쓰려면 30이 필요하다.

## 회귀 시험과 변이 확인

`HttpLocationClientTest`에 1개를 추가했다(MockWebServer, 서블릿 요청은 `RequestContextHolder`로 흉내 낸다). 상한 1에서 한 요청 스레드 호출이 응답을 기다리는 동안 (1) 다른 요청 스레드 호출은 `RETRYABLE_UNKNOWN`/`INVENTORY_BUSY`로 거절되고 서버가 받은 요청이 1건에 그치며, (2) 요청 밖 스레드(복구 worker)는 같은 시점에 위치 서버에 도달해 응답을 받고, (3) 거절 계수기가 1이다. 위치 호출은 HTTP 클라이언트 경계라 AGENTS.md의 기준대로 MockWebServer를 썼다(이 경계에는 PostgreSQL 상태가 없다).

| 코드 | 결과 |
|---|---|
| 수정 후(실제) | 2개 통과(기존 1개 + 신규 1개) |
| M0: 상한 없이 항상 위임 | 신규 1개 실패 |
| M1: 요청 밖 스레드도 상한 대상 | 신규 1개 실패 |
| M2: 거절하면서도 호출을 먼저 보냄 | 신규 1개 실패 |

변이마다 원본으로 복원했고 복원 뒤 파일이 원본과 같음을 확인했다.

### 전체 검증

`fullcheck.sh`(`clean check`) 한 번을 호스트 잠금 아래에서 실행했다(커밋 `98f8a23d` 기준): 종료 코드 0, `BUILD SUCCESSFUL in 6m 1s`, 테스트 결과 XML 190개 · 테스트 1,147개 · 실패 0. `atlas-order:test`와 `atlas-application:test`는 실제 실행되었고 나머지 모듈 테스트는 입력이 같아 빌드 캐시에서 가져왔다.

## 한계

- 운영 PayPal은 이 환경에서 잴 수 없다. Sandbox 지연도 시간대에 따라 다르다(꼬리 10 s 넘는 호출이 시간대 1·2에만 있었다). 가짜 서버의 지연 모델은 시간대 1~2의 연산당 150건 경험 분포라 꼬리가 실제보다 두껍다(10 s 넘는 호출 2/150).
- 구매자 승인이 필요한 authorize 성공·capture·refund·return 경로의 실제 지연과 부하는 측정하지 못했다. Sandbox 가맹점이 카드 처리 미활성이라 승인 없이 만들 수 없었다(`삭제한 파일의 내용`). 이 경로의 호출도 같은 한 지점(`PayPalCallBulkhead`)을 지나지만 부하로는 재지 않았다.
- 실행하지 않은 조합: A3 brownout, A1-20 λ = 12/s, 위치 상한을 켠 상태의 세션 24/s 같은 중간점. 위치 상한의 거절 계수기 값과 UNKNOWN 복구 worker 호출 수는 칸에서 수집하지 않았다.
- Tomcat busy 지표가 앞 24칸 안팎에서 나오지 않았다(PayPal in-flight와 D로 갈음).
- 호스트가 공유되어 시간 값은 맥락이다. 위치 칸의 응답은 2xx로 집계했고 본문 상태(예약 확정 여부)는 확인하지 않았다.

## 주장 표

| 주장 | 확신도 | 근거 |
|---|---|---|
| 실제 Sandbox 연산 5종의 p99는 2.1 s 이하이고 10 s 넘는 호출은 create order 2/300뿐이다 | confirmed(n = 299~300, 시간대 4개, Sandbox 한정) | 3a |
| 평상시 PayPal in-flight ≈ 도착률 × 평균 지연(8/s 9.6~11.8, 12/s 14.7~16.2, 16/s 21.1~22.5) | confirmed(n = 3씩, 가짜 서버 + Sandbox 분포) | `none`·`nonebig` 칸 |
| 구매자 결제 POST 경로에서도 PayPal 지연 8배(`step`)가 무관한 GET을 포화시킨다(D ≈ 0.71) | confirmed(n = 3) | A0 `step` |
| 정적 상한 20은 `step`·`cap`·`hang`에서 무관한 GET을 지킨다 | confirmed(n = 3씩) | A1-20 |
| 상한 20은 16/s에서 거절하고 상한 30은 거절하지 않는다 | confirmed(n = 3씩) | A1-20r16, A1-30r16 |
| 용량 저하(`cap`)에서 단순 breaker가 진동한다 | not confirmed(반증: OPEN 2회) | A3 `cap` |
| 점진 복구가 단순 breaker보다 낫다 | not confirmed(반증: 복구 11 s 대 2~4 s) | A3·A4 `cap` |
| 완료한 호출 기반 breaker는 첫 열림이 6~10 s라 포화 전에 막지 못한다 | confirmed(n = 3씩) | A3·A4 `step` |
| 적응형 한도가 정적 상한보다 doomed 스레드 시간을 30% 줄인다 | not confirmed(반증: 14% 감소) | A5 대 A1-20 `cap` |
| 백분위 규칙 timeout이 10 s보다 낫다 | not confirmed(반증: 규칙이 12 s, `step` D 0.66~0.73, `cap` D 0.48~0.52) | A2 |
| 읽기 timeout 3 s는 포화를 막지만 장애 구간 결제 전부를 UNKNOWN으로 만든다 | confirmed(n = 3, `step`) | A2b |
| brownout에서 breaker·적응형은 A0보다 성공 결제를 줄이고 이득이 없다 | confirmed(n = 3, A4·A5), A3 미실행 | brown |
| 연결 거부는 어떤 arm에서도 포화를 만들지 않는다 | confirmed(n = 3씩, A0·A1-20·A4) | refuse |
| 위치 호출 20/s는 포화하지 않고 30/s 이상은 포화한다(읽기 2 s) | confirmed(n = 3씩) | LOC20·LOC30·LOC40 |
| 위치 호출 상한 20은 30·40/s를 지킨다 | confirmed(n = 3씩), 거절 계수기는 미수집 | LOC30c20·LOC40c20 |
| 위치 호출 상한 20은 평상시에 거절하지 않는다 | not confirmed(한 칸에서 호출 21건 적음, 계수기 미수집) | LOCctlc20 |
| 운영 PayPal의 지연 분포와 평상시 동시 호출 수 | not confirmed(이 환경에서 측정 불가) | - |
| 호스트 부하가 낮은 칸만 쓴 결론이다 | local-only(오염 12칸은 같은 칸의 다른 반복과 방향이 같음) | 오염 칸 |

## 삭제한 파일의 내용

아래 구동·분석 파일은 저장소에 올리지 않았고 작업 폴더에 그대로 남아 있다(지우지 않았다). 결과의 정본은 위 표이고, 이 절은 같은 측정을 다시 하는 데 필요한 동작·매개변수·명령을 적는다. 파일 자체는 이 PR 이력에 없으므로 아래 명세로 다시 만든다.

### 실제 Sandbox 지연 측정기 (파이썬 약 60줄)

- 자격 증명(`PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET`, `PAYPAL_BASE_URL`)은 `.env.local`에서 이 프로세스 안에서만 읽고 출력·기록하지 않는다. HTTPS 연결 하나(keep-alive)로 순차 호출하고 호출 시작 간격을 최소 1/3 s로 제한한다(≤3 req/s).
- 사이클 하나: `POST /v1/oauth2/token`(Basic, `grant_type=client_credentials`) → `POST /v2/checkout/orders`(`intent=AUTHORIZE`, `purchase_units[0].amount` 10.00 USD, `payment_source.paypal.experience_context`의 `return_url`·`cancel_url`·`user_action=PAY_NOW`, 호출마다 새 `PayPal-Request-Id`) → `GET /v2/checkout/orders/{id}` → `POST /v2/checkout/orders/{id}/authorize`(미승인이라 422 `ORDER_NOT_APPROVED`) → `POST /v1/notifications/verify-webhook-signature`(가짜 서명). 시간대 하나 = 75 사이클, 시간대 4개를 약 23~28분 간격으로 실행(총 1,500호출: 연산 5종 × 약 300).
- 기록 열: 시각(epoch), 시간대, 연산, HTTP 상태(예외면 -1), 지연(s), 오류 문자열, 새 연결 여부, load1. 분석은 nearest-rank 백분위(`v[ceil(p·n)-1]`)와 3 s·5 s·10 s 초과 건수.
- 시도했으나 불가능했던 것: 구매자 승인 없이 authorize 성공·capture를 만드는 방법을 확인했다. 카드 `payment_source`로 주문 생성은 422 `PAYEE_NOT_ENABLED_FOR_CARD_PROCESSING`(Sandbox 가맹점이 카드 처리 미활성), 미승인 주문 authorize는 422 `ORDER_NOT_APPROVED`, 카드 `confirm-payment-source`는 422였다. 구매자 승인은 브라우저가 필요하다(`load-tests/paypal-real-fault`의 Playwright 흐름). 그래서 authorize 성공·capture·refund의 실제 지연은 측정하지 못했다.

### 용량 제한 가짜 서버 (파이썬 asyncio 약 280줄)

- 인자: `<PayPal 포트> <제어 포트> <위치 포트> <모델 json>`(127.0.0.1). 모델 json은 연산(`token`, `create_order`, `get_order`, `verify_webhook`)별 3a 지연 표본 배열이다(이 실험은 시간대 1~2의 연산당 150건으로 만들었다. 시간대 3~4는 모델 검증에만 썼다).
- 처리 모델: 서버마다 worker 수 W와 대기열 길이 Q. 요청이 오면 worker가 비어 있으면 즉시, 아니면 FIFO 대기열(길이 < Q)에 들어가고 Q가 차면 즉시 503(`OVERLOADED`). 서비스 시간은 연산별 모델에서 복원 추출한 값 × U(0.95, 1.05) × `mult`. 대기 중 클라이언트가 끊으면 `dead_queue=drop`에서 요청을 버리고 `dropped_dead`로 센다. 서비스 중 끊긴 호출은 `aborted`.
- 경로: `POST /v1/oauth2/token`(즉시 응답, 토큰 만료 32400 s), `GET /v1/latest`(환율, 즉시, KRW 1400), `POST /v2/checkout/orders`(`PAYER_ACTION_REQUIRED`와 `payer-action` 링크), `GET /v2/checkout/orders/{id}`, `POST /v1/notifications/verify-webhook-signature`(`SUCCESS`). 위치 서버는 고정 서비스 시간 `delay_ms`로 `/availability/…`(`{"available":100}`)와 예약(`RESERVED`, 2분 뒤 만료)을 응답.
- 제어 API(제어 포트 POST): `/ctl?workers=&queue=&mult=&err=&mode=ok|hang|refuse&dead_queue=drop|keep`, `/loc?workers=&queue=&delay_ms=&mode=`, `/reset`, `GET /stats`, `GET /calls`(호출별 JSON 줄: 서버, 도착 시각, 연산, 대기 s, 서비스 s, 결과 `ok|aborted|shed|err503|hang|dropped_dead`). `mode=hang`은 연결을 받고 3,600 s 응답하지 않고, `mode=refuse`는 수신 소켓을 닫는다. `err`는 서비스 전에 503을 돌려주는 확률.

### 시나리오와 매개변수

- 기준 설정(`none`): PayPal worker 16, 대기열 64, `mult=1`. 장애 시작 T = 5 s, 끝 = 25 s, 총 40 s + 6 s 추가 표집(복구 worker 호출 관찰).
- `step`: worker 1000, 대기열 1000(용량 사실상 무제한), 장애 구간 `mult=8`. `cap`: worker 16 → 4(대기열 64 유지, 용량 저하 → 대기 지연), 장애 끝에 16으로 복귀. `hang`: 장애 구간 `mode=hang`. `flap`: `mult=8`과 `mult=1`을 5 s씩 두 번 번갈아(5~10 s, 15~20 s 느림). `brown`: `err=0.3`, `mult=3`. `refuse`: 장애 구간 수신 소켓 닫음. 위치 `locslow`: 장애 구간 `delay_ms=5000`(읽기 상한 2 s라 h = 2 s), 위치 worker 64, 대기열 256.
- 앱 구성(칸마다 새 JVM, `-Xms1g -Xmx1g`): `atlas.payment.provider=paypal`, PayPal 기준 URL과 Frankfurter 기준 URL을 모두 가짜 서버로, `ATLAS_PAYPAL_LOCAL_PROXY_ENABLED=true`, 인증 시도 제한 상향(`ATLAS_AUTH_THROTTLE_*_LIMIT=1000000`: 구매자 로그인 180건이 한 출처에서 나옴), 복구 worker 초기 지연 `PT10S`, `management.server.port` 별도, `server.tomcat.mbeanregistry.enabled=true`, 프로파일 `observability`. 위치 칸만 `atlas.order-inventory-reservation.enabled=true`와 위치 endpoint, 그 밖은 false(true이면 checkout이 위치 서비스를 부른다. 구매자 칸은 로컬 재고).

### 시드와 부하 (파이썬 약 70줄 + k6 약 110줄)

- 시드: `POST /api/auth/signup`(이메일 `k6-exp109-<역할>-<i>@example.test`, 비밀번호는 실행마다 무작위, 12~128자), 메일함(Mailpit)에서 `one-time token:` 줄을 읽어 `POST /api/auth/verify-email`, Seller 1명 `POST /api/sellers`, 상품 1, SKU 1(`priceWon` 10000, `initialStock` 1,000,000), 구매자 180명. 시드 DB를 `atlas_seed`로 두고 칸마다 `CREATE DATABASE atlas_run TEMPLATE atlas_seed`.
- k6: `constant-arrival-rate` 세 개를 동시에 40 s: 카탈로그 `GET /api/products?limit=20` 20/s, 구매자 세션 8/s(`PUT /api/buyers/{ref}/cart/items/{seller}/{sku}` → `POST /api/buyers/{ref}/cart/checkout`(`Idempotency-Key` 호출마다 새 값) → `POST /api/checkouts/{id}/payment`), 장애 주입기(제어 API를 시각에 맞춰 호출, 시작 시각 `T0` 로그). 위치 칸은 세션 대신 `POST /api/internal/order-inventory/reservations`(`X-Atlas-Service-Token`, 호출마다 새 `Idempotency-Key`와 새 `orderId`)을 20·40/s로 보낸다. 결제 POST의 응답 `paymentState`(`AUTHORIZATION_PENDING` = 성공, `UNKNOWN`)를 별도 지표 `pay_dur`의 태그로 기록.
- 표집기: 1 Hz로 `/actuator/prometheus`에서 `tomcat_threads_busy_threads`, `tomcat_threads_current_threads`, `hikaricp_connections_active|pending`을, 가짜 서버 `/stats`를 JSON 줄로 기록. 칸 끝에 가짜 서버 `/calls`와 `payment_attempt`(`payment_phase`, `state`별 건수), `payment_recovery_job`(`status`별 건수)을 저장하고 `measure-lock.sh status`와 load1을 칸 시작·끝에 기록.
- 분석: 요청 시작 시각(완료 시각 − 지연)을 T0 기준으로 기준 구간(0~5 s)·장애 구간·복구 구간으로 나눈다. D = 장애 시작 + 3 s ~ 장애 끝 구간에서 (지연 > 1 s 또는 비2xx) 카탈로그 요청 비율. 복구 s = 장애 끝 이후 3 s 창에서 결제 성공 ≥ 90%(창당 ≥ 3건)가 되는 첫 시각까지. 오염 = 시작 load1 > 12 또는 시작·끝 `status`에 라벨 `res`·`pool110`(유휴, 이 호스트에서 항상 있던 다른 팀의 PostgreSQL) 외 컨테이너.

### 실험용 arm 구현 (자바 약 450줄, 한 클래스, 올리지 않음)

- 기준: `PayPalCallBulkhead`의 `call`/`run`(모든 PayPal HTTP 호출이 지나는 한 지점)을 `-Dexp.arm=A0|A1|A3|A4|A5|A6`으로 분기. 생성자 서명과 `max-concurrent-calls` 설정은 그대로. `RequestContextHolder.getRequestAttributes() == null`이면(요청 밖 스레드) 모든 arm이 통과시키고 통계에도 넣지 않는다. 거절은 `action.get()` 전에 `onRejected.run()` + `PayPalBusyException`.
- A3: 완료한 요청 스레드 호출 최근 10개(COUNT_BASED), 나쁨 = 예외 또는 ≥ `exp.slowMs`(2000 ms), 10개가 차고 나쁜 비율 ≥ 0.5이면 OPEN. OPEN 5 s(`exp.openMs`) 동안 전부 거절. 이후 HALF_OPEN에서 시험 호출 3개(`exp.halfOpenCalls`)만 통과, 나쁜 비율 < 0.5이면 CLOSED(창 초기화), 아니면 OPEN. 이전 상태에서 시작한 호출의 완료는 breaker가 무시(통계에는 센다).
- A4: A3의 열림 규칙. OPEN 뒤 RECOVER33(시험 호출 3개로 제한, 이후 호출 번호 % 3 == 0만 통과) → RECOVER67(번호 % 3 != 2) → RECOVER100(전부). 단계는 ≥ `exp.stepMs`(5 s)이고 완료 ≥ 3건이며 나쁜 비율 < 0.5일 때 올라간다. 단계 안에서 (완료 ≥ 3건이고 나쁜 비율 ≥ 0.5) 또는 나쁜 완료 ≥ `exp.stepBadAbs`(3)이면 다시 OPEN. RECOVER100 → CLOSED는 같은 조건. (설계 문서의 HALF_OPEN 상태 대신 OPEN → RECOVER33 직행이다.)
- A5: AIMD. 한도 초기 20, 최소 2, 최대 40. 나쁜 완료이면 한도 × 0.9(최대 `exp.cooldownMs` = 1000 ms에 한 번), 좋은 완료이고 시작 시 in-flight ≥ 한도/2이면 + 1/한도(CAS 루프). in-flight ≥ floor(한도)이면 거절. 한도가 정수로 바뀔 때만 `EXPSTATE ... LIMIT->n` 줄(200 ms에 1줄).
- A6: 정적 상한(설정값 20)의 permit을 먼저 잡고(breaker가 거절하면 반환) A4 breaker 적용.
- 관측: 상태 전이마다 `EXPSTATE <epoch ms> <arm> <FROM>-><TO> limit=<n>`, 1 s마다 데몬 스레드가 `EXPSTAT <epoch ms> inflight=<n> admitted= rejected= ok= bad= slow= state= limit= bad_ms= busy_ms=`(요청 스레드, 누적). `bad_ms` = 예외로 끝난 호출 지속 시간 합(doomed thread-seconds), `busy_ms` = 모든 완료 호출 지속 시간 합.
- 위치 서비스 칸의 상한(`LocationCallBulkhead`, 이 PR의 운영 코드)은 별도 jar(`atlas.order-inventory-reservation.bulkhead.max-concurrent-calls`)로 측정했다.

### 실행 명령 (삭제했으므로 이력에서 복원하지 않고 위 명세로 다시 만들어 실행한다)

```sh
# 호스트 잠금을 쥔 동안에만
bash .omp/scripts/measure-lock.sh acquire <태그> 40
docker run -d --name <이름> --label atlasexp=res -e POSTGRES_USER=atlas -e POSTGRES_PASSWORD=<로컬 전용> \
  -e POSTGRES_DB=atlas -p 127.0.0.1:<포트>:5432 postgres:18 -c max_connections=200 -c fsync=off
docker run -d --name <이름> --label atlasexp=res -p 127.0.0.1:<SMTP 포트>:1025 -p 127.0.0.1:<API 포트>:8025 axllent/mailpit:latest
# 가짜 서버
python3 fake3.py <PayPal 포트> <제어 포트> <위치 포트> model.json
# 앱(칸마다 새 프로세스)
ATLAS_PAYMENT_PROVIDER=paypal PAYPAL_BASE_URL=http://127.0.0.1:<PayPal 포트> ATLAS_PAYPAL_LOCAL_PROXY_ENABLED=true \
PAYPAL_CLIENT_ID=<가짜> PAYPAL_CLIENT_SECRET=<가짜> PAYPAL_WEBHOOK_ID=<가짜> ATLAS_EXCHANGE_RATE_PROVIDER=frankfurter \
ATLAS_AUTH_THROTTLE_ORIGIN_LIMIT=1000000 ATLAS_AUTH_THROTTLE_SUBJECT_LIMIT=1000000 ATLAS_PAYMENT_RECOVERY_INITIAL_DELAY=PT10S \
java -Xms1g -Xmx1g -Dexp.arm=<A0|A1|A3|A4|A5|A6> -jar <실험 jar> --server.port=<포트> --management.server.port=<관리 포트> \
  --spring.profiles.active=observability --server.tomcat.mbeanregistry.enabled=true \
  --atlas.payment.frankfurter.base-url=http://127.0.0.1:<PayPal 포트> \
  --atlas.payment.paypal.bulkhead.max-concurrent-calls=<A1·A6의 상한> --atlas.payment.paypal.read-timeout=<A2: PT12S, A2b: PT3S>
bash .omp/scripts/measure-lock.sh release <태그>
```
