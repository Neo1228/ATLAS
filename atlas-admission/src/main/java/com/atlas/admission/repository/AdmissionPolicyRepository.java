package com.atlas.admission.repository;

import com.atlas.admission.persistence.entity.AdmissionPolicy;
import com.atlas.admission.persistence.entity.AdmissionPolicy.AdmissionPolicyId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdmissionPolicyRepository extends JpaRepository<AdmissionPolicy, AdmissionPolicyId> {
    Optional<AdmissionPolicy> findBySellerIdAndSkuId(UUID sellerId, String skuId);
}
