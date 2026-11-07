package com.atlas.catalog.repository;

import com.atlas.catalog.persistence.entity.Product;
import com.atlas.catalog.persistence.projection.ProductChannelAvailability;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, UUID> {
    boolean existsByProductIdAndSellerId(UUID productId, UUID sellerId);
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
        return findListingPageByScope(sellerId, categoryId, keyword, cursorSeller, cursorProduct, limit, false);
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
            WITH product_listing AS (
                SELECT p.*,
                    encode(set_byte(set_byte(uuid_send(p.seller_id), 0,
                        get_byte(uuid_send(p.seller_id), 0) # 128), 8,
                        get_byte(uuid_send(p.seller_id), 8) # 128), 'hex') COLLATE "C" AS seller_sort_key,
                    encode(set_byte(set_byte(uuid_send(p.product_id), 0,
                        get_byte(uuid_send(p.product_id), 0) # 128), 8,
                        get_byte(uuid_send(p.product_id), 8) # 128), 'hex') COLLATE "C" AS product_sort_key
                FROM product p
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
            SELECT p.product_id, p.seller_id, p.category_id, p.name, p.description, p.status
            FROM product_listing p CROSS JOIN cursor_key c
            WHERE (CAST(:sellerId AS uuid) IS NULL OR p.seller_id = CAST(:sellerId AS uuid))
                AND (CAST(:managementScope AS boolean) OR (p.status = 'ACTIVE'
                    AND EXISTS (SELECT 1 FROM product_channel_availability c
                        WHERE c.product_id = p.product_id AND c.sales_channel = 'ONLINE_STORE'
                            AND c.available = TRUE)))
                AND (CAST(:categoryId AS uuid) IS NULL OR p.category_id = CAST(:categoryId AS uuid))
                AND (CAST(:keyword AS text) = '' OR position(CAST(:keyword AS text) in lower(p.name)) > 0)
                AND (CAST(:cursorSeller AS uuid) IS NULL OR p.seller_sort_key > c.seller_sort_key
                    OR (p.seller_sort_key = c.seller_sort_key AND p.product_sort_key > c.product_sort_key))
            ORDER BY p.seller_sort_key, p.product_sort_key
            LIMIT :limit
            """, nativeQuery = true)
    List<Product> findListingPageByScope(@Param("sellerId") UUID sellerId, @Param("categoryId") UUID categoryId,
            @Param("keyword") String keyword, @Param("cursorSeller") UUID cursorSeller,
            @Param("cursorProduct") UUID cursorProduct, @Param("limit") int limit,
            @Param("managementScope") boolean managementScope);
}
