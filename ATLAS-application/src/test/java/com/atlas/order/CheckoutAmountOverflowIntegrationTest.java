package com.atlas.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.atlas.cart.service.CartService;
import com.atlas.catalog.dto.request.CreateProductRequest;
import com.atlas.catalog.dto.request.RegisterSellerSkuRequest;
import com.atlas.catalog.service.ProductService;
import com.atlas.catalog.service.SellerService;
import com.atlas.order.service.CartCheckoutService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 결제 금액 계산이 넘칠 때 클라이언트 오류로 응답하는지 확인한다.
 *
 * <p>왜 필요한가: 장바구니 quantity 는 양수만 검사하고 상한이 없다. 가격 × 수량이 long 범위를
 * 넘으면 {@code Math.multiplyExact}/{@code Math.addExact} 가 {@link ArithmeticException} 을
 * 던진다. checkout 경로가 그것을 잡지 않으면 전역 폴백이 500 Internal Server Error 로
 * 응답한다. 클라이언트가 만든 입력 오류이므로 4xx 여야 한다.
 *
 * <p>카트 덧셈과 환불 경로는 이미 ArithmeticException 을 도메인 예외로 변환한다. 이 테스트는
 * checkout 경로에 같은 방어가 있는지 고정한다.
 */
@Testcontainers
@SpringBootTest
class CheckoutAmountOverflowIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6")
            .withDatabaseName("atlas")
            .withUsername("atlas")
            .withPassword("atlas");

    private static final long PRICE_WON = 500_000_000_000L;
    private static final int QUANTITY = Integer.MAX_VALUE;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("atlas.admission.expiry-poll-interval", () -> "PT24H");
        registry.add("atlas.orders.expiry-poll-interval", () -> "PT24H");
    }

    @Autowired private SellerService sellers;
    @Autowired private ProductService products;
    @Autowired private CartService carts;
    @Autowired private CartCheckoutService checkouts;

    /**
     * 가격 × 수량이 long 을 넘는 장바구니로 checkout 하면 도메인 오류(4xx)로 거절되어야 한다.
     *
     * <p>500_000_000_000 × 2,147,483,647 ≈ 1.07e21 > Long.MAX_VALUE(9.22e18) 이므로 반드시 넘친다.
     */
    @Test
    void checkoutRejectsAmountThatOverflowsLongWithAClientError() {
        // 전제: 이 가격×수량은 long 을 넘친다(1.07e21 > 9.22e18). 넘치지 않으면 테스트가 의미를 잃는다.
        assertThat(PRICE_WON).isGreaterThan(Long.MAX_VALUE / QUANTITY);

        UUID sellerId = sellers.createSeller().sellerId();
        var product = products.createProduct(sellerId,
                new CreateProductRequest("overflow-checkout-" + UUID.randomUUID(), null));
        String skuId = "overflow-" + UUID.randomUUID();
        products.registerSku(sellerId, product.productId(),
                new RegisterSellerSkuRequest(skuId, PRICE_WON, QUANTITY));

        String buyerRef = "overflow-buyer-" + UUID.randomUUID();
        carts.setItemQuantity(sellerId, buyerRef, skuId, QUANTITY);

        Throwable thrown = catchThrowable(
                () -> checkouts.checkout(buyerRef, "overflow-checkout-" + UUID.randomUUID()));

        assertThat(thrown)
                .as("금액 계산이 넘치면 ArithmeticException 이 아니라 도메인 오류여야 한다")
                .isNotNull()
                .isNotInstanceOf(ArithmeticException.class);
    }
}
