package com.atlas.application.repository;

import com.atlas.application.persistence.projection.ProductReview;
import com.atlas.application.persistence.projection.ReviewSummary;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ProductReviewRepository {
    private static final RowMapper<ProductReview> REVIEW_MAPPER = (rs, rowNum) ->
            new ProductReview(
                    rs.getObject("review_id", UUID.class), rs.getInt("rating"), rs.getString("body"),
                    rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public ProductReviewRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public ProductReview create(String buyerRef, UUID productId, UUID orderItemId, int rating, String body) {
        return jdbc.queryForObject("""
                INSERT INTO product_review (buyer_ref, product_id, order_item_id, rating, body)
                VALUES (?, ?, ?, ?, ?)
                RETURNING review_id, rating, body, created_at
                """, REVIEW_MAPPER, buyerRef, productId, orderItemId, rating, body);
    }

    public ReviewSummary summary(UUID productId) {
        return jdbc.queryForObject("""
                SELECT count(*) AS review_count, COALESCE(AVG(rating), 0)::numeric AS average_rating
                FROM product_review
                WHERE product_id = ?
                """, (rs, rowNum) -> new ReviewSummary(
                rs.getLong("review_count"), rs.getBigDecimal("average_rating")), productId);
    }

    public List<ProductReview> list(UUID productId, int limit, int offset) {
        return jdbc.query("""
                SELECT review_id, rating, body, created_at
                FROM product_review
                WHERE product_id = ?
                ORDER BY created_at DESC, review_id DESC
                LIMIT ? OFFSET ?
                """, REVIEW_MAPPER, productId, limit, offset);
    }
}
