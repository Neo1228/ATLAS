package com.atlas.order.service;

import com.atlas.order.dto.response.ReservationWorkflowResponse;
import com.atlas.order.dto.request.LocationReservationRequest;
import java.util.List;
import java.util.UUID;

public interface LocationReservationService {
    /**
     * 원격 호출 없이 workflow intent만 저장한다. 호출자는 자신의 transaction 안에서 호출해
     * intent를 소유 주문과 함께 원자적으로 commit할 수 있다.
     */
    ReservationWorkflowResponse.LocationReservation stage(String idempotencyKey,
            LocationReservationRequest request);

    ReservationWorkflowResponse.LocationReservation submit(String idempotencyKey,
            LocationReservationRequest request);

    ReservationWorkflowResponse.LocationReservation find(UUID workflowId);

    ReservationWorkflowResponse.LocationReservation retry(UUID workflowId);

    /** 운영자가 봐야 하는 workflow를 오래된 순으로 돌려준다. */
    List<ReservationWorkflowResponse.LocationReservation> needsReview(int limit);

    /**
     * NEEDS_REVIEW를 보상 재실행으로 되돌려 원격 예약을 해제한다(CLAIMED는 restore). 원격 재고를 풀기 때문에 주문을
     * 계속 진행하려는 경우에는 쓰지 않는다. 다른 상태에서 호출하면 현재 상태를 그대로 돌려준다.
     */
    ReservationWorkflowResponse.LocationReservation releaseNeedsReview(UUID workflowId);

    ReservationWorkflowResponse.LocationReservation cancel(UUID workflowId);

    /**
     * 결제 승인 뒤 매입 전에 모든 location의 원격 예약을 claim한다. 원격 호출은 DB transaction 밖에서
     * 수행하고, workflow lease로 동시 claim·취소를 막는다. 반환 상태가 {@code CLAIMED}일 때만 매입해도 된다.
     * 원격 결과가 불확실하면 {@code RESERVED}로 남겨 같은 reservation ID로 다시 claim한다(원격 claim은 멱등).
     * 만료·취소로 claim이 확정 거절되면 {@code lastErrorCode}에 원인을 남기고 workflow는 {@code RESERVED}로 둔다.
     * 호출자는 승인을 void한 뒤 {@link #cancel(UUID)}로 남은 예약을 정리한다.
     */
    ClaimOutcome claim(UUID workflowId);

    enum ClaimOutcome { CLAIMED, PENDING, REJECTED }

    long needsReviewCount(boolean claimedResidual);

    double oldestDueAgeSeconds();

    /**
     * poll 시작 시각까지 due인 workflow를 한 건씩 lease하고 즉시 처리한다.
     * poll 도중 새로 예약된 retry는 다음 poll에 맡겨 같은 poll의 재시도 loop를 막는다.
     */
    int recoverDue(int batchSize);
}
