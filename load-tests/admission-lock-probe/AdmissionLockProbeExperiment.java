package com.atlas.experiment;

import com.atlas.admission.dto.request.AdmissionRequest;
import com.atlas.admission.dto.response.AdmissionResponse;
import com.atlas.admission.repository.QueueRepository;
import com.atlas.admission.service.AdmissionService;
import com.atlas.admission.service.CampaignService;
import com.atlas.catalog.dto.request.ProductRequest;
import com.atlas.catalog.dto.response.CatalogResponse;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.payment.PayPalIntegrationTestSupport;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * EXP-090 Part A. 실제 PostgreSQL에서 (1) 잠금 대기 초과·교착·statement timeout이 Spring 예외로 어떻게
 * 올라오는지, (2) 한 Seller-SKU에 join을 직렬로 300번, 동시 10·30 스레드로 30번씩 보냈을 때 지연을 잰다.
 * 일반 check에 넣지 않고 load-tests/admission-lock-probe/run.sh가 임시로 test 소스에 복사해 실행한다.
 * 지연은 공유 개발 머신 값이라 맥락으로만 쓴다.
 */
@Testcontainers
@SpringBootTest
class AdmissionLockProbeExperiment extends PayPalIntegrationTestSupport {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        properties.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
    }

    @Autowired private SellerService sellers;
    @Autowired private ProductService products;
    @Autowired private CampaignService campaigns;
    @Autowired private AdmissionService admission;
    @Autowired private QueueRepository queueRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;

    private record Queue(UUID sellerId, String skuId, UUID campaignId) {}

    private Queue queue() {
        CatalogResponse.Seller seller = sellers.createSeller();
        CatalogResponse.Product product = products.createProduct(seller.sellerId(),
                new ProductRequest.CreateProduct("probe " + UUID.randomUUID(), "d"));
        String skuId = "probe-" + UUID.randomUUID();
        products.registerSku(seller.sellerId(), product.productId(),
                new ProductRequest.RegisterSellerSku(skuId, 100, 100000));
        admission.configureSellerSkuPolicy(new AdmissionRequest.ConfigureSellerSkuPolicy(seller.sellerId(), skuId, 10000));
        AdmissionResponse.Campaign campaign = campaigns.create(
                new AdmissionRequest.CreateCampaign(seller.sellerId(), skuId, null));
        campaigns.open(campaign.campaignId());
        return new Queue(seller.sellerId(), skuId, campaign.campaignId());
    }

    private static String describe(Throwable failure) {
        StringBuilder text = new StringBuilder(failure.getClass().getSimpleName());
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                text.append(" sqlstate=").append(sql.getSQLState());
                break;
            }
        }
        List<String> chain = new ArrayList<>();
        for (Throwable cause = failure.getCause(); cause != null && chain.size() < 4; cause = cause.getCause()) {
            chain.add(cause.getClass().getSimpleName());
        }
        return text + " cause=" + chain;
    }

    @Test
    void probe() throws Exception {
        String outPath = System.getenv().getOrDefault("PROBE_OUT", "/tmp/atlas-admission-lock-probe.txt");
        try (PrintWriter out = new PrintWriter(new FileWriter(outPath, false), true)) {
            Queue q = queue();
            try (Connection holder = dataSource.getConnection()) {
                holder.setAutoCommit(false);
                holder.createStatement().execute("SELECT 1 FROM admission_counter WHERE seller_id = '" + q.sellerId()
                        + "' AND sku_id = '" + q.skuId() + "' FOR UPDATE");
                try {
                    new TransactionTemplate(transactionManager).execute(status -> {
                        jdbc.execute("SET LOCAL lock_timeout = '500ms'");
                        queueRepository.lockCounter(q.sellerId(), q.skuId());
                        return null;
                    });
                    out.println("jdbc-lock: no exception");
                } catch (Throwable failure) {
                    out.println("jdbc-lock: " + describe(failure));
                }
                long started = System.nanoTime();
                try {
                    new TransactionTemplate(transactionManager).execute(status -> {
                        jdbc.execute("SET LOCAL lock_timeout = '500ms'");
                        return admission.join(new AdmissionRequest.JoinQueue(q.campaignId(), q.skuId(),
                                "b-" + UUID.randomUUID(), "k-" + UUID.randomUUID()));
                    });
                    out.println("join-while-locked: no exception");
                } catch (Throwable failure) {
                    out.println("join-while-locked: " + describe(failure) + " ms=" + (System.nanoTime() - started) / 1_000_000);
                }
                holder.rollback();
            }
            try (Connection holder = dataSource.getConnection()) {
                holder.setAutoCommit(false);
                holder.createStatement().execute("SELECT 1 FROM campaign WHERE campaign_id = '" + q.campaignId() + "' FOR UPDATE");
                try {
                    new TransactionTemplate(transactionManager).execute(status -> {
                        jdbc.execute("SET LOCAL lock_timeout = '500ms'");
                        return campaigns.close(q.sellerId(), q.campaignId());
                    });
                    out.println("jpa-lock: no exception");
                } catch (Throwable failure) {
                    out.println("jpa-lock: " + describe(failure));
                }
                holder.rollback();
            }
            try {
                new TransactionTemplate(transactionManager).execute(status -> {
                    jdbc.execute("SET LOCAL statement_timeout = '200ms'");
                    return jdbc.queryForObject("SELECT pg_sleep(2)::text", String.class);
                });
                out.println("stmt-timeout: no exception");
            } catch (Throwable failure) {
                out.println("stmt-timeout: " + describe(failure));
            }
            Queue a = queue();
            Queue b = queue();
            CountDownLatch bothFirst = new CountDownLatch(2);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            List<Future<String>> deadlockResults = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Queue first = i == 0 ? a : b;
                Queue second = i == 0 ? b : a;
                deadlockResults.add(executor.submit(() -> {
                    try {
                        new TransactionTemplate(transactionManager).execute(status -> {
                            queueRepository.lockCounter(first.sellerId(), first.skuId());
                            bothFirst.countDown();
                            try {
                                bothFirst.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException interrupted) {
                                throw new IllegalStateException(interrupted);
                            }
                            queueRepository.lockCounter(second.sellerId(), second.skuId());
                            return null;
                        });
                        return "ok";
                    } catch (Throwable failure) {
                        return describe(failure);
                    }
                }));
            }
            for (Future<String> result : deadlockResults) {
                out.println("deadlock-tx: " + result.get(20, TimeUnit.SECONDS));
            }
            executor.shutdownNow();

            Queue hot = queue();
            List<Long> single = new ArrayList<>();
            for (int i = 0; i < 300; i++) {
                long started = System.nanoTime();
                admission.join(new AdmissionRequest.JoinQueue(hot.campaignId(), hot.skuId(), "s-" + i,
                        "sk-" + UUID.randomUUID()));
                single.add((System.nanoTime() - started) / 1000);
            }
            Collections.sort(single);
            out.println("join-single-us n=300 p50=" + single.get(150) + " p95=" + single.get(285) + " p99=" + single.get(297)
                    + " max=" + single.get(299));
            for (int threads : new int[] {10, 30}) {
                Queue contended = queue();
                ExecutorService pool = Executors.newFixedThreadPool(threads);
                List<Future<List<Long>>> results = new ArrayList<>();
                CountDownLatch go = new CountDownLatch(1);
                for (int t = 0; t < threads; t++) {
                    int id = t;
                    results.add(pool.submit(() -> {
                        go.await();
                        List<Long> latencies = new ArrayList<>();
                        for (int i = 0; i < 30; i++) {
                            long started = System.nanoTime();
                            admission.join(new AdmissionRequest.JoinQueue(contended.campaignId(), contended.skuId(),
                                    "c-" + id + "-" + i, "ck-" + UUID.randomUUID()));
                            latencies.add((System.nanoTime() - started) / 1000);
                        }
                        return latencies;
                    }));
                }
                long wallStarted = System.nanoTime();
                go.countDown();
                List<Long> all = new ArrayList<>();
                for (Future<List<Long>> result : results) {
                    all.addAll(result.get(120, TimeUnit.SECONDS));
                }
                long wallMs = (System.nanoTime() - wallStarted) / 1_000_000;
                pool.shutdownNow();
                Collections.sort(all);
                int n = all.size();
                out.println("join-contended threads=" + threads + " n=" + n + " wallMs=" + wallMs + " p50us=" + all.get(n / 2)
                        + " p95us=" + all.get((int) (n * 0.95)) + " p99us=" + all.get((int) (n * 0.99)) + " maxus=" + all.get(n - 1));
            }
        }
    }
}
