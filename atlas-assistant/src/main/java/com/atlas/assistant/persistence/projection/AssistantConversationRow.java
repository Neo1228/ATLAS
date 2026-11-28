package com.atlas.assistant.persistence.projection;

import java.util.UUID;

public record AssistantConversationRow(UUID conversationId, String buyerRef, long tokensUsed) {
}
