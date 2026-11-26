# EXP-109: 회로 차단·timeout 단축·적응형 동시 한도를 외부 사례와 실측으로 다시 판단한다

- 상태: 사전 등록 단계(이 절의 가설과 반증 조건은 측정 실행 전 커밋으로 고정한다). 결과·결정 절은 측정 뒤에 같은 문서에 추가한다.
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

### 위치 서비스 상한 정당화 규칙 (3c)

옵트인 상한을 구현하려면 (1) 위치 서비스 칸에서 계약 안 도착률(ADR-0084의 버스트 30 세션/s 이하에서 세션당 위치 호출이 1건 이상)의 어떤 점에서 D ≥ 0.20이 2/3 이상이어야 한다. 20/s에서 D < 0.05이고 40/s에서만 포화하면 포화 문턱 λ·h = 40은 위치 호출 20/s이고 버스트 30 세션/s는 그보다 크므로, 세션당 호출이 1건 이상인지 코드로 확인해 정당화 여부를 정한다. (2) 구현은 요청 스레드만 세고 worker(`LocationReservationServiceImpl.recoverDue`, `LocationRestockServiceImpl`)는 상한 대상이 아니며 거절은 보내기 전 `definitely-not-sent`로 분류돼 기존 복구 경로로 수렴해야 한다.

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
