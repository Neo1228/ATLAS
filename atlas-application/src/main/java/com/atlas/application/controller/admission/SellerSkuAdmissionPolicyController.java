package com.atlas.application.controller.admission;

import com.atlas.application.dto.request.AdmissionHttpRequest;
import com.atlas.admission.dto.request.AdmissionRequest;
import com.atlas.admission.service.AdmissionService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/sellers/{sellerId}/skus/{skuId}/admission-policy")
public class SellerSkuAdmissionPolicyController {

    private final AdmissionService admission;

    @PutMapping
    public void configure(@PathVariable UUID sellerId, @PathVariable String skuId,
            @Valid @RequestBody AdmissionHttpRequest.ConfigureSellerSkuPolicy request) {
        admission.configureSellerSkuPolicy(
                new AdmissionRequest.ConfigureSellerSkuPolicy(sellerId, skuId, request.maxActiveCheckouts()));
    }
}
