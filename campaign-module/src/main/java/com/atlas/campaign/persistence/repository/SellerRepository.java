package com.atlas.campaign.persistence.repository;

import com.atlas.campaign.persistence.entity.Seller;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SellerRepository extends JpaRepository<Seller, UUID> {
}
