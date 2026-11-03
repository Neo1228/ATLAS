package com.atlas.inventory.persistence.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Column;

@Entity
@Table(name = "inventory_pool")
public class InventoryPool {

    @EmbeddedId
    private InventoryPoolId id;

    @Column(name = "initial_capacity", nullable = false)
    private int initialCapacity;

    @Column(nullable = false)
    private int available;

    @Column(nullable = false)
    private int reserved;

    @Column(nullable = false)
    private int claimed;

    protected InventoryPool() {
    }

    public InventoryPool(InventoryPoolId id, int capacity) {
        this.id = id;
        this.initialCapacity = capacity;
        this.available = capacity;
    }

    public InventoryPoolId getId() { return id; }
    public int getInitialCapacity() { return initialCapacity; }
    public int getAvailable() { return available; }
    public int getReserved() { return reserved; }
    public int getClaimed() { return claimed; }
}
