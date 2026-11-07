package com.atlas.inventory.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name="inventory_pool")
@Getter
@NoArgsConstructor
public class InventoryPool {
    @EmbeddedId private InventoryPoolId id;
    @Column(name="initial_capacity",nullable=false) private int initialCapacity;
    @Column(nullable=false) private int available;
    @Column(nullable=false) private int reserved;
    @Column(nullable=false) private int claimed;
    public InventoryPool(InventoryPoolId id,int initialCapacity) { this.id=id; this.initialCapacity=initialCapacity; this.available=initialCapacity; this.reserved=0; this.claimed=0; }
}
