package com.atlas.application.persistence.projection;

public record RequeueRecord(String eventId, String actor, String reason, String outcome) { }
