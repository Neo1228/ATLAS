package com.atlas.order.service;

import java.util.Map;
import java.util.UUID;

public interface LocationRestockService {
    /** 환불 확정 transaction 안에서 호출한다. 판매 확정(CLAIMED) workflow의 location별 수량으로 나눠 기록한다. *
     * 호출자의 트랜잭션 안에서 실행되어야 한다. 이 메서드는 자체 트랜잭션을 열지 않아 중간에 예외가 나면 앞서 넣은
     * 재입고 행은 호출자 트랜잭션의 롤백으로만 지워진다.
     */
    void stage(UUID refundId, UUID orderId, UUID workflowId, UUID sellerId, Map<String, Integer> quantitiesBySku);

    /** 환불 확정 transaction 안에서 shipment가 확정한 location별 재입고량만 저장한다. *
     * 호출자의 트랜잭션 안에서 실행되어야 한다. 이 메서드는 자체 트랜잭션을 열지 않아 중간에 예외가 나면 앞서 넣은
     * 재입고 행은 호출자 트랜잭션의 롤백으로만 지워진다.
     */
    void stageAtLocations(UUID refundId, UUID orderId, UUID workflowId, UUID sellerId,
            Map<UUID, Map<String, Integer>> quantitiesByLocation);

    /** commit 직후 해당 환불의 재입고를 바로 시도한다. 실패는 recovery가 이어받는다. */
    void dispatch(UUID refundId);

    double oldestDueAgeSeconds();

    int recoverDue(int batchSize);
}
