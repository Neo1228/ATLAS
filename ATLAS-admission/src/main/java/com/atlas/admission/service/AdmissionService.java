package com.atlas.admission.service;

import com.atlas.admission.persistence.state.CheckoutSlotReleaseReason;
import com.atlas.admission.dto.response.QueueTicketResponse;
import com.atlas.admission.dto.request.JoinQueueRequest;

import com.atlas.admission.dto.request.ConfigureSellerSkuPolicyRequest;
import java.util.UUID;

public interface AdmissionService {

    void configureSellerSkuPolicy(ConfigureSellerSkuPolicyRequest request);
    QueueTicketResponse join(JoinQueueRequest request);

    /** 마지막으로 저장된 Ticket 상태를 반환. 만료·승격은 스케줄러나 상태 변경 작업이 반영. */
    QueueTicketResponse find(UUID campaignId, UUID ticketId);

    QueueTicketResponse requireCheckoutSlot(UUID campaignId, UUID ticketId);
    QueueTicketResponse startCheckout(UUID campaignId, UUID ticketId);
    QueueTicketResponse releaseCheckoutSlot(UUID campaignId, UUID ticketId, CheckoutSlotReleaseReason reason);

}
