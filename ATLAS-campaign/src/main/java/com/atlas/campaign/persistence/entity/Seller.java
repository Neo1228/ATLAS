package com.atlas.campaign.persistence.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "seller")
public class Seller {

    @Id
    private UUID sellerId;

    protected Seller() {
    }

    public Seller(UUID sellerId) {
        this.sellerId = sellerId;
    }

    public UUID getSellerId() {
        return sellerId;
    }
}
