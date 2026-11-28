package com.atlas.agent.repository;

import com.atlas.agent.persistence.projection.AssistantConversationRow;
import com.atlas.agent.persistence.projection.AssistantMessageRow;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AssistantConversationRepository {
    private final JdbcTemplate jdbc;

    public AssistantConversationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AssistantConversationRow> find(UUID conversationId) {
        return jdbc.query("""
                SELECT conversation_id, buyer_ref, tokens_used FROM assistant_conversation WHERE conversation_id = ?
                """, (rs, row) -> new AssistantConversationRow(rs.getObject("conversation_id", UUID.class),
                rs.getString("buyer_ref"), rs.getLong("tokens_used")), conversationId).stream().findFirst();
    }

    /** 최근 {@code limit}개를 시간 순으로 돌려준다. */
    public List<AssistantMessageRow> findRecentMessages(UUID conversationId, int limit) {
        return jdbc.query("""
                SELECT role, content, tool_calls, tool_call_id FROM (
                    SELECT seq, role, content, tool_calls, tool_call_id FROM assistant_message
                    WHERE conversation_id = ? ORDER BY seq DESC LIMIT ?
                ) recent ORDER BY seq
                """, (rs, row) -> new AssistantMessageRow(rs.getString("role"), rs.getString("content"),
                rs.getString("tool_calls"), rs.getString("tool_call_id")), conversationId, limit);
    }

    public void create(UUID conversationId, String buyerRef, Instant now) {
        jdbc.update("""
                INSERT INTO assistant_conversation (conversation_id, buyer_ref, tokens_used, created_at, updated_at)
                VALUES (?, ?, 0, ?, ?)
                """, conversationId, buyerRef, Timestamp.from(now), Timestamp.from(now));
    }

    /** 대화 행을 잠그고 토큰을 더한다. 같은 대화의 동시 차례가 메시지 순번을 겹쳐 쓰지 않게 하는 잠금이기도 하다. */
    public long addTokens(UUID conversationId, long tokens, Instant now) {
        Long total = jdbc.queryForObject("""
                UPDATE assistant_conversation SET tokens_used = tokens_used + ?, updated_at = ?
                WHERE conversation_id = ? RETURNING tokens_used
                """, Long.class, tokens, Timestamp.from(now), conversationId);
        return total == null ? 0 : total;
    }

    public void append(UUID conversationId, List<AssistantMessageRow> messages, Instant now) {
        Integer last = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM assistant_message WHERE conversation_id = ?",
                Integer.class, conversationId);
        int seq = last == null ? 0 : last;
        for (AssistantMessageRow message : messages) {
            seq++;
            jdbc.update("""
                    INSERT INTO assistant_message (conversation_id, seq, role, content, tool_calls, tool_call_id, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, conversationId, seq, message.role(), message.content(), message.toolCallsJson(),
                    message.toolCallId(), Timestamp.from(now));
        }
    }
}
