package com.atlas.admission.service;

import com.atlas.admission.dto.response.AdmissionResponse;
import com.atlas.admission.dto.request.AdmissionRequest;
import com.atlas.admission.model.CheckoutSlotReleaseReason;

import java.util.UUID;

public interface AdmissionService {
    int expireDueTickets();

    void configureSellerSkuPolicy(AdmissionRequest.ConfigureSellerSkuPolicy request);
    AdmissionResponse.QueueTicket join(AdmissionRequest.JoinQueue request);

    /** 마지막으로 저장된 티켓 상태를 반환한다. 만료와 승격은 스케줄러 또는 상태 변경 작업에서 처리한다. */
    AdmissionResponse.QueueTicket find(UUID campaignId, UUID ticketId);

    AdmissionResponse.QueueTicket requireCheckoutSlot(UUID campaignId, UUID ticketId);
    AdmissionResponse.QueueTicket startCheckout(UUID campaignId, UUID ticketId);
    AdmissionResponse.QueueTicket releaseCheckoutSlot(UUID campaignId, UUID ticketId, CheckoutSlotReleaseReason reason);

}
