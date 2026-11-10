package com.atlas.payment.service.impl;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** 소유자별 작업만 직렬화하고, 사용이 끝난 잠금 항목은 회수합니다. */
final class OwnerLockRegistry {
    private final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();

    <T> T withLock(UUID ownerId, Supplier<T> action) {
        Entry entry = entries.compute(ownerId, (key, current) -> {
            Entry selected = current == null ? new Entry() : current;
            selected.references++;
            return selected;
        });
        try {
            synchronized (entry) {
                return action.get();
            }
        } finally {
            entries.compute(ownerId, (key, current) -> {
                if (current != entry) {
                    throw new IllegalStateException("소유자 잠금 항목이 예상과 다릅니다");
                }
                current.references--;
                return current.references == 0 ? null : current;
            });
        }
    }

    int registeredKeyCount() {
        return entries.size();
    }

    private static final class Entry {
        private int references;
    }
}
