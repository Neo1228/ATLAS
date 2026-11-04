package com.atlas.catalog.repository;

import com.atlas.catalog.persistence.entity.Seller;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SellerRepository extends JpaRepository<Seller, UUID> {
}
