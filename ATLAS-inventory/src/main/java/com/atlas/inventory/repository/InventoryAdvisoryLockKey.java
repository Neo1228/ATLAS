package com.atlas.inventory.repository;

import java.util.Objects;

final class InventoryAdvisoryLockKey {
    private InventoryAdvisoryLockKey() { }

    static String encode(String... parts) {
        StringBuilder key = new StringBuilder();
        for (String part : parts) {
            String value = Objects.requireNonNull(part);
            key.append(value.length()).append(':').append(value);
        }
        return key.toString();
    }
}
