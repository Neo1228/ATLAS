-- Buyer 취소와 형제 workflow 보상은 실패 보상(COMPENSATING)과 구분한다.
-- CANCEL_PENDING은 복구 대상 작업이고, CANCELLED는 모든 location 해제 후의 종료 상태다.
ALTER TABLE order_inventory_reservation_workflow
    DROP CONSTRAINT order_inventory_reservation_workflow_state;
ALTER TABLE order_inventory_reservation_workflow
    ADD CONSTRAINT order_inventory_reservation_workflow_state CHECK (
        workflow_state IN (
            'PENDING', 'RETRY_PENDING', 'RESERVED', 'COMPENSATING', 'COMPENSATION_REQUIRED',
            'CANCEL_PENDING', 'FAILED', 'CANCELLED', 'NEEDS_REVIEW'
        )
    );

DROP INDEX order_inventory_reservation_workflow_due_idx;
CREATE INDEX order_inventory_reservation_workflow_due_idx
    ON order_inventory_reservation_workflow (next_attempt_at, workflow_id)
    WHERE workflow_state IN (
        'PENDING', 'RETRY_PENDING', 'COMPENSATING', 'COMPENSATION_REQUIRED', 'CANCEL_PENDING'
    );
