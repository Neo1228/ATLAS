package com.atlas.inventory.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class InventoryAdvisoryLockKeyTest {
    @Test
    void lengthPrefixesKeepDelimitedFieldsUnambiguous() {
        String seller = "00000000-0000-0000-0000-000000000001";

        String first = InventoryAdvisoryLockKey.encode(seller, "a:b", "c");
        String second = InventoryAdvisoryLockKey.encode(seller, "a", "b:c");

        assertNotEquals(first, second);
        assertEquals(first, InventoryAdvisoryLockKey.encode(seller, "a:b", "c"));
    }
}
