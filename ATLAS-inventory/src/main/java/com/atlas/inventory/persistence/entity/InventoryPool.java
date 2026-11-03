package com.atlas.inventory.persistence.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import lombok.Getter;

@Entity
@Table(name = "inventory_pool")
public class InventoryPool {

    @EmbeddedId
    @Getter
    private InventoryPoolId id;

    @Column(name = "initial_capacity", nullable = false)
    @Getter
    private int initialCapacity;

    @Column(nullable = false)
    @Getter
    private int available;

    @Column(nullable = false)
    @Getter
    private int reserved;

    @Column(nullable = false)
    @Getter
    private int claimed;

    protected InventoryPool() {
    }

    public InventoryPool(InventoryPoolId id, int capacity) {
        this.id = id;
        this.initialCapacity = capacity;
        this.available = capacity;
    }

}
