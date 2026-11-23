package com.atlas.catalog.repository;

import com.atlas.catalog.persistence.projection.ProductListingRow;
import java.time.Instant;
import java.sql.Array;
import java.sql.Timestamp;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 목록 조회용 읽기 모델을 유지합니다.
 *
 * <p>목록 한 페이지를 만들 때 원본 3개 테이블을 다시 읽으면 buffer 1,132 를 씁니다.
 * 이 투영을 쓰면 같은 페이지가 buffer 103 으로 끝납니다(로컬 2M 상품 실측,
 * 이슈 #389).
 *
 * <p>갱신은 카탈로그 쓰기 트랜잭션 안에서 호출합니다. 별도 배치가 아니라 같은
 * 트랜잭션이어야 상품 변경과 투영이 어긋나지 않습니다.
 *
 * <p>투영이 낡아도 결제·재고 판정에는 영향이 없습니다. 상세 조회와 주문 가능 판정은
 * 원본 테이블을 계속 보기 때문입니다.
 */
@Repository
public class ProductListingProjectionRepository {
    private final JdbcTemplate jdbc;

    public ProductListingProjectionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 상품 하나의 투영을 다시 계산해 저장합니다.
     *
     * <p>upsert 를 쓰는 이유: 상품 생성·상태 변경·채널 토글·카테고리 변경이 모두
     * 이 메서드 하나를 부르므로, 행이 있는지 없는지 호출자가 판단하지 않게 합니다.
     */
    public void refresh(UUID productId, Instant now) {
        jdbc.update("""
                INSERT INTO product_listing_projection (
                    product_id, seller_id, status, primary_category_id, channels, categories, updated_at
                )
                SELECT p.product_id,
                       p.seller_id,
                       p.status,
                       p.primary_category_id,
                       COALESCE((SELECT array_agg(c.sales_channel ORDER BY c.ctid)
                                 FROM product_channel_availability c
                                 WHERE c.product_id = p.product_id AND c.available), '{}'),
                       COALESCE((SELECT array_agg(a.category_id ORDER BY a.category_id)
                                 FROM product_category_assignment a
                                 WHERE a.product_id = p.product_id), '{}'),
                       ?
                FROM product p
                WHERE p.product_id = ?
                ON CONFLICT (product_id) DO UPDATE
                SET seller_id = EXCLUDED.seller_id,
                    status = EXCLUDED.status,
                    primary_category_id = EXCLUDED.primary_category_id,
                    channels = EXCLUDED.channels,
                    categories = EXCLUDED.categories,
                    updated_at = EXCLUDED.updated_at
                """, Timestamp.from(now), productId);
    }

    /** 한 페이지의 채널·카테고리를 한 번에 읽습니다. */
    public List<ProductListingRow> findPage(List<UUID> productIds) {
        if (productIds == null || productIds.isEmpty()) return List.of();
        return jdbc.query("""
                SELECT product_id, channels, categories
                FROM product_listing_projection
                WHERE product_id = ANY (?)
                """, (rs, row) -> new ProductListingRow(
                        rs.getObject("product_id", UUID.class),
                        // TEXT[] 는 String[] 로, UUID[] 는 PGobject[] 로 돌아올 수 있다.
                        // 배열 원소를 문자열로 정규화한 뒤 필요한 타입으로 변환한다.
                        strings(rs.getArray("channels")),
                        uuids(rs.getArray("categories"))),
                new Object[] {productIds.toArray(UUID[]::new)});
    }

    private static List<String> strings(Array array) {
        if (array == null) return List.of();
        try {
            Object raw = array.getArray();
            if (raw instanceof String[] values) return List.of(values);
            if (raw instanceof Object[] values) {
                List<String> result = new ArrayList<>(values.length);
                for (Object value : values) {
                    if (value != null) result.add(value.toString());
                }
                return List.copyOf(result);
            }
            return List.of();
        } catch (SQLException exception) {
            throw new IllegalStateException("목록 투영의 채널 배열을 읽지 못했습니다", exception);
        }
    }

    private static List<UUID> uuids(Array array) {
        if (array == null) return List.of();
        try {
            Object raw = array.getArray();
            List<UUID> result = new ArrayList<>();
            if (raw instanceof Object[] values) {
                for (Object value : values) {
                    if (value != null) result.add(UUID.fromString(value.toString()));
                }
            }
            return List.copyOf(result);
        } catch (SQLException exception) {
            throw new IllegalStateException("목록 투영의 카테고리 배열을 읽지 못했습니다", exception);
        }
    }
}
