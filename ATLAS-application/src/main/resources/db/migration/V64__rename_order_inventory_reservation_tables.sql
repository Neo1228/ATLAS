ALTER TABLE distributed_inventory_workflow
    RENAME TO order_inventory_reservation_workflow;

ALTER TABLE distributed_inventory_location_command
    RENAME TO order_inventory_location_command;

ALTER TABLE distributed_inventory_location_item
    RENAME TO order_inventory_location_item;

ALTER TABLE order_inventory_reservation_workflow
    RENAME CONSTRAINT distributed_inventory_workflow_pkey
    TO order_inventory_reservation_workflow_pkey;
ALTER TABLE order_inventory_reservation_workflow
    RENAME CONSTRAINT distributed_inventory_workflow_idempotency_key_key
    TO order_inventory_reservation_workflow_idempotency_key_key;
ALTER TABLE order_inventory_reservation_workflow
    RENAME CONSTRAINT distributed_inventory_workflow_attempt_nonnegative
    TO order_inventory_reservation_workflow_attempt_nonnegative;
ALTER TABLE order_inventory_reservation_workflow
    RENAME CONSTRAINT distributed_inventory_workflow_state
    TO order_inventory_reservation_workflow_state;

ALTER TABLE order_inventory_location_command
    RENAME CONSTRAINT distributed_inventory_location_command_pkey
    TO order_inventory_location_command_pkey;
ALTER TABLE order_inventory_location_command
    RENAME CONSTRAINT distributed_inventory_location_command_idempotency_key_key
    TO order_inventory_location_command_idempotency_key_key;
ALTER TABLE order_inventory_location_command
    RENAME CONSTRAINT distributed_inventory_location_command_workflow_id_fkey
    TO order_inventory_location_command_workflow_id_fkey;
ALTER TABLE order_inventory_location_command
    RENAME CONSTRAINT distributed_inventory_location_state
    TO order_inventory_location_reservation_state;

ALTER TABLE order_inventory_location_item
    RENAME CONSTRAINT distributed_inventory_location_item_pkey
    TO order_inventory_location_item_pkey;
ALTER TABLE order_inventory_location_item
    RENAME CONSTRAINT distributed_inventory_location_item_quantity
    TO order_inventory_location_item_quantity;
ALTER TABLE order_inventory_location_item
    RENAME CONSTRAINT distributed_inventory_location_item_command_fk
    TO order_inventory_location_item_command_fk;

ALTER INDEX distributed_inventory_workflow_due_idx
    RENAME TO order_inventory_reservation_workflow_due_idx;
