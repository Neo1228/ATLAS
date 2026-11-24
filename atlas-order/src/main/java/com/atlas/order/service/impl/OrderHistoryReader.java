package com.atlas.order.service.impl;

import com.atlas.order.dto.response.OrderHistoryPageResponse;
import com.atlas.order.exception.OrderException;
import com.atlas.order.persistence.entity.PurchaseOrder;
import com.atlas.order.repository.OrderRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntFunction;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 구매자·판매자 주문 이력의 keyset 페이지 조회. 두 조회는 정렬 키({@code createdAt DESC, orderId DESC})와
 * cursor 형식이 같고 소유자 조건만 다르므로 한도 검증, 페이지 조립, cursor 인코딩을 한 곳에서 처리한다.
 */
final class OrderHistoryReader {
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;

    private final OrderRepository repository;
    private final TransactionTemplate transactions;

    OrderHistoryReader(OrderRepository repository, TransactionTemplate transactions) {
        this.repository = Objects.requireNonNull(repository);
        this.transactions = Objects.requireNonNull(transactions);
    }

    OrderHistoryPageResponse buyerHistory(String buyerRef, Integer requestedLimit, String cursor) {
        if (buyerRef == null || buyerRef.isBlank()) {
            throw new OrderException(OrderException.Code.INVALID_REQUEST, "Buyer reference is required");
        }
        return page(requestedLimit, cursor,
                fetch -> repository.findByBuyerRefOrderByCreatedAtDescOrderIdDesc(buyerRef, PageRequest.of(0, fetch)),
                (position, fetch) -> repository.findBuyerOrdersBefore(
                        buyerRef, position.createdAt(), position.orderId(), PageRequest.of(0, fetch)));
    }

    OrderHistoryPageResponse sellerHistory(UUID sellerId, Integer requestedLimit, String cursor) {
        if (sellerId == null) {
            throw new OrderException(OrderException.Code.INVALID_REQUEST, "Seller is required");
        }
        return page(requestedLimit, cursor,
                fetch -> repository.findBySellerIdOrderByCreatedAtDescOrderIdDesc(sellerId, PageRequest.of(0, fetch)),
                (position, fetch) -> repository.findSellerOrdersBefore(
                        sellerId, position.createdAt(), position.orderId(), PageRequest.of(0, fetch)));
    }

    private OrderHistoryPageResponse page(Integer requestedLimit, String cursor,
            IntFunction<List<PurchaseOrder>> firstPage, AfterCursor nextPage) {
        int limit = requestedLimit == null ? DEFAULT_PAGE_SIZE : requestedLimit;
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new OrderException(OrderException.Code.INVALID_REQUEST, "Order history limit is out of range");
        }
        Cursor position = decode(cursor);
        int fetch = limit + 1;
        List<PurchaseOrder> fetched = Objects.requireNonNull(transactions.execute(status -> position == null
                ? firstPage.apply(fetch)
                : nextPage.fetch(position, fetch)));
        boolean hasNext = fetched.size() > limit;
        List<PurchaseOrder> page = hasNext ? fetched.subList(0, limit) : fetched;
        List<OrderHistoryPageResponse.Entry> orders = page.stream()
                .map(order -> new OrderHistoryPageResponse.Entry(order.orderId(), order.checkoutId(),
                        order.sellerId(), order.source(), order.state(), order.createdAt()))
                .toList();
        return new OrderHistoryPageResponse(orders, hasNext ? encode(page.get(page.size() - 1)) : null);
    }

    private static Cursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", -1);
            if (parts.length != 3 || !"v1".equals(parts[0])) {
                throw new IllegalArgumentException("Malformed cursor");
            }
            return new Cursor(Instant.parse(parts[1]), UUID.fromString(parts[2]));
        } catch (RuntimeException exception) {
            throw new OrderException(OrderException.Code.INVALID_REQUEST, "Invalid order history cursor");
        }
    }

    private static String encode(PurchaseOrder order) {
        String value = "v1|" + order.createdAt() + "|" + order.orderId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @FunctionalInterface
    private interface AfterCursor {
        List<PurchaseOrder> fetch(Cursor position, int fetchSize);
    }

    private record Cursor(Instant createdAt, UUID orderId) { }
}
