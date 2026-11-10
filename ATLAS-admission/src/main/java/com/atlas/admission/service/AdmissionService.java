package com.atlas.admission.service;

import com.atlas.admission.persistence.state.CheckoutSlotReleaseReason;
import com.atlas.admission.dto.response.QueueTicketResponse;
import com.atlas.admission.dto.request.JoinQueueRequest;

import com.atlas.admission.dto.request.ConfigureSellerSkuPolicyRequest;
import java.util.UUID;

public interface AdmissionService {

    void configureSellerSkuPolicy(ConfigureSellerSkuPolicyRequest request);
    QueueTicketResponse join(JoinQueueRequest request);

    /** 마지막으로 저장된 티켓 상태를 반환한다. 만료와 승격은 스케줄러 또는 상태 변경 작업에서 처리한다. */
    QueueTicketResponse find(UUID campaignId, UUID ticketId);

    QueueTicketResponse requireCheckoutSlot(UUID campaignId, UUID ticketId);
    QueueTicketResponse startCheckout(UUID campaignId, UUID ticketId);
    QueueTicketResponse releaseCheckoutSlot(UUID campaignId, UUID ticketId, CheckoutSlotReleaseReason reason);

}
