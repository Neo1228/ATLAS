package com.atlas.catalog.repository;

import com.atlas.catalog.persistence.entity.Product;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, UUID> {
    boolean existsByProductIdAndSellerId(UUID productId, UUID sellerId);
    List<Product> findAllBySellerId(UUID sellerId);
    List<Product> findAllByProductIdIn(List<UUID> productIds);

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
            SELECT p.product_id, p.seller_id, p.name, p.description
            FROM product_listing p CROSS JOIN cursor_key c
            WHERE (CAST(:sellerId AS uuid) IS NULL OR p.seller_id = CAST(:sellerId AS uuid))
                AND (CAST(:keyword AS text) = '' OR position(CAST(:keyword AS text) in lower(p.name)) > 0)
                AND (CAST(:cursorSeller AS uuid) IS NULL OR p.seller_sort_key > c.seller_sort_key
                    OR (p.seller_sort_key = c.seller_sort_key AND p.product_sort_key > c.product_sort_key))
            ORDER BY p.seller_sort_key, p.product_sort_key
            LIMIT :limit
            """, nativeQuery = true)
    List<Product> findListingPage(@Param("sellerId") UUID sellerId, @Param("keyword") String keyword,
            @Param("cursorSeller") UUID cursorSeller, @Param("cursorProduct") UUID cursorProduct,
            @Param("limit") int limit);
}
