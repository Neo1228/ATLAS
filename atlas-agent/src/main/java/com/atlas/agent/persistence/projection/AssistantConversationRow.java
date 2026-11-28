package com.atlas.agent.persistence.projection;

import java.util.UUID;

public record AssistantConversationRow(UUID conversationId, String buyerRef, long tokensUsed) {
}
