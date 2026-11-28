package com.atlas.catalog.repository;

import com.atlas.catalog.persistence.entity.Product;
import com.atlas.catalog.persistence.projection.ProductChannelAvailability;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select product from Product product where product.productId = :productId and product.sellerId = :sellerId")
    Optional<Product> findByProductIdAndSellerIdForUpdate(@Param("productId") UUID productId,
            @Param("sellerId") UUID sellerId);
    List<Product> findAllByProductIdIn(List<UUID> productIds);

    @Query(value = """
            SELECT product_id AS "productId", sales_channel AS "salesChannel"
            FROM product_channel_availability
            WHERE product_id IN (:productIds) AND available = TRUE
            """, nativeQuery = true)
    List<ProductChannelAvailability> findAvailableChannelsByProductIdIn(
            @Param("productIds") List<UUID> productIds);

    default List<Product> findListingPage(UUID sellerId, UUID categoryId, String keyword, UUID cursorSeller,
            UUID cursorProduct, int limit) {
        return findListingPageByScope(sellerId, categoryId, keyword, cursorSeller, cursorProduct, limit,
                null, null, Instant.EPOCH, false);
    }

    default List<Product> findListingPage(UUID sellerId, UUID categoryId, String keyword, UUID cursorSeller,
            UUID cursorProduct, int limit, Long minPriceWon, Long maxPriceWon, Instant asOf) {
        return findListingPageByScope(sellerId, categoryId, keyword, cursorSeller, cursorProduct, limit,
                minPriceWon, maxPriceWon, asOf, false);
    }

    @Query(value = """
            SELECT p.*
            FROM product p
            WHERE p.product_id = :productId
                AND p.status = 'ACTIVE'
                AND EXISTS (SELECT 1 FROM product_channel_availability c
                    WHERE c.product_id = p.product_id AND c.sales_channel = 'ONLINE_STORE' AND c.available = TRUE)
            """, nativeQuery = true)
    Optional<Product> findPublicOrderableByProductId(@Param("productId") UUID productId);
    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM product p
                WHERE p.product_id = :productId
                    AND p.status = 'ACTIVE'
                    AND EXISTS (SELECT 1 FROM product_channel_availability c
                        WHERE c.product_id = p.product_id AND c.sales_channel = 'ONLINE_STORE' AND c.available = TRUE)
            )
            """, nativeQuery = true)
    boolean existsPublicOrderableByProductId(@Param("productId") UUID productId);
    @Query(value = """
            SELECT DISTINCT p.product_id
            FROM product p
            JOIN product_channel_availability c ON c.product_id = p.product_id
            WHERE p.product_id IN (:productIds)
                AND p.status = 'ACTIVE'
                AND c.sales_channel = :channel AND c.available = TRUE
            """, nativeQuery = true)
    List<UUID> findOrderableProductIds(@Param("productIds") List<UUID> productIds,
            @Param("channel") String channel);
    @Query(value = """
            WITH RECURSIVE product_listing AS (
                SELECT p.*,
                    encode(set_byte(set_byte(uuid_send(p.seller_id), 0,
                        get_byte(uuid_send(p.seller_id), 0) # 128), 8,
                        get_byte(uuid_send(p.seller_id), 8) # 128), 'hex') COLLATE "C" AS seller_sort_key,
                    encode(set_byte(set_byte(uuid_send(p.product_id), 0,
                        get_byte(uuid_send(p.product_id), 0) # 128), 8,
                        get_byte(uuid_send(p.product_id), 8) # 128), 'hex') COLLATE "C" AS product_sort_key
                FROM product p
            ),
            effective_sku_price AS (
                SELECT sku.product_id,
                       CASE WHEN sku.sale_price_won IS NOT NULL
                                 AND sku.sale_starts_at <= CAST(:asOf AS timestamptz)
                                 AND CAST(:asOf AS timestamptz) < sku.sale_ends_at
                            THEN sku.sale_price_won ELSE sku.price_won END AS effective_price
                FROM seller_sku sku
            ),
            category_tree(category_id) AS (
                SELECT CAST(:categoryId AS uuid) WHERE CAST(:categoryId AS uuid) IS NOT NULL
                UNION
                SELECT child.category_id FROM product_category child
                JOIN category_tree parent ON child.parent_category_id = parent.category_id
                WHERE child.seller_id = CAST(:sellerId AS uuid)
            ),
            cursor_key AS (
                SELECT
                    encode(set_byte(set_byte(uuid_send(CAST(:cursorSeller AS uuid)), 0,
                        get_byte(uuid_send(CAST(:cursorSeller AS uuid)), 0) # 128), 8,
                        get_byte(uuid_send(CAST(:cursorSeller AS uuid)), 8) # 128), 'hex') COLLATE "C"
                        AS seller_sort_key,
                    encode(set_byte(set_byte(uuid_send(CAST(:cursorProduct AS uuid)), 0,
                        get_byte(uuid_send(CAST(:cursorProduct AS uuid)), 0) # 128), 8,
                        get_byte(uuid_send(CAST(:cursorProduct AS uuid)), 8) # 128), 'hex') COLLATE "C"
                        AS product_sort_key
            )
            SELECT p.product_id, p.seller_id, p.primary_category_id, p.name, p.description, p.status
            FROM product_listing p CROSS JOIN cursor_key c
            WHERE (CAST(:sellerId AS uuid) IS NULL OR p.seller_id = CAST(:sellerId AS uuid))
                AND (CAST(:managementScope AS boolean) OR (p.status = 'ACTIVE'
                    AND EXISTS (SELECT 1 FROM product_channel_availability c
                        WHERE c.product_id = p.product_id AND c.sales_channel = 'ONLINE_STORE'
                            AND c.available = TRUE)))
                AND (CAST(:categoryId AS uuid) IS NULL OR p.primary_category_id IN
                    (SELECT category_id FROM category_tree) OR EXISTS (
                        SELECT 1 FROM product_category_assignment assignment
                        WHERE assignment.seller_id = p.seller_id AND assignment.product_id = p.product_id
                            AND assignment.category_id IN (SELECT category_id FROM category_tree)))
                -- `position(... in lower(name))` cannot use a trigram index, so every
                -- keyword search scanned the whole product table in listing order
                -- until LIMIT rows matched (measured 409 ms over 100k rows). The
                -- LIKE form is equivalent for our normalization (keyword is already
                -- trimmed and lowercased, see ProductServiceImpl) and is indexable.
                -- LIKE metacharacters in the user keyword are escaped so they keep
                -- matching literally, exactly as they did with position().
                AND (CAST(:keyword AS text) = ''
                    OR lower(p.name) LIKE '%' || replace(replace(replace(
                            CAST(:keyword AS text), '\\', '\\\\'), '%', '\\%'), '_', '\\_')
                        || '%' ESCAPE '\\')
            AND (CAST(:minPriceWon AS bigint) IS NULL AND CAST(:maxPriceWon AS bigint) IS NULL
                OR EXISTS (
                    SELECT 1
                    FROM effective_sku_price price
                    WHERE price.product_id = p.product_id
                      AND (CAST(:minPriceWon AS bigint) IS NULL
                           OR price.effective_price >= CAST(:minPriceWon AS bigint))
                      AND (CAST(:maxPriceWon AS bigint) IS NULL
                           OR price.effective_price <= CAST(:maxPriceWon AS bigint))
                ))
                AND (CAST(:cursorSeller AS uuid) IS NULL OR p.seller_sort_key > c.seller_sort_key
                    OR (p.seller_sort_key = c.seller_sort_key AND p.product_sort_key > c.product_sort_key))
            ORDER BY CASE WHEN CAST(:sellerId AS uuid) IS NOT NULL THEN NULL ELSE p.seller_sort_key END,
                     p.product_sort_key
            LIMIT :limit
            """, nativeQuery = true)
    List<Product> findListingPageByScope(@Param("sellerId") UUID sellerId, @Param("categoryId") UUID categoryId,
            @Param("keyword") String keyword, @Param("cursorSeller") UUID cursorSeller,
            @Param("cursorProduct") UUID cursorProduct, @Param("limit") int limit,
            @Param("minPriceWon") Long minPriceWon, @Param("maxPriceWon") Long maxPriceWon,
            @Param("asOf") Instant asOf, @Param("managementScope") boolean managementScope);

    /**
     * 공개 구매 가능 상품의 관련도 검색(ADR-0109, EXP-120). V14의 {@code catalog_search_terms}가 조사와 요청 동사를 떼고
     * {@code catalog_search_grams}가 한글 2-gram·영숫자 3-gram을 만든다. 검색어 n-gram n개 중 40% 이상({@code min_hits}
     * = ⌈2n/5⌉)이 상품명·설명에 있어야 후보가 되고, 상품명 겹침에 2배 가중을 둔 겹침 수로 정렬한다. 동점은 이름이 짧은(더
     * 구체적인) 상품이 앞선다. 검색어 n-gram은 MATERIALIZED로 한 번만 계산한다(일반 계획에서 행마다 다시 계산하지 않게).
     *
     * <p>후보 탐색(EXP-123): {@code min_hits}개 이상 겹치는 상품은 비둘기집 원리로 아무 {@code n - min_hits + 1}개
     * n-gram 중 하나를 반드시 가진다. 그래서 GIN 조회는 전체 n-gram이 아니라 {@code pg_stats}의 원소 빈도로 고른 가장
     * 드문 {@code n - min_hits + 1}개({@code probe})로만 한다. 결과 집합은 빈도와 무관하게 전체 n-gram 조회와 같고,
     * 통계가 낡거나 없으면(ANALYZE 전) 덜 줄일 뿐이다. 빈도 목록에 없는 n-gram은 목록 최소 빈도보다 드물다고 보고 먼저 고른다.
     */
    @Query(value = """
            WITH q AS MATERIALIZED (
                SELECT grams, (2 * cardinality(grams) + 4) / 5 AS min_hits
                FROM (SELECT catalog_search_grams(catalog_search_terms(CAST(:query AS text))) AS grams) t
            ),
            probe AS MATERIALIZED (
                SELECT COALESCE(array_agg(r.g), '{}') AS grams
                FROM (
                    SELECT g
                    FROM q CROSS JOIN unnest(q.grams) AS g
                    LEFT JOIN (
                        SELECT e.elem, e.freq
                        FROM pg_stats s
                        CROSS JOIN unnest(CAST(CAST(s.most_common_elems AS text) AS text[]),
                                          s.most_common_elem_freqs) AS e(elem, freq)
                        WHERE s.schemaname = current_schema() AND s.tablename = 'product'
                            AND s.attname = 'search_grams' AND NOT s.inherited
                    ) f ON f.elem = g
                    ORDER BY f.freq NULLS FIRST, g
                    LIMIT (SELECT cardinality(grams) - min_hits + 1 FROM q)
                ) r
            ),
            candidate AS (
                SELECT p.product_id, p.seller_id, p.primary_category_id, p.name, p.description, p.status,
                       cardinality(p.search_name_grams) AS name_size,
                       (SELECT count(*) FROM unnest(q.grams) g WHERE g = ANY (p.search_grams)) AS hits,
                       (SELECT count(*) FROM unnest(q.grams) g WHERE g = ANY (p.search_name_grams)) AS name_hits,
                       q.min_hits
                FROM product p CROSS JOIN q CROSS JOIN probe
                WHERE p.search_grams && probe.grams
                    AND p.status = 'ACTIVE'
                    AND EXISTS (SELECT 1 FROM product_channel_availability c
                        WHERE c.product_id = p.product_id AND c.sales_channel = 'ONLINE_STORE' AND c.available = TRUE)
                    AND (CAST(:minPriceWon AS bigint) IS NULL AND CAST(:maxPriceWon AS bigint) IS NULL
                        OR EXISTS (
                            SELECT 1 FROM seller_sku sku
                            WHERE sku.product_id = p.product_id
                              AND (CASE WHEN sku.sale_price_won IS NOT NULL
                                             AND sku.sale_starts_at <= CAST(:asOf AS timestamptz)
                                             AND CAST(:asOf AS timestamptz) < sku.sale_ends_at
                                        THEN sku.sale_price_won ELSE sku.price_won END)
                                  BETWEEN COALESCE(CAST(:minPriceWon AS bigint), 0)
                                      AND COALESCE(CAST(:maxPriceWon AS bigint), 9223372036854775807)))
            )
            SELECT product_id, seller_id, primary_category_id, name, description, status
            FROM candidate
            WHERE hits >= min_hits
            ORDER BY 2 * name_hits + hits DESC, name_size, product_id
            LIMIT :limit
            """, nativeQuery = true)
    List<Product> searchPublic(@Param("query") String query, @Param("minPriceWon") Long minPriceWon,
            @Param("maxPriceWon") Long maxPriceWon, @Param("asOf") Instant asOf, @Param("limit") int limit);
}
