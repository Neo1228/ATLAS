package com.atlas.application.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atlas.admission.dto.request.AdmissionRequest;
import com.atlas.admission.dto.response.AdmissionResponse;
import com.atlas.admission.repository.QueueRepository;
import com.atlas.admission.service.CampaignService;
import com.atlas.catalog.dto.request.ProductRequest;
import com.atlas.catalog.dto.response.CatalogResponse;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.payment.PayPalIntegrationTestSupport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
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
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 실제 PostgreSQL이 낸 잠금 대기 초과(55P03)와 교착(40P01)이 Spring을 거쳐 어떤 예외로 올라오는지, 그 예외가
 * {@link GlobalExceptionHandler}에서 500이 아니라 503 + Retry-After가 되는지 확인한다(ADR-0083).
 * JdbcTemplate 경로는 {@link UncategorizedSQLException}, JPA 경로는 {@link PessimisticLockingFailureException}이다.
 */
@Testcontainers
@SpringBootTest
class LockFailureMappingIntegrationTest extends PayPalIntegrationTestSupport {
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
    @Autowired private QueueRepository queueRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;

    /** 운영 application.yml의 기본 접속 옵션을 실제 연결에 걸어 lock_timeout 5초가 서버에 적용되는지 확인한다. */
    @Test
    void defaultConnectionOptionsSetLockTimeoutBelowStatementTimeout() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("production", new FileSystemResource("src/main/resources/application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        String defaultUrl = environment.getProperty("spring.datasource.url");
        String options = defaultUrl.substring(defaultUrl.indexOf("options="));
        String baseUrl = POSTGRES.getJdbcUrl();
        String url = baseUrl + (baseUrl.contains("?") ? "&" : "?") + options;

        try (Connection connection = DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
                ResultSet lockTimeout = connection.createStatement().executeQuery("SHOW lock_timeout")) {
            assertThat(lockTimeout.next()).isTrue();
            assertThat(lockTimeout.getString(1)).isEqualTo("5s");
            try (ResultSet statementTimeout = connection.createStatement().executeQuery("SHOW statement_timeout")) {
                assertThat(statementTimeout.next()).isTrue();
                assertThat(statementTimeout.getString(1)).isEqualTo("30s");
            }
        }
    }

    @Test
    void jdbcLockTimeoutBecomesServiceUnavailable() throws Exception {
        SellerSku sku = registerSku();
        UUID sellerId = sku.sellerId();
        queueRepository.createCounterIfAbsent(sellerId, sku.skuId());

        RuntimeException failure;
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            holder.createStatement().execute("SELECT 1 FROM admission_counter WHERE seller_id = '" + sellerId
                    + "' AND sku_id = '" + sku.skuId() + "' FOR UPDATE");
            failure = failureOf(() -> new TransactionTemplate(transactionManager).execute(status -> {
                jdbc.execute("SET LOCAL lock_timeout = '200ms'");
                return queueRepository.lockCounter(sellerId, sku.skuId());
            }));
            holder.rollback();
        }

        assertThat(failure).isInstanceOf(UncategorizedSQLException.class);
        assertServiceUnavailable(failure);
    }

    @Test
    void jpaLockTimeoutBecomesServiceUnavailable() throws Exception {
        SellerSku sku = registerSku();
        AdmissionResponse.Campaign campaign = campaigns.create(
                new AdmissionRequest.CreateCampaign(sku.sellerId(), sku.skuId(), null));

        RuntimeException failure;
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            holder.createStatement().execute(
                    "SELECT 1 FROM campaign WHERE campaign_id = '" + campaign.campaignId() + "' FOR UPDATE");
            failure = failureOf(() -> new TransactionTemplate(transactionManager).execute(status -> {
                jdbc.execute("SET LOCAL lock_timeout = '200ms'");
                return campaigns.close(sku.sellerId(), campaign.campaignId());
            }));
            holder.rollback();
        }

        assertThat(failure).isInstanceOf(PessimisticLockingFailureException.class);
        assertServiceUnavailable(failure);
    }

    @Test
    void deadlockBecomesServiceUnavailableForExactlyOneTransaction() throws Exception {
        SellerSku first = registerSku();
        SellerSku second = registerSku();
        queueRepository.createCounterIfAbsent(first.sellerId(), first.skuId());
        queueRepository.createCounterIfAbsent(second.sellerId(), second.skuId());
        CountDownLatch bothHoldTheirFirstLock = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<RuntimeException>> attempts = new ArrayList<>();
            attempts.add(executor.submit(() -> lockInOrder(first, second, bothHoldTheirFirstLock)));
            attempts.add(executor.submit(() -> lockInOrder(second, first, bothHoldTheirFirstLock)));

            List<RuntimeException> failures = new ArrayList<>();
            for (Future<RuntimeException> attempt : attempts) {
                RuntimeException failure = attempt.get(30, TimeUnit.SECONDS);
                if (failure != null) {
                    failures.add(failure);
                }
            }

            // PostgreSQL은 교착한 두 트랜잭션 중 하나만 중단하고 다른 하나는 이어서 성공한다.
            assertThat(failures).hasSize(1);
            assertThat(failures.get(0)).isInstanceOf(PessimisticLockingFailureException.class);
            assertServiceUnavailable(failures.get(0));
        } finally {
            executor.shutdownNow();
        }
    }

    private SellerSku registerSku() {
        CatalogResponse.Seller seller = sellers.createSeller();
        CatalogResponse.Product product = products.createProduct(seller.sellerId(),
                new ProductRequest.CreateProduct("lock mapping " + UUID.randomUUID(), "description"));
        String skuId = "lock-mapping-" + UUID.randomUUID();
        products.registerSku(seller.sellerId(), product.productId(), new ProductRequest.RegisterSellerSku(skuId, 100, 5));
        return new SellerSku(seller.sellerId(), skuId);
    }

    private record SellerSku(UUID sellerId, String skuId) {}

    private RuntimeException lockInOrder(SellerSku firstSku, SellerSku secondSku, CountDownLatch bothHoldTheirFirstLock) {
        return failureOf(() -> new TransactionTemplate(transactionManager).execute(status -> {
            queueRepository.lockCounter(firstSku.sellerId(), firstSku.skuId());
            bothHoldTheirFirstLock.countDown();
            try {
                assertThat(bothHoldTheirFirstLock.await(10, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return queueRepository.lockCounter(secondSku.sellerId(), secondSku.skuId());
        }));
    }

    private static RuntimeException failureOf(Runnable action) {
        try {
            action.run();
            return null;
        } catch (RuntimeException exception) {
            return exception;
        }
    }

    private static void assertServiceUnavailable(RuntimeException failure) throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new FailingController(failure))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        mockMvc.perform(get("/fail"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }

    @RestController
    private static final class FailingController {
        private final RuntimeException failure;

        private FailingController(RuntimeException failure) {
            this.failure = failure;
        }

        @GetMapping("/fail")
        void fail() {
            throw failure;
        }
    }
}
