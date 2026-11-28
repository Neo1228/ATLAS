package com.atlas.assistant.service.impl;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 모델이 만든 도구 인자를 서버에서 다시 검증한다. 모델에 준 JSON Schema를 믿지 않고 같은 제약을 여기서 강제한다:
 * 객체가 아니거나, 모르는 키가 있거나(예: buyer_ref, price_won), 타입·범위·형식이 어긋나면 거절한다.
 */
final class ToolArguments {
    private static final Pattern SKU = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private final JsonNode node;

    private ToolArguments(JsonNode node) {
        this.node = node;
    }

    static ToolArguments parse(ObjectMapper mapper, String json, Set<String> allowedKeys) {
        JsonNode node;
        try {
            node = mapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (JacksonException exception) {
            throw new ToolRefusal("ARGUMENTS_NOT_JSON");
        }
        if (node == null || !node.isObject()) throw new ToolRefusal("ARGUMENTS_NOT_OBJECT");
        for (String key : node.propertyNames()) {
            if (!allowedKeys.contains(key)) throw new ToolRefusal("UNKNOWN_ARGUMENT:" + key);
        }
        return new ToolArguments(node);
    }

    String text(String key, int maxLength) {
        JsonNode value = node.get(key);
        if (value == null || !value.isString()) throw new ToolRefusal("INVALID_ARGUMENT:" + key);
        String text = value.asString().strip();
        if (text.isEmpty() || text.length() > maxLength) throw new ToolRefusal("INVALID_ARGUMENT:" + key);
        return text;
    }

    UUID uuid(String key) {
        try {
            return UUID.fromString(text(key, 36));
        } catch (IllegalArgumentException exception) {
            throw new ToolRefusal("INVALID_ARGUMENT:" + key);
        }
    }

    String sku(String key) {
        String sku = text(key, 128);
        if (!SKU.matcher(sku).matches()) throw new ToolRefusal("INVALID_ARGUMENT:" + key);
        return sku;
    }

    /** 정수만 받는다. 1.5, "3", 범위 밖 값은 거절한다. */
    long integer(String key, long min, long max) {
        JsonNode value = node.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new ToolRefusal("INVALID_ARGUMENT:" + key);
        }
        long number = value.asLong();
        if (number < min || number > max) throw new ToolRefusal("INVALID_ARGUMENT:" + key);
        return number;
    }

    Long optionalInteger(String key, long min, long max) {
        JsonNode value = node.get(key);
        return value == null || value.isNull() ? null : integer(key, min, max);
    }

    /**
     * 정책과 무관한 선택 힌트(결과 수 등)만 위한 해석. 정수가 아니거나 범위 밖이면 거절하지 않고 null(기본값)을 돌려준다.
     * 금액·수량·식별자에는 쓰지 않는다.
     */
    Long optionalHint(String key, long min, long max) {
        JsonNode value = node.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) return null;
        long number = value.asLong();
        return number < min || number > max ? null : number;
    }
}
