-- 결제 승인 뒤 매입 전에 원격 재고를 claim한다. location과 workflow에 CLAIMED를 기록해야
-- 매입 성공 시 PAID로 확정하고, 매입 실패·취소 시 claim을 되돌릴 대상을 식별할 수 있다.
ALTER TABLE order_inventory_location_command
    DROP CONSTRAINT order_inventory_location_reservation_state;
ALTER TABLE order_inventory_location_command
    ADD CONSTRAINT order_inventory_location_reservation_state CHECK (
        command_state IN (
            'PENDING', 'RETRY_PENDING', 'RESERVED', 'CLAIMED', 'FAILED', 'RELEASE_PENDING', 'RELEASED'
        )
    );

ALTER TABLE order_inventory_reservation_workflow
    DROP CONSTRAINT order_inventory_reservation_workflow_state;
ALTER TABLE order_inventory_reservation_workflow
    ADD CONSTRAINT order_inventory_reservation_workflow_state CHECK (
        workflow_state IN (
            'PENDING', 'RETRY_PENDING', 'RESERVED', 'CLAIMED', 'COMPENSATING', 'COMPENSATION_REQUIRED',
            'CANCEL_PENDING', 'FAILED', 'CANCELLED', 'NEEDS_REVIEW'
        )
    );
