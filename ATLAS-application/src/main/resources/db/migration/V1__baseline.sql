-- ATLAS 기준 스키마(baseline)
--
-- 아직 배포하지 않아 이전 상태에서 올려야 할 데이터가 없으므로, 증분 마이그레이션 V1~V75를 하나로 합쳐
-- 같은 최종 스키마를 만드는 기준 마이그레이션으로 대체했다. 이 파일은 V1~V75를 순서대로 적용한 빈
-- PostgreSQL 18.6에서 `pg_dump --schema-only`로 얻은 결과이며, 백필·이름 변경·임시 호환 문장은 포함하지 않는다.
-- 설명 주석이 들어 있던 이전 파일은 git 이력에서 볼 수 있다.
-- 객체 이름에 스키마를 붙이지 않아 Flyway가 정한 스키마(기본 public)에 만들어진다.
--
-- 이후 변경은 V2부터 증분 마이그레이션으로 추가한다.

--
-- PostgreSQL database dump
--

-- Dumped from database version 18.6 (Debian 18.6-1.pgdg13+2)
-- Dumped by pg_dump version 18.6 (Debian 18.6-1.pgdg13+2)

--
-- Name: pg_trgm; Type: EXTENSION; Schema: -; Owner: -
--

CREATE EXTENSION IF NOT EXISTS pg_trgm;

--
-- Name: EXTENSION pg_trgm; Type: COMMENT; Schema: -; Owner: -
--


--
-- Name: guard_paypal_webhook_retry_attempt_history(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION guard_paypal_webhook_retry_attempt_history() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'UPDATE'
       AND OLD.outcome = 'CLAIMED' AND NEW.outcome <> 'CLAIMED'
       AND NEW.lease_token = OLD.lease_token AND NEW.event_id = OLD.event_id
       AND NEW.attempt_number = OLD.attempt_number AND NEW.claimed_at = OLD.claimed_at
       AND NEW.finished_at IS NOT NULL THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'PayPal webhook retry attempt history is immutable after finalization';
END;
$$;

--
-- Name: order_inventory_record_workflow_event(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION order_inventory_record_workflow_event() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.workflow_state IS NOT DISTINCT FROM NEW.workflow_state THEN
        RETURN NULL;
    END IF;
    INSERT INTO order_inventory_event (workflow_id, order_id, aggregate_version, event_type, last_error_code)
    SELECT NEW.workflow_id, NEW.order_id, COALESCE(MAX(e.aggregate_version), 0) + 1,
           'RESERVATION_' || NEW.workflow_state, NEW.last_error_code
    FROM order_inventory_event e
    WHERE e.workflow_id = NEW.workflow_id;
    RETURN NULL;
END;
$$;

--
-- Name: protect_default_inventory_location(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION protect_default_inventory_location() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF OLD.location_code = 'DEFAULT' THEN
        RAISE EXCEPTION 'DEFAULT inventory location cannot be deleted';
    END IF;
    RETURN OLD;
END;
$$;

--
-- Name: reject_inventory_transfer_mutation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION reject_inventory_transfer_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'inventory transfer audit is append-only';
END;
$$;

--
-- Name: reject_paypal_balance_report_mutation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION reject_paypal_balance_report_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'PayPal balance report history is immutable';
END;
$$;

--
-- Name: reject_paypal_webhook_retry_audit_mutation(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION reject_paypal_webhook_retry_audit_mutation() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'operator retry audit rows are immutable';
END;
$$;

--
-- Name: account_action_token; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE account_action_token (
    token_id uuid DEFAULT uuidv7() NOT NULL,
    account_id uuid NOT NULL,
    purpose character varying(32) NOT NULL,
    token_hash character(64) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    consumed_at timestamp with time zone,
    CONSTRAINT ck_account_action_token_expiry CHECK ((expires_at > created_at)),
    CONSTRAINT ck_account_action_token_hash CHECK ((token_hash ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT ck_account_action_token_purpose CHECK (((purpose)::text = ANY ((ARRAY['EMAIL_VERIFICATION'::character varying, 'PASSWORD_RESET'::character varying])::text[])))
);

--
-- Name: account_mfa_challenge; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE account_mfa_challenge (
    challenge_hash character(64) NOT NULL,
    account_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    consumed_at timestamp with time zone,
    CONSTRAINT ck_account_mfa_challenge_expiry CHECK ((expires_at > created_at)),
    CONSTRAINT ck_account_mfa_challenge_hash CHECK ((challenge_hash ~ '^[0-9a-f]{64}$'::text))
);

--
-- Name: account_mfa_recovery_code; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE account_mfa_recovery_code (
    account_id uuid NOT NULL,
    code_hash character(64) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    consumed_at timestamp with time zone,
    CONSTRAINT ck_account_mfa_recovery_code_hash CHECK ((code_hash ~ '^[0-9a-f]{64}$'::text))
);

--
-- Name: account_mfa_secret; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE account_mfa_secret (
    account_id uuid NOT NULL,
    encrypted_secret text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    enabled_at timestamp with time zone,
    last_accepted_step bigint,
    CONSTRAINT ck_account_mfa_secret_nonempty CHECK ((length(btrim(encrypted_secret)) > 0))
);

--
-- Name: account_refresh_token; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE account_refresh_token (
    token_id uuid DEFAULT uuidv7() NOT NULL,
    family_id uuid NOT NULL,
    token_hash character(64) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    consumed_at timestamp with time zone,
    revoked_at timestamp with time zone,
    replaced_by_hash character(64),
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_account_refresh_token_expiry CHECK ((expires_at > created_at)),
    CONSTRAINT ck_account_refresh_token_hash_hex CHECK ((token_hash ~ '^[0-9a-f]{64}$'::text))
);

--
-- Name: account_suspension_action; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE account_suspension_action (
    suspension_action_id uuid NOT NULL,
    account_id uuid NOT NULL,
    action character varying(16) NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    actor character varying(128) NOT NULL,
    reason character varying(500) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT account_suspension_action_action_check CHECK (((action)::text = ANY ((ARRAY['SUSPEND'::character varying, 'RELEASE'::character varying])::text[]))),
    CONSTRAINT account_suspension_action_actor_check CHECK (((length(TRIM(BOTH FROM actor)) >= 1) AND (length(TRIM(BOTH FROM actor)) <= 128))),
    CONSTRAINT account_suspension_action_reason_check CHECK (((length(TRIM(BOTH FROM reason)) >= 1) AND (length(TRIM(BOTH FROM reason)) <= 500)))
);

--
-- Name: admission_counter; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE admission_counter (
    seller_id uuid NOT NULL,
    sku_id character varying(128) NOT NULL,
    last_sequence bigint DEFAULT 0 NOT NULL,
    CONSTRAINT admission_counter_sequence_nonnegative CHECK ((last_sequence >= 0))
);

--
-- Name: admission_ticket; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE admission_ticket (
    ticket_id uuid NOT NULL,
    campaign_id uuid NOT NULL,
    seller_id uuid NOT NULL,
    sku_id character varying(128) NOT NULL,
    buyer_ref character varying(256) NOT NULL,
    idempotency_key character varying(256) NOT NULL,
    sequence bigint NOT NULL,
    status character varying(32) NOT NULL,
    expires_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    checkout_slot_acquired_at timestamp with time zone,
    checkout_slot_held boolean NOT NULL,
    CONSTRAINT admission_ticket_buyer_nonblank CHECK ((length(TRIM(BOTH FROM buyer_ref)) > 0)),
    CONSTRAINT admission_ticket_key_nonblank CHECK ((length(TRIM(BOTH FROM idempotency_key)) > 0)),
    CONSTRAINT admission_ticket_sequence_positive CHECK ((sequence > 0)),
    CONSTRAINT admission_ticket_sku_nonblank CHECK ((length(TRIM(BOTH FROM sku_id)) > 0)),
    CONSTRAINT admission_ticket_slot_status_consistent CHECK (((checkout_slot_held AND ((status)::text = ANY ((ARRAY['CHECKOUT_ACTIVE'::character varying, 'CHECKOUT_STARTED'::character varying])::text[]))) OR ((NOT checkout_slot_held) AND ((status)::text <> ALL ((ARRAY['CHECKOUT_ACTIVE'::character varying, 'CHECKOUT_STARTED'::character varying])::text[]))))),
    CONSTRAINT admission_ticket_status_valid CHECK (((status)::text = ANY ((ARRAY['WAITING'::character varying, 'CHECKOUT_ACTIVE'::character varying, 'CHECKOUT_STARTED'::character varying, 'CHECKOUT_SUBMITTED'::character varying, 'EXPIRED'::character varying, 'CHECKOUT_FAILED'::character varying])::text[])))
);

--
-- Name: auth_origin_bucket; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE auth_origin_bucket (
    origin_key character(64) NOT NULL,
    scope character varying(24) NOT NULL,
    window_started_at timestamp with time zone NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT auth_origin_bucket_attempts_nonnegative CHECK ((attempts >= 0)),
    CONSTRAINT auth_origin_bucket_scope_check CHECK (((scope)::text = ANY ((ARRAY['LOGIN'::character varying, 'EMAIL'::character varying])::text[])))
);

--
-- Name: auth_subject_bucket; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE auth_subject_bucket (
    subject_key character(64) NOT NULL,
    scope character varying(24) NOT NULL,
    window_started_at timestamp with time zone NOT NULL,
    failures integer DEFAULT 0 NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT auth_subject_bucket_failures_nonnegative CHECK ((failures >= 0)),
    CONSTRAINT auth_subject_bucket_scope_check CHECK (((scope)::text = ANY ((ARRAY['LOGIN'::character varying, 'MFA'::character varying])::text[])))
);

--
-- Name: buyer; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE buyer (
    buyer_id uuid NOT NULL
);

--
-- Name: buyer_account; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE buyer_account (
    account_id uuid NOT NULL,
    buyer_id uuid NOT NULL
);

--
-- Name: buyer_checkout; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE buyer_checkout (
    checkout_id uuid DEFAULT uuidv7() NOT NULL,
    buyer_ref character varying(256) NOT NULL,
    idempotency_key character varying(256) NOT NULL,
    state character varying(32) NOT NULL,
    total_won bigint NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    cancellation_target character varying(32),
    cancellation_requested_at timestamp with time zone,
    CONSTRAINT buyer_checkout_buyer_nonblank CHECK ((length(TRIM(BOTH FROM buyer_ref)) > 0)),
    CONSTRAINT buyer_checkout_cancellation_target_valid CHECK (((cancellation_target IS NULL) OR ((cancellation_target)::text = 'EXPIRED'::text))),
    CONSTRAINT buyer_checkout_cancellation_timestamp_required CHECK ((((state)::text <> 'CANCEL_PENDING'::text) OR (cancellation_requested_at IS NOT NULL))),
    CONSTRAINT buyer_checkout_key_nonblank CHECK ((length(TRIM(BOTH FROM idempotency_key)) > 0)),
    CONSTRAINT buyer_checkout_state_valid CHECK (((state)::text = ANY ((ARRAY['RESERVATION_PENDING'::character varying, 'RESERVED'::character varying, 'PAYMENT_PENDING'::character varying, 'PAYMENT_UNKNOWN'::character varying, 'INVENTORY_CLAIM_PENDING'::character varying, 'CANCEL_PENDING'::character varying, 'PAID'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying, 'EXPIRED'::character varying])::text[]))),
    CONSTRAINT buyer_checkout_total_nonnegative CHECK ((total_won >= 0))
);

--
-- Name: buyer_wishlist_item; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE buyer_wishlist_item (
    buyer_ref character varying(256) NOT NULL,
    product_id uuid NOT NULL,
    saved_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT buyer_wishlist_item_buyer_nonblank CHECK ((length(btrim((buyer_ref)::text)) > 0))
);

--
-- Name: campaign; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE campaign (
    campaign_id uuid NOT NULL,
    seller_id uuid NOT NULL,
    sku_id character varying(128) NOT NULL,
    status character varying(16) NOT NULL,
    queue_ticket_ttl_seconds bigint NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT campaign_sku_nonblank CHECK ((length(TRIM(BOTH FROM sku_id)) > 0)),
    CONSTRAINT campaign_status_valid CHECK (((status)::text = ANY ((ARRAY['CLOSED'::character varying, 'OPEN'::character varying])::text[]))),
    CONSTRAINT campaign_ttl_positive CHECK ((queue_ticket_ttl_seconds > 0))
);

--
-- Name: inventory_capacity_adjustment; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE inventory_capacity_adjustment (
    seller_id uuid NOT NULL,
    sku_id character varying(128) NOT NULL,
    idempotency_key text NOT NULL,
    delta integer NOT NULL,
    reason text NOT NULL,
    initial_capacity integer NOT NULL,
    available integer NOT NULL,
    reserved integer NOT NULL,
    claimed integer NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    location_id uuid NOT NULL,
    CONSTRAINT inventory_capacity_adjustment_conservation CHECK ((((available + reserved) + claimed) = initial_capacity)),
    CONSTRAINT inventory_capacity_adjustment_delta_check CHECK ((delta <> 0)),
    CONSTRAINT inventory_capacity_adjustment_reason_check CHECK ((length(TRIM(BOTH FROM reason)) > 0))
);

--
-- Name: inventory_location; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE inventory_location (
    location_id uuid DEFAULT uuidv7() NOT NULL,
    seller_id uuid NOT NULL,
    location_code character varying(64) NOT NULL,
    name character varying(128) NOT NULL,
    priority integer DEFAULT 0 NOT NULL,
    active boolean DEFAULT true NOT NULL,
    CONSTRAINT inventory_location_code_nonblank CHECK ((length(TRIM(BOTH FROM location_code)) > 0)),
    CONSTRAINT inventory_location_name_nonblank CHECK ((length(TRIM(BOTH FROM name)) > 0)),
    CONSTRAINT inventory_location_priority_nonnegative CHECK ((priority >= 0))
);

--
-- Name: inventory_pool; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE inventory_pool (
    seller_id uuid NOT NULL,
    sku_id character varying(128) NOT NULL,
    initial_capacity integer NOT NULL,
    available integer NOT NULL,
    reserved integer NOT NULL,
    claimed integer NOT NULL,
    location_id uuid NOT NULL,
    CONSTRAINT inventory_pool_available_nonnegative CHECK ((available >= 0)),
    CONSTRAINT inventory_pool_claimed_nonnegative CHECK ((claimed >= 0)),
    CONSTRAINT inventory_pool_conservation CHECK ((((available + reserved) + claimed) = initial_capacity)),
    CONSTRAINT inventory_pool_initial_capacity_nonnegative CHECK ((initial_capacity >= 0)),
    CONSTRAINT inventory_pool_reserved_nonnegative CHECK ((reserved >= 0))
);

--
-- Name: inventory_reservation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE inventory_reservation (
    order_id uuid NOT NULL,
    campaign_id uuid,
    seller_id uuid NOT NULL,
    sku_id character varying(128) NOT NULL,
    quantity integer NOT NULL,
    status character varying(16) NOT NULL,
    reservation_id uuid NOT NULL,
    restored_quantity integer DEFAULT 0 NOT NULL,
    location_id uuid NOT NULL,
    CONSTRAINT inventory_reservation_quantity_positive CHECK ((quantity > 0)),
    CONSTRAINT inventory_reservation_restored_quantity CHECK (((restored_quantity >= 0) AND (restored_quantity <= quantity))),
    CONSTRAINT inventory_reservation_status CHECK (((status)::text = ANY ((ARRAY['RESERVED'::character varying, 'CLAIMED'::character varying, 'RELEASED'::character varying])::text[])))
);

--
-- Name: inventory_transfer; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE inventory_transfer (
    transfer_id uuid DEFAULT uuidv7() NOT NULL,
    seller_id uuid NOT NULL,
    sku_id character varying(128) NOT NULL,
    source_location_id uuid NOT NULL,
    destination_location_id uuid NOT NULL,
    idempotency_key character varying(256) NOT NULL,
    quantity integer NOT NULL,
    reason text NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT inventory_transfer_distinct_locations CHECK ((source_location_id <> destination_location_id)),
    CONSTRAINT inventory_transfer_idempotency_key_nonblank CHECK ((length(TRIM(BOTH FROM idempotency_key)) > 0)),
    CONSTRAINT inventory_transfer_quantity_positive CHECK ((quantity > 0)),
    CONSTRAINT inventory_transfer_reason_nonblank CHECK ((length(TRIM(BOTH FROM reason)) > 0))
);

--
-- Name: order_fulfillment; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE order_fulfillment (
    order_id uuid NOT NULL,
    status character varying(24) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    in_progress_at timestamp with time zone,
    shipped_at timestamp with time zone,
    delivered_at timestamp with time zone,
    CONSTRAINT order_fulfillment_status CHECK (((status)::text = ANY ((ARRAY['UNFULFILLED'::character varying, 'IN_PROGRESS'::character varying, 'PARTIALLY_SHIPPED'::character varying, 'SHIPPED'::character varying, 'DELIVERED'::character varying])::text[]))),
    CONSTRAINT order_fulfillment_timestamps CHECK (((((status)::text = 'UNFULFILLED'::text) AND (in_progress_at IS NULL) AND (shipped_at IS NULL) AND (delivered_at IS NULL)) OR (((status)::text = 'IN_PROGRESS'::text) AND (in_progress_at IS NOT NULL) AND (shipped_at IS NULL) AND (delivered_at IS NULL)) OR (((status)::text = 'PARTIALLY_SHIPPED'::text) AND (in_progress_at IS NOT NULL) AND (shipped_at IS NULL) AND (delivered_at IS NULL)) OR (((status)::text = 'SHIPPED'::text) AND (in_progress_at IS NOT NULL) AND (shipped_at IS NOT NULL) AND (delivered_at IS NULL)) OR (((status)::text = 'DELIVERED'::text) AND (in_progress_at IS NOT NULL) AND (shipped_at IS NOT NULL) AND (delivered_at IS NOT NULL))))
);

--
-- Name: order_inventory_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE order_inventory_event (
    event_seq bigint NOT NULL,
    tx_id xid8 DEFAULT pg_current_xact_id() NOT NULL,
    workflow_id uuid NOT NULL,
    order_id uuid NOT NULL,
    aggregate_version integer NOT NULL,
    event_type character varying(64) NOT NULL,
    last_error_code character varying(64),
    occurred_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT order_inventory_event_version_positive CHECK ((aggregate_version >= 1))
);

--
-- Name: order_inventory_event_cursor; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE order_inventory_event_cursor (
    consumer_group character varying(64) NOT NULL,
    last_tx_id xid8 NOT NULL,
    last_event_seq bigint NOT NULL,
    lease_token uuid,
    lease_until timestamp with time zone,
    updated_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL
);

--
-- Name: order_inventory_event_event_seq_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE order_inventory_event ALTER COLUMN event_seq ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME order_inventory_event_event_seq_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

--
-- Name: order_inventory_location_command; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE order_inventory_location_command (
    workflow_id uuid CONSTRAINT distributed_inventory_location_command_workflow_id_not_null NOT NULL,
    fulfillment_location_id uuid CONSTRAINT distributed_inventory_location_fulfillment_location_id_not_null NOT NULL,
    idempotency_key character varying(160) CONSTRAINT distributed_inventory_location_command_idempotency_key_not_null NOT NULL,
    command_state character varying(32) CONSTRAINT distributed_inventory_location_command_command_state_not_null NOT NULL,
    remote_reservation_id uuid,
    last_error_code character varying(64),
    updated_at timestamp with time zone CONSTRAINT distributed_inventory_location_command_updated_at_not_null NOT NULL,
    remote_expires_at timestamp with time zone,
    CONSTRAINT order_inventory_location_reservation_state CHECK (((command_state)::text = ANY ((ARRAY['PENDING'::character varying, 'RETRY_PENDING'::character varying, 'RESERVED'::character varying, 'CLAIMED'::character varying, 'FAILED'::character varying, 'RELEASE_PENDING'::character varying, 'RELEASED'::character varying])::text[])))
);

--
-- Name: order_inventory_location_item; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE order_inventory_location_item (
    workflow_id uuid CONSTRAINT distributed_inventory_location_item_workflow_id_not_null NOT NULL,
    fulfillment_location_id uuid CONSTRAINT distributed_inventory_locatio_fulfillment_location_id_not_null1 NOT NULL,
    sku_id character varying(96) CONSTRAINT distributed_inventory_location_item_sku_id_not_null NOT NULL,
    quantity integer CONSTRAINT distributed_inventory_location_item_quantity_not_null NOT NULL,
    seller_id uuid NOT NULL,
    CONSTRAINT order_inventory_location_item_quantity CHECK ((quantity > 0))
);

--
-- Name: order_inventory_reservation_workflow; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE order_inventory_reservation_workflow (
    workflow_id uuid CONSTRAINT distributed_inventory_workflow_workflow_id_not_null NOT NULL,
    order_id uuid CONSTRAINT distributed_inventory_workflow_order_id_not_null NOT NULL,
    idempotency_key character varying(128) CONSTRAINT distributed_inventory_workflow_idempotency_key_not_null NOT NULL,
    workflow_state character varying(32) CONSTRAINT distributed_inventory_workflow_workflow_state_not_null NOT NULL,
    attempt_count integer DEFAULT 0 CONSTRAINT distributed_inventory_workflow_attempt_count_not_null NOT NULL,
    next_attempt_at timestamp with time zone CONSTRAINT distributed_inventory_workflow_next_attempt_at_not_null NOT NULL,
    lease_token uuid,
    lease_until timestamp with time zone,
    last_error_code character varying(64),
    created_at timestamp with time zone CONSTRAINT distributed_inventory_workflow_created_at_not_null NOT NULL,
    updated_at timestamp with time zone CONSTRAINT distributed_inventory_workflow_updated_at_not_null NOT NULL,
    CONSTRAINT order_inventory_reservation_workflow_attempt_nonnegative CHECK ((attempt_count >= 0)),
    CONSTRAINT order_inventory_reservation_workflow_state CHECK (((workflow_state)::text = ANY ((ARRAY['PENDING'::character varying, 'RETRY_PENDING'::character varying, 'RESERVED'::character varying, 'CLAIMED'::character varying, 'COMPENSATING'::character varying, 'COMPENSATION_REQUIRED'::character varying, 'CANCEL_PENDING'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying, 'NEEDS_REVIEW'::character varying])::text[])))
);

--
-- Name: order_inventory_restock; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE order_inventory_restock (
    restock_key character varying(160) NOT NULL,
    fulfillment_location_id uuid NOT NULL,
    refund_id uuid NOT NULL,
    order_id uuid NOT NULL,
    workflow_id uuid NOT NULL,
    remote_reservation_id uuid NOT NULL,
    restock_state character varying(32) NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    next_attempt_at timestamp with time zone NOT NULL,
    last_error_code character varying(64),
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT order_inventory_restock_attempt_nonnegative CHECK ((attempt_count >= 0)),
    CONSTRAINT order_inventory_restock_state CHECK (((restock_state)::text = ANY ((ARRAY['PENDING'::character varying, 'COMPLETED'::character varying, 'NEEDS_REVIEW'::character varying])::text[])))
);

--
-- Name: order_inventory_restock_item; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE order_inventory_restock_item (
    restock_key character varying(160) NOT NULL,
    fulfillment_location_id uuid NOT NULL,
    seller_id uuid NOT NULL,
    sku_id character varying(96) NOT NULL,
    quantity integer NOT NULL,
    CONSTRAINT order_inventory_restock_item_quantity CHECK ((quantity > 0))
);

--
-- Name: order_item; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE order_item (
    order_item_id uuid NOT NULL,
    order_id uuid NOT NULL,
    product_id uuid NOT NULL,
    seller_id uuid NOT NULL,
    sku_id character varying(128) NOT NULL,
    product_name text NOT NULL,
    quantity integer NOT NULL,
    unit_price_won bigint NOT NULL,
    coupon_discount_won bigint DEFAULT 0 NOT NULL,
    CONSTRAINT order_item_coupon_discount_check CHECK (((coupon_discount_won >= 0) AND (quantity > 0) AND (coupon_discount_won < (unit_price_won * quantity)) AND (((coupon_discount_won / quantity) +
CASE
    WHEN ((coupon_discount_won % (quantity)::bigint) = 0) THEN 0
    ELSE 1
END) < unit_price_won))),
    CONSTRAINT order_item_name_nonblank CHECK ((length(TRIM(BOTH FROM product_name)) > 0)),
    CONSTRAINT order_item_price_positive CHECK ((unit_price_won > 0)),
    CONSTRAINT order_item_quantity_positive CHECK ((quantity > 0))
);

--
-- Name: order_refund_request; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE order_refund_request (
    refund_id uuid DEFAULT uuidv7() NOT NULL,
    order_id uuid NOT NULL,
    seller_id uuid NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    amount_won bigint NOT NULL,
    refund_status character varying(32) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    payment_refund_operation_id uuid,
    payment_refund_status character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    payment_idempotency_key character varying(128),
    CONSTRAINT order_refund_request_amount_positive CHECK ((amount_won > 0)),
    CONSTRAINT order_refund_request_payment_status_check CHECK (((payment_refund_status)::text = ANY ((ARRAY['NOT_APPLICABLE'::character varying, 'PENDING'::character varying, 'UNKNOWN'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT order_refund_request_status CHECK (((refund_status)::text = ANY ((ARRAY['PARTIALLY_RECORDED'::character varying, 'FULLY_RECORDED'::character varying])::text[])))
);

--
-- Name: order_refund_request_item; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE order_refund_request_item (
    refund_id uuid NOT NULL,
    order_item_id uuid NOT NULL,
    quantity integer NOT NULL,
    CONSTRAINT order_refund_request_item_quantity_positive CHECK ((quantity > 0))
);

--
-- Name: order_refund_summary; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE order_refund_summary (
    order_id uuid NOT NULL,
    seller_id uuid NOT NULL,
    recorded_amount_won bigint NOT NULL,
    refund_status character varying(32) NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT order_refund_summary_amount_positive CHECK ((recorded_amount_won > 0)),
    CONSTRAINT order_refund_summary_status CHECK (((refund_status)::text = ANY ((ARRAY['PARTIALLY_RECORDED'::character varying, 'FULLY_RECORDED'::character varying])::text[])))
);

--
-- Name: order_reorder_request; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE order_reorder_request (
    seller_id uuid NOT NULL,
    buyer_ref character varying(256) NOT NULL,
    idempotency_key character varying(256) NOT NULL,
    source_order_id uuid NOT NULL,
    confirm_merge boolean NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT order_reorder_request_buyer_nonblank CHECK ((length(TRIM(BOTH FROM buyer_ref)) > 0)),
    CONSTRAINT order_reorder_request_key_nonblank CHECK ((length(TRIM(BOTH FROM idempotency_key)) > 0))
);

--
-- Name: order_return; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE order_return (
    return_id uuid DEFAULT uuidv7() NOT NULL,
    order_id uuid NOT NULL,
    seller_id uuid NOT NULL,
    buyer_ref character varying(256) NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    return_status character varying(32) NOT NULL,
    requested_at timestamp with time zone NOT NULL,
    received_at timestamp with time zone,
    inspected_at timestamp with time zone,
    CONSTRAINT order_return_status_check CHECK (((return_status)::text = ANY ((ARRAY['REQUESTED'::character varying, 'RECEIVED'::character varying, 'REFUND_RECORDED'::character varying, 'PARTIALLY_RECORDED'::character varying, 'INSPECTION_FAILED'::character varying])::text[]))),
    CONSTRAINT order_return_timestamps_check CHECK (((((return_status)::text = 'REQUESTED'::text) AND (received_at IS NULL) AND (inspected_at IS NULL)) OR (((return_status)::text = 'RECEIVED'::text) AND (received_at IS NOT NULL) AND (inspected_at IS NULL)) OR (((return_status)::text = ANY ((ARRAY['REFUND_RECORDED'::character varying, 'PARTIALLY_RECORDED'::character varying, 'INSPECTION_FAILED'::character varying])::text[])) AND (received_at IS NOT NULL) AND (inspected_at IS NOT NULL))))
);

--
-- Name: order_return_item; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE order_return_item (
    return_item_id uuid DEFAULT uuidv7() NOT NULL,
    return_id uuid NOT NULL,
    order_item_id uuid NOT NULL,
    quantity integer NOT NULL,
    inspection_result character varying(16),
    refund_id uuid,
    refund_amount_won bigint,
    inspected_at timestamp with time zone,
    shipment_id uuid,
    CONSTRAINT order_return_item_inspection CHECK (((inspection_result IS NULL) OR ((inspection_result)::text = ANY ((ARRAY['PASSED'::character varying, 'FAILED'::character varying])::text[])))),
    CONSTRAINT order_return_item_quantity_positive CHECK ((quantity > 0)),
    CONSTRAINT order_return_item_refund CHECK (((((refund_id IS NULL) AND (refund_amount_won IS NULL)) OR ((refund_id IS NOT NULL) AND (refund_amount_won > 0))) AND ((refund_id IS NULL) OR ((inspection_result)::text = 'PASSED'::text))))
);

--
-- Name: order_shipment; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE order_shipment (
    shipment_id uuid DEFAULT uuidv7() NOT NULL,
    order_id uuid NOT NULL,
    idempotency_key character varying(128),
    status character varying(16) NOT NULL,
    carrier_code character varying(64),
    tracking_number character varying(128),
    shipped_at timestamp with time zone NOT NULL,
    delivered_at timestamp with time zone,
    fulfillment_location_id uuid,
    CONSTRAINT order_shipment_carrier_tracking_pair CHECK ((((carrier_code IS NULL) AND (tracking_number IS NULL)) OR ((carrier_code IS NOT NULL) AND (TRIM(BOTH FROM carrier_code) <> ''::text) AND (tracking_number IS NOT NULL) AND (TRIM(BOTH FROM tracking_number) <> ''::text)))),
    CONSTRAINT order_shipment_idempotency_key_nonblank CHECK (((idempotency_key IS NULL) OR (TRIM(BOTH FROM idempotency_key) <> ''::text))),
    CONSTRAINT order_shipment_status_check CHECK (((status)::text = ANY ((ARRAY['SHIPPED'::character varying, 'DELIVERED'::character varying])::text[]))),
    CONSTRAINT order_shipment_timestamps_check CHECK (((((status)::text = 'SHIPPED'::text) AND (delivered_at IS NULL)) OR (((status)::text = 'DELIVERED'::text) AND (delivered_at IS NOT NULL) AND (delivered_at >= shipped_at))))
);

--
-- Name: order_shipment_item; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE order_shipment_item (
    shipment_id uuid NOT NULL,
    order_id uuid NOT NULL,
    order_item_id uuid NOT NULL,
    quantity integer NOT NULL,
    CONSTRAINT order_shipment_item_quantity_check CHECK ((quantity > 0))
);

--
-- Name: payment_attempt; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE payment_attempt (
    order_id uuid,
    attempt_id uuid NOT NULL,
    state character varying(32) NOT NULL,
    payment_phase character varying(20) NOT NULL,
    authorization_active boolean NOT NULL,
    checkout_id uuid,
    total_won bigint,
    provider_order_id character varying(64),
    provider_authorization_id character varying(64),
    provider_capture_id character varying(64),
    approval_url text,
    create_order_request_id character varying(38),
    authorization_request_id character varying(38),
    capture_request_id character varying(38),
    void_request_id character varying(38),
    provider_amount_minor bigint,
    provider_currency character varying(3),
    fx_rate_won_per_usd numeric(20,10),
    fx_rate_source character varying(64),
    fx_rate_as_of date,
    approval_expires_at timestamp with time zone,
    capture_prohibited boolean DEFAULT false NOT NULL,
    CONSTRAINT payment_attempt_fx_snapshot_check CHECK ((((provider_amount_minor IS NULL) AND (provider_currency IS NULL) AND (fx_rate_won_per_usd IS NULL) AND (fx_rate_source IS NULL) AND (fx_rate_as_of IS NULL)) OR ((provider_amount_minor > 0) AND (provider_currency IS NOT NULL) AND (fx_rate_won_per_usd > (0)::numeric) AND (fx_rate_source IS NOT NULL) AND (fx_rate_as_of IS NOT NULL)))),
    CONSTRAINT payment_attempt_owner_xor CHECK (((order_id IS NOT NULL) <> (checkout_id IS NOT NULL))),
    CONSTRAINT payment_attempt_payment_phase CHECK (((payment_phase)::text = ANY ((ARRAY['AUTHORIZATION'::character varying, 'CAPTURE'::character varying, 'VOID'::character varying])::text[]))),
    CONSTRAINT payment_attempt_state CHECK (((state)::text = ANY ((ARRAY['NOT_STARTED'::character varying, 'AUTHORIZATION_PENDING'::character varying, 'AUTHORIZED'::character varying, 'CAPTURE_PENDING'::character varying, 'CAPTURED'::character varying, 'DECLINED'::character varying, 'UNKNOWN'::character varying, 'VOID_PENDING'::character varying, 'VOIDED'::character varying])::text[]))),
    CONSTRAINT payment_attempt_total_won_nonnegative CHECK (((total_won IS NULL) OR (total_won >= 0)))
);

--
-- Name: payment_provider_webhook_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE payment_provider_webhook_event (
    event_id character varying(128) NOT NULL,
    event_type character varying(128) NOT NULL,
    resource_id character varying(128),
    received_at timestamp with time zone NOT NULL,
    processed_at timestamp with time zone,
    processing_status character varying(16) NOT NULL,
    CONSTRAINT payment_provider_webhook_event_status_check CHECK (((processing_status)::text = ANY ((ARRAY['RECEIVED'::character varying, 'PROCESSED'::character varying, 'FAILED'::character varying])::text[])))
);

--
-- Name: payment_recovery_job; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE payment_recovery_job (
    recovery_job_id uuid NOT NULL,
    payment_attempt_id uuid,
    payment_refund_operation_id uuid,
    operation_type character varying(24) NOT NULL,
    status character varying(24) NOT NULL,
    available_at timestamp with time zone NOT NULL,
    first_enqueued_at timestamp with time zone NOT NULL,
    retry_window_started_at timestamp with time zone NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    manual_requeue_count integer DEFAULT 0 NOT NULL,
    lease_token uuid,
    lease_until timestamp with time zone,
    last_attempt_at timestamp with time zone,
    last_error_code character varying(64),
    updated_at timestamp with time zone NOT NULL,
    work_stage character varying(16) DEFAULT 'PROVIDER'::character varying NOT NULL,
    CONSTRAINT payment_recovery_job_attempt_count_check CHECK (((attempt_count >= 0) AND (manual_requeue_count >= 0))),
    CONSTRAINT payment_recovery_job_lease_check CHECK (((lease_token IS NULL) = (lease_until IS NULL))),
    CONSTRAINT payment_recovery_job_operation_reference_check CHECK (((((operation_type)::text = 'PAYMENT_ATTEMPT'::text) AND (payment_attempt_id IS NOT NULL) AND (payment_refund_operation_id IS NULL)) OR (((operation_type)::text = 'PAYMENT_REFUND'::text) AND (payment_attempt_id IS NULL) AND (payment_refund_operation_id IS NOT NULL)))),
    CONSTRAINT payment_recovery_job_operation_type_check CHECK (((operation_type)::text = ANY ((ARRAY['PAYMENT_ATTEMPT'::character varying, 'PAYMENT_REFUND'::character varying])::text[]))),
    CONSTRAINT payment_recovery_job_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'WAITING_FOR_BUYER'::character varying, 'COMPLETED'::character varying, 'NEEDS_REVIEW'::character varying])::text[]))),
    CONSTRAINT payment_recovery_job_work_stage_check CHECK (((work_stage)::text = ANY ((ARRAY['PROVIDER'::character varying, 'APPLY'::character varying])::text[])))
);

--
-- Name: payment_recovery_operator_action; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE payment_recovery_operator_action (
    operator_action_id uuid NOT NULL,
    recovery_job_id uuid NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    actor character varying(128) NOT NULL,
    reason character varying(500) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT payment_recovery_operator_action_reason_check CHECK (((length(TRIM(BOTH FROM reason)) >= 1) AND (length(TRIM(BOTH FROM reason)) <= 500)))
);

--
-- Name: payment_refund_operation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE payment_refund_operation (
    refund_operation_id uuid NOT NULL,
    attempt_id uuid NOT NULL,
    capture_id character varying(64) NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    amount_won bigint NOT NULL,
    currency character(3) NOT NULL,
    provider_request_id uuid NOT NULL,
    provider_refund_id character varying(64),
    provider_status character varying(32),
    refund_state character varying(16) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    provider_amount_minor bigint,
    provider_currency character varying(3),
    CONSTRAINT payment_refund_operation_amount_positive CHECK ((amount_won > 0)),
    CONSTRAINT payment_refund_operation_currency_krw CHECK ((currency = 'KRW'::bpchar)),
    CONSTRAINT payment_refund_operation_state_check CHECK (((refund_state)::text = ANY ((ARRAY['PENDING'::character varying, 'UNKNOWN'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying])::text[]))),
    CONSTRAINT payment_refund_provider_amount_check CHECK ((((provider_amount_minor IS NULL) AND (provider_currency IS NULL)) OR ((provider_amount_minor > 0) AND (provider_currency IS NOT NULL))))
);

--
-- Name: paypal_balance_report_disposition; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE paypal_balance_report_disposition (
    disposition_id uuid NOT NULL,
    run_id uuid NOT NULL,
    row_number integer NOT NULL,
    disposition character varying(32) NOT NULL,
    actor character varying(128) NOT NULL,
    reason character varying(500) NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT paypal_balance_report_disposition_disposition_check CHECK (((disposition)::text = ANY ((ARRAY['CONFIRMED_EXTERNAL'::character varying, 'DEFERRED'::character varying, 'NEEDS_REVIEW'::character varying])::text[]))),
    CONSTRAINT paypal_balance_report_disposition_reason_check CHECK (((length(btrim((reason)::text)) >= 1) AND (length(btrim((reason)::text)) <= 500)))
);

--
-- Name: paypal_balance_report_row; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE paypal_balance_report_row (
    run_id uuid NOT NULL,
    row_number integer NOT NULL,
    record_id character varying(256) NOT NULL,
    related_record_id character varying(256),
    original_record_id character varying(256),
    record_type character varying(64) NOT NULL,
    record_subtype character varying(64),
    currency character varying(3),
    gross_amount numeric(20,6),
    fee_amount numeric(20,6),
    net_amount numeric(20,6),
    transfer_id character varying(256),
    status character varying(16) NOT NULL,
    reason_code character varying(64),
    CONSTRAINT paypal_balance_report_row_row_number_check CHECK (((row_number >= 1) AND (row_number <= 10000))),
    CONSTRAINT paypal_balance_report_row_status_check CHECK (((status)::text = ANY ((ARRAY['MATCHED'::character varying, 'UNMATCHED'::character varying, 'DUPLICATE'::character varying, 'MISMATCH'::character varying])::text[])))
);

--
-- Name: paypal_balance_report_run; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE paypal_balance_report_run (
    run_id uuid NOT NULL,
    report_sha256 character varying(64) NOT NULL,
    uploaded_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    report_format character varying(16) NOT NULL,
    row_count integer NOT NULL,
    matched_count integer NOT NULL,
    unmatched_count integer NOT NULL,
    mismatch_count integer NOT NULL,
    duplicate_count integer NOT NULL,
    CONSTRAINT paypal_balance_report_run_check CHECK (((((matched_count + unmatched_count) + mismatch_count) + duplicate_count) = row_count)),
    CONSTRAINT paypal_balance_report_run_duplicate_count_check CHECK ((duplicate_count >= 0)),
    CONSTRAINT paypal_balance_report_run_matched_count_check CHECK ((matched_count >= 0)),
    CONSTRAINT paypal_balance_report_run_mismatch_count_check CHECK ((mismatch_count >= 0)),
    CONSTRAINT paypal_balance_report_run_report_sha256_check CHECK (((report_sha256)::text ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT paypal_balance_report_run_row_count_check CHECK (((row_count >= 0) AND (row_count <= 10000))),
    CONSTRAINT paypal_balance_report_run_unmatched_count_check CHECK ((unmatched_count >= 0))
);

--
-- Name: paypal_webhook_retry; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE paypal_webhook_retry (
    event_id character varying(128) NOT NULL,
    event_type character varying(128) NOT NULL,
    resource_id character varying(128),
    resource_status character varying(64),
    provider_order_id character varying(128),
    related_capture_id character varying(128),
    resource_amount numeric(19,4),
    resource_currency character varying(3),
    attempt_count integer DEFAULT 0 NOT NULL,
    state character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    next_attempt_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone NOT NULL,
    lease_owner character varying(36),
    lease_until timestamp with time zone,
    last_error character varying(1000),
    finished_at timestamp with time zone,
    manual_requeue_count integer DEFAULT 0 NOT NULL,
    CONSTRAINT paypal_webhook_retry_attempt_count_check CHECK (((attempt_count >= 0) AND (attempt_count <= 8))),
    CONSTRAINT paypal_webhook_retry_exhausted_terminal CHECK ((((state)::text <> 'EXHAUSTED'::text) OR (finished_at IS NOT NULL))),
    CONSTRAINT paypal_webhook_retry_manual_requeue_count_check CHECK (((manual_requeue_count >= 0) AND (manual_requeue_count <= 3))),
    CONSTRAINT paypal_webhook_retry_state_check CHECK (((state)::text = ANY ((ARRAY['PENDING'::character varying, 'EXHAUSTED'::character varying])::text[])))
);

--
-- Name: paypal_webhook_retry_attempt_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE paypal_webhook_retry_attempt_history (
    lease_token character varying(36) NOT NULL,
    event_id character varying(128) NOT NULL,
    attempt_number integer NOT NULL,
    outcome character varying(32) DEFAULT 'CLAIMED'::character varying NOT NULL,
    claimed_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    finished_at timestamp with time zone,
    recorded_at timestamp with time zone GENERATED ALWAYS AS (COALESCE(finished_at, claimed_at)) STORED,
    CONSTRAINT paypal_webhook_retry_attempt_history_attempt_number_check CHECK ((attempt_number > 0)),
    CONSTRAINT paypal_webhook_retry_attempt_history_check CHECK ((((outcome)::text = 'CLAIMED'::text) = (finished_at IS NULL))),
    CONSTRAINT paypal_webhook_retry_attempt_history_outcome_check CHECK (((outcome)::text = ANY ((ARRAY['CLAIMED'::character varying, 'CORRELATION_MISS'::character varying, 'TRANSIENT_ERROR'::character varying, 'PROCESSED'::character varying, 'PERMANENT_REJECT'::character varying, 'EXHAUSTED'::character varying, 'LEASE_EXPIRED'::character varying])::text[])))
);

--
-- Name: paypal_webhook_retry_operator_audit; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE paypal_webhook_retry_operator_audit (
    idempotency_key character varying(128) NOT NULL,
    event_id character varying(128) NOT NULL,
    actor character varying(128) NOT NULL,
    reason character varying(500) NOT NULL,
    outcome character varying(16) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT paypal_webhook_retry_operator_audit_outcome_check CHECK (((outcome)::text = 'REQUEUED'::text))
);

--
-- Name: product; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE product (
    product_id uuid NOT NULL,
    seller_id uuid NOT NULL,
    name text NOT NULL,
    description text,
    primary_category_id uuid CONSTRAINT product_category_id_not_null NOT NULL,
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    CONSTRAINT product_name_nonblank CHECK ((length(TRIM(BOTH FROM name)) > 0)),
    CONSTRAINT product_status_allowed CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying])::text[])))
);

--
-- Name: product_category; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE product_category (
    category_id uuid DEFAULT uuidv7() NOT NULL,
    seller_id uuid NOT NULL,
    code character varying(128) NOT NULL,
    name text NOT NULL,
    parent_category_id uuid,
    CONSTRAINT product_category_code_nonblank CHECK ((length(TRIM(BOTH FROM code)) > 0)),
    CONSTRAINT product_category_name_nonblank CHECK ((length(TRIM(BOTH FROM name)) > 0)),
    CONSTRAINT product_category_not_self_parent CHECK (((parent_category_id IS NULL) OR (parent_category_id <> category_id)))
);

--
-- Name: product_category_assignment; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE product_category_assignment (
    seller_id uuid NOT NULL,
    product_id uuid NOT NULL,
    category_id uuid NOT NULL
);

--
-- Name: product_channel_availability; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE product_channel_availability (
    product_id uuid NOT NULL,
    sales_channel character varying(32) NOT NULL,
    available boolean NOT NULL,
    CONSTRAINT product_channel_availability_channel_allowed CHECK (((sales_channel)::text = ANY ((ARRAY['ONLINE_STORE'::character varying, 'CAMPAIGN'::character varying])::text[])))
);

--
-- Name: product_listing_projection; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE product_listing_projection (
    product_id uuid NOT NULL,
    seller_id uuid NOT NULL,
    status character varying(20) NOT NULL,
    primary_category_id uuid,
    channels text[] DEFAULT '{}'::text[] NOT NULL,
    categories uuid[] DEFAULT '{}'::uuid[] NOT NULL,
    updated_at timestamp with time zone NOT NULL
);

--
-- Name: product_review; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE product_review (
    review_id uuid DEFAULT uuidv7() NOT NULL,
    buyer_ref character varying(256) NOT NULL,
    product_id uuid NOT NULL,
    order_item_id uuid NOT NULL,
    rating smallint NOT NULL,
    body character varying(2000) NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT product_review_body_check CHECK (((length(btrim((body)::text)) >= 1) AND (length(btrim((body)::text)) <= 2000))),
    CONSTRAINT product_review_buyer_nonblank CHECK ((length(btrim((buyer_ref)::text)) > 0)),
    CONSTRAINT product_review_rating_check CHECK (((rating >= 1) AND (rating <= 5)))
);

--
-- Name: purchase_order; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE purchase_order (
    order_id uuid NOT NULL,
    seller_id uuid NOT NULL,
    campaign_id uuid,
    ticket_id uuid,
    buyer_ref character varying(256) NOT NULL,
    state character varying(32) NOT NULL,
    checkout_expires_at timestamp with time zone NOT NULL,
    payment_attempt_id uuid,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    order_source character varying(16) DEFAULT 'CAMPAIGN'::character varying NOT NULL,
    checkout_key character varying(256),
    checkout_id uuid,
    fulfillment_location_id uuid,
    seller_coupon_id uuid,
    seller_coupon_code character varying(32),
    coupon_discount_won bigint DEFAULT 0 NOT NULL,
    reservation_workflow_id uuid,
    CONSTRAINT purchase_order_checkout_key_nonblank CHECK (((checkout_key IS NULL) OR (length(TRIM(BOTH FROM checkout_key)) > 0))),
    CONSTRAINT purchase_order_checkout_source_valid CHECK (((((order_source)::text = 'CAMPAIGN'::text) AND (checkout_id IS NULL)) OR (((order_source)::text = 'CART'::text) AND (checkout_id IS NOT NULL)))),
    CONSTRAINT purchase_order_coupon_snapshot_check CHECK (((coupon_discount_won >= 0) AND (((seller_coupon_id IS NULL) AND (seller_coupon_code IS NULL) AND (coupon_discount_won = 0)) OR ((seller_coupon_id IS NOT NULL) AND (seller_coupon_code IS NOT NULL) AND (coupon_discount_won > 0))))),
    CONSTRAINT purchase_order_source_context CHECK (((((order_source)::text = 'CAMPAIGN'::text) AND (campaign_id IS NOT NULL) AND (ticket_id IS NOT NULL)) OR (((order_source)::text = 'CART'::text) AND (campaign_id IS NULL) AND (ticket_id IS NULL)))),
    CONSTRAINT purchase_order_source_valid CHECK (((order_source)::text = ANY ((ARRAY['CAMPAIGN'::character varying, 'CART'::character varying])::text[]))),
    CONSTRAINT purchase_order_state CHECK (((state)::text = ANY ((ARRAY['RESERVATION_PENDING'::character varying, 'RESERVED'::character varying, 'PAYMENT_SUBMITTED'::character varying, 'PAYMENT_PENDING'::character varying, 'PAYMENT_UNKNOWN'::character varying, 'PAYMENT_VOID_PENDING'::character varying, 'INVENTORY_CLAIM_PENDING'::character varying, 'PAID'::character varying, 'FAILED'::character varying, 'EXPIRED'::character varying, 'CANCEL_PENDING'::character varying, 'CANCELLED'::character varying])::text[])))
);

--
-- Name: refresh_token_family; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE refresh_token_family (
    family_id uuid DEFAULT uuidv7() NOT NULL,
    account_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    CONSTRAINT ck_refresh_family_expiry CHECK ((expires_at > created_at))
);

--
-- Name: seller; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE seller (
    seller_id uuid NOT NULL
);

--
-- Name: seller_account; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE seller_account (
    account_id uuid NOT NULL,
    seller_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

--
-- Name: seller_coupon; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE seller_coupon (
    coupon_id uuid NOT NULL,
    seller_id uuid NOT NULL,
    code character varying(32) NOT NULL,
    discount_amount_won bigint NOT NULL,
    starts_at timestamp with time zone NOT NULL,
    ends_at timestamp with time zone NOT NULL,
    active boolean DEFAULT true NOT NULL,
    max_redemptions bigint,
    reserved_count bigint DEFAULT 0 NOT NULL,
    redeemed_count bigint DEFAULT 0 NOT NULL,
    CONSTRAINT seller_coupon_max_redemptions_check CHECK (((max_redemptions IS NULL) OR (max_redemptions > 0))),
    CONSTRAINT seller_coupon_positive_amount CHECK ((discount_amount_won > 0)),
    CONSTRAINT seller_coupon_redemption_counts_check CHECK (((reserved_count >= 0) AND (redeemed_count >= 0) AND ((max_redemptions IS NULL) OR (((reserved_count)::numeric + (redeemed_count)::numeric) <= (max_redemptions)::numeric)))),
    CONSTRAINT seller_coupon_valid_window CHECK ((starts_at < ends_at))
);

--
-- Name: seller_coupon_redemption; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE seller_coupon_redemption (
    redemption_id uuid NOT NULL,
    seller_id uuid NOT NULL,
    coupon_id uuid NOT NULL,
    order_id uuid NOT NULL,
    state character varying(16) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT seller_coupon_redemption_state_check CHECK (((state)::text = ANY ((ARRAY['RESERVED'::character varying, 'REDEEMED'::character varying, 'RELEASED'::character varying])::text[])))
);

--
-- Name: seller_payout_profile; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE seller_payout_profile (
    seller_id uuid NOT NULL,
    recipient_account_id uuid NOT NULL,
    country_code character(2) NOT NULL,
    payout_currency character(3) DEFAULT 'USD'::bpchar NOT NULL,
    status character varying(20) DEFAULT 'PENDING_REVIEW'::character varying NOT NULL,
    profile_version integer DEFAULT 1 NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    verified_at timestamp with time zone,
    verified_by character varying(128),
    CONSTRAINT seller_payout_profile_country_check CHECK ((country_code ~ '^[A-Z]{2}$'::text)),
    CONSTRAINT seller_payout_profile_currency_check CHECK ((payout_currency = 'USD'::bpchar)),
    CONSTRAINT seller_payout_profile_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING_REVIEW'::character varying, 'ACTIVE'::character varying, 'SUSPENDED'::character varying])::text[]))),
    CONSTRAINT seller_payout_profile_verification_check CHECK (((((status)::text = 'ACTIVE'::text) AND (verified_at IS NOT NULL) AND (verified_by IS NOT NULL)) OR ((status)::text <> 'ACTIVE'::text))),
    CONSTRAINT seller_payout_profile_version_check CHECK ((profile_version > 0))
);

--
-- Name: seller_payout_profile_action; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE seller_payout_profile_action (
    action_id uuid DEFAULT uuidv7() NOT NULL,
    seller_id uuid NOT NULL,
    profile_version integer NOT NULL,
    action character varying(24) NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    request_facts_sha256 character(64) NOT NULL,
    actor character varying(128) NOT NULL,
    reason character varying(500) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT seller_payout_profile_action_reason_check CHECK (((length(TRIM(BOTH FROM reason)) >= 1) AND (length(TRIM(BOTH FROM reason)) <= 500))),
    CONSTRAINT seller_payout_profile_action_version_check CHECK ((profile_version > 0))
);

--
-- Name: seller_sku; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE seller_sku (
    seller_id uuid NOT NULL,
    sku_id character varying(128) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    product_id uuid NOT NULL,
    price_won bigint NOT NULL,
    sale_price_won bigint,
    sale_starts_at timestamp with time zone,
    sale_ends_at timestamp with time zone,
    CONSTRAINT seller_sku_code_nonblank CHECK ((length(TRIM(BOTH FROM sku_id)) > 0)),
    CONSTRAINT seller_sku_price_positive CHECK ((price_won > 0)),
    CONSTRAINT seller_sku_sale_price_window_check CHECK ((((sale_price_won IS NULL) AND (sale_starts_at IS NULL) AND (sale_ends_at IS NULL)) OR ((sale_price_won IS NOT NULL) AND (sale_price_won > 0) AND (sale_price_won < price_won) AND (sale_starts_at IS NOT NULL) AND (sale_ends_at IS NOT NULL) AND (sale_starts_at < sale_ends_at))))
);

--
-- Name: seller_sku_admission_policy; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE seller_sku_admission_policy (
    seller_id uuid NOT NULL,
    sku_id character varying(128) NOT NULL,
    max_active_checkouts integer NOT NULL,
    CONSTRAINT seller_sku_admission_policy_positive CHECK ((max_active_checkouts > 0))
);

--
-- Name: settlement_entry; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE settlement_entry (
    entry_id uuid DEFAULT uuidv7() NOT NULL,
    journal_id uuid NOT NULL,
    account_code character varying(32) NOT NULL,
    entry_side character varying(6) NOT NULL,
    amount_won bigint NOT NULL,
    recorded_at timestamp with time zone NOT NULL,
    seller_id uuid,
    CONSTRAINT settlement_entry_account_side CHECK (((((account_code)::text = 'PSP_CLEARING'::text) AND ((entry_side)::text = ANY ((ARRAY['DEBIT'::character varying, 'CREDIT'::character varying])::text[]))) OR (((account_code)::text = 'SELLER_PAYABLE'::text) AND ((entry_side)::text = ANY ((ARRAY['DEBIT'::character varying, 'CREDIT'::character varying])::text[]))) OR (((account_code)::text = 'PLATFORM_FEE'::text) AND ((entry_side)::text = 'CREDIT'::text)) OR (((account_code)::text = 'SELLER_RESERVE'::text) AND ((entry_side)::text = ANY ((ARRAY['DEBIT'::character varying, 'CREDIT'::character varying])::text[]))) OR (((account_code)::text = 'PLATFORM_PAYOUT_FEE'::text) AND ((entry_side)::text = 'DEBIT'::text)) OR (((account_code)::text = 'PLATFORM_PAYOUT_FX_ROUNDING'::text) AND ((entry_side)::text = 'DEBIT'::text)))),
    CONSTRAINT settlement_entry_amount_positive CHECK ((amount_won > 0))
);

--
-- Name: settlement_journal; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE settlement_journal (
    journal_id uuid DEFAULT uuidv7() NOT NULL,
    source_order_id uuid,
    source_attempt_id uuid,
    seller_id uuid,
    currency character(3) DEFAULT 'KRW'::bpchar NOT NULL,
    gross_amount_won bigint NOT NULL,
    recorded_at timestamp with time zone NOT NULL,
    source_refund_id uuid,
    checkout_id uuid,
    fee_amount_won bigint DEFAULT 0 NOT NULL,
    reserve_amount_won bigint DEFAULT 0 NOT NULL,
    fee_rate_bps integer DEFAULT 0 NOT NULL,
    reserve_rate_bps integer DEFAULT 0 NOT NULL,
    source_payout_id uuid,
    CONSTRAINT settlement_journal_amount_positive CHECK ((gross_amount_won > 0)),
    CONSTRAINT settlement_journal_fee_nonnegative CHECK ((fee_amount_won >= 0)),
    CONSTRAINT settlement_journal_krw_only CHECK ((currency = 'KRW'::bpchar)),
    CONSTRAINT settlement_journal_owner_xor CHECK ((((source_order_id IS NOT NULL) AND (checkout_id IS NULL) AND (source_refund_id IS NULL) AND (source_attempt_id IS NOT NULL) AND (source_payout_id IS NULL)) OR ((source_order_id IS NULL) AND (checkout_id IS NOT NULL) AND (source_refund_id IS NULL) AND (source_attempt_id IS NOT NULL) AND (source_payout_id IS NULL)) OR ((source_order_id IS NOT NULL) AND (checkout_id IS NULL) AND (source_refund_id IS NOT NULL) AND (source_attempt_id IS NULL) AND (source_payout_id IS NULL)) OR ((source_order_id IS NULL) AND (checkout_id IS NULL) AND (source_refund_id IS NULL) AND (source_attempt_id IS NULL) AND (source_payout_id IS NOT NULL) AND (seller_id IS NOT NULL)))),
    CONSTRAINT settlement_journal_rates_valid CHECK ((((fee_rate_bps >= 0) AND (fee_rate_bps <= 10000)) AND ((reserve_rate_bps >= 0) AND (reserve_rate_bps <= 10000)))),
    CONSTRAINT settlement_journal_reserve_nonnegative CHECK ((reserve_amount_won >= 0)),
    CONSTRAINT settlement_journal_split_within_gross CHECK (((fee_amount_won + reserve_amount_won) <= gross_amount_won))
);

--
-- Name: settlement_payout; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE settlement_payout (
    payout_id uuid DEFAULT uuidv7() NOT NULL,
    seller_id uuid NOT NULL,
    period_start timestamp with time zone NOT NULL,
    period_end timestamp with time zone NOT NULL,
    amount_won bigint NOT NULL,
    state character varying(16) NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    requested_at timestamp with time zone NOT NULL,
    sent_at timestamp with time zone,
    confirmed_at timestamp with time zone,
    failure_code character varying(64),
    updated_at timestamp with time zone NOT NULL,
    settled_amount_won bigint DEFAULT 0 NOT NULL,
    cancelled_at timestamp with time zone,
    provider_debit_book_value_won bigint DEFAULT 0 NOT NULL,
    payout_fx_rounding_expense_won bigint DEFAULT 0 NOT NULL,
    provider_fee_won bigint DEFAULT 0 NOT NULL,
    CONSTRAINT settlement_payout_amount_nonnegative CHECK ((amount_won >= 0)),
    CONSTRAINT settlement_payout_key_nonblank CHECK ((length(TRIM(BOTH FROM idempotency_key)) > 0)),
    CONSTRAINT settlement_payout_period_valid CHECK ((period_end > period_start)),
    CONSTRAINT settlement_payout_provider_amount_valid CHECK (((provider_debit_book_value_won >= 0) AND (payout_fx_rounding_expense_won >= 0) AND (provider_fee_won >= 0) AND (((provider_debit_book_value_won = 0) AND (payout_fx_rounding_expense_won = 0) AND (provider_fee_won = 0)) OR (provider_debit_book_value_won = (amount_won + payout_fx_rounding_expense_won))))),
    CONSTRAINT settlement_payout_settled_amount_valid CHECK (((settled_amount_won >= 0) AND (settled_amount_won <= amount_won))),
    CONSTRAINT settlement_payout_state_valid CHECK (((state)::text = ANY ((ARRAY['REQUESTED'::character varying, 'APPROVED'::character varying, 'DISPATCHING'::character varying, 'SENT'::character varying, 'UNKNOWN'::character varying, 'UNCLAIMED'::character varying, 'NEEDS_REVIEW'::character varying, 'CANCELLED'::character varying, 'CONFIRMED'::character varying, 'FAILED'::character varying])::text[])))
);

--
-- Name: settlement_payout_dispatch_outbox; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE settlement_payout_dispatch_outbox (
    payout_id uuid NOT NULL,
    profile_version integer NOT NULL,
    recipient_email_ciphertext text CONSTRAINT settlement_payout_dispatch__recipient_email_ciphertext_not_null NOT NULL,
    recipient_country_code character(2) CONSTRAINT settlement_payout_dispatch_outb_recipient_country_code_not_null NOT NULL,
    payout_currency character(3) DEFAULT 'USD'::bpchar NOT NULL,
    requested_amount_won bigint NOT NULL,
    provider_amount_minor bigint CONSTRAINT settlement_payout_dispatch_outbo_provider_amount_minor_not_null NOT NULL,
    fx_rate_won_per_usd numeric(18,8) NOT NULL,
    fx_source character varying(32) NOT NULL,
    fx_as_of_date date NOT NULL,
    provider_debit_book_value_won bigint CONSTRAINT settlement_payout_dispatch__provider_debit_book_value__not_null NOT NULL,
    payout_fx_rounding_expense_won bigint CONSTRAINT settlement_payout_dispatch__payout_fx_rounding_expense_not_null NOT NULL,
    provider_fee_minor bigint,
    provider_fee_currency character(3),
    provider_fee_won bigint,
    sender_batch_id character varying(256) NOT NULL,
    sender_item_id character varying(256) NOT NULL,
    paypal_request_id character varying(256) NOT NULL,
    request_payload_sha256 character(64) CONSTRAINT settlement_payout_dispatch_outb_request_payload_sha256_not_null NOT NULL,
    provider_batch_id character varying(128),
    provider_item_id character varying(128),
    provider_batch_status character varying(32),
    provider_item_status character varying(32),
    status character varying(24) NOT NULL,
    available_at timestamp with time zone NOT NULL,
    first_enqueued_at timestamp with time zone NOT NULL,
    attempt_count integer DEFAULT 0 NOT NULL,
    lease_token uuid,
    lease_until timestamp with time zone,
    last_attempt_at timestamp with time zone,
    last_error_code character varying(64),
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT settlement_payout_dispatch_amount_check CHECK (((requested_amount_won > 0) AND (provider_amount_minor > 0) AND (provider_debit_book_value_won >= requested_amount_won) AND (payout_fx_rounding_expense_won = (provider_debit_book_value_won - requested_amount_won)))),
    CONSTRAINT settlement_payout_dispatch_attempt_check CHECK ((attempt_count >= 0)),
    CONSTRAINT settlement_payout_dispatch_country_check CHECK ((recipient_country_code ~ '^[A-Z]{2}$'::text)),
    CONSTRAINT settlement_payout_dispatch_currency_check CHECK ((payout_currency = 'USD'::bpchar)),
    CONSTRAINT settlement_payout_dispatch_fee_check CHECK ((((provider_fee_minor IS NULL) AND (provider_fee_currency IS NULL) AND (provider_fee_won IS NULL)) OR ((provider_fee_minor IS NOT NULL) AND (provider_fee_minor >= 0) AND (provider_fee_currency = 'USD'::bpchar) AND (provider_fee_won IS NOT NULL) AND (provider_fee_won >= 0)))),
    CONSTRAINT settlement_payout_dispatch_fx_check CHECK ((fx_rate_won_per_usd > (0)::numeric)),
    CONSTRAINT settlement_payout_dispatch_lease_check CHECK (((lease_token IS NULL) = (lease_until IS NULL))),
    CONSTRAINT settlement_payout_dispatch_lease_status_check CHECK ((((status)::text = 'LEASED'::text) = (lease_token IS NOT NULL))),
    CONSTRAINT settlement_payout_dispatch_profile_version_check CHECK ((profile_version > 0)),
    CONSTRAINT settlement_payout_dispatch_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'LEASED'::character varying, 'WAITING_PROVIDER'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying, 'NEEDS_REVIEW'::character varying])::text[]))),
    CONSTRAINT settlement_payout_dispatch_waiting_provider_check CHECK ((((status)::text <> 'WAITING_PROVIDER'::text) OR (provider_batch_id IS NOT NULL)))
);

--
-- Name: settlement_payout_operator_action; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE settlement_payout_operator_action (
    action_id uuid DEFAULT uuidv7() NOT NULL,
    payout_id uuid NOT NULL,
    idempotency_key character varying(128) NOT NULL,
    action character varying(32) NOT NULL,
    actor character varying(128) NOT NULL,
    reason character varying(500) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    CONSTRAINT settlement_payout_operator_action_reason_check CHECK (((length(TRIM(BOTH FROM reason)) >= 1) AND (length(TRIM(BOTH FROM reason)) <= 500)))
);

--
-- Name: settlement_refund_reservation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE settlement_refund_reservation (
    refund_id uuid NOT NULL,
    order_id uuid NOT NULL,
    seller_id uuid NOT NULL,
    amount_won bigint NOT NULL,
    status character varying(16) NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    CONSTRAINT settlement_refund_reservation_amount_check CHECK ((amount_won > 0)),
    CONSTRAINT settlement_refund_reservation_status_check CHECK (((status)::text = ANY ((ARRAY['RESERVED'::character varying, 'POSTED'::character varying, 'RELEASED'::character varying])::text[])))
);

--
-- Name: settlement_seller_allocation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE settlement_seller_allocation (
    journal_id uuid NOT NULL,
    order_id uuid NOT NULL,
    seller_id uuid NOT NULL,
    amount_won bigint NOT NULL,
    allocation_ordinal integer NOT NULL,
    CONSTRAINT settlement_seller_allocation_amount_positive CHECK ((amount_won > 0)),
    CONSTRAINT settlement_seller_allocation_ordinal_nonnegative CHECK ((allocation_ordinal >= 0))
);

--
-- Name: shopping_cart; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE shopping_cart (
    cart_id uuid NOT NULL,
    buyer_ref character varying(256) NOT NULL,
    CONSTRAINT shopping_cart_buyer_nonblank CHECK ((length(TRIM(BOTH FROM buyer_ref)) > 0))
);

--
-- Name: shopping_cart_item; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE shopping_cart_item (
    cart_item_id uuid NOT NULL,
    cart_id uuid NOT NULL,
    seller_id uuid NOT NULL,
    sku_id character varying(128) NOT NULL,
    quantity integer NOT NULL,
    CONSTRAINT shopping_cart_item_quantity_positive CHECK ((quantity > 0))
);

--
-- Name: user_account; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE user_account (
    account_id uuid DEFAULT uuidv7() NOT NULL,
    email character varying(320) NOT NULL,
    password_hash character varying(255) NOT NULL,
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    email_verified_at timestamp with time zone,
    CONSTRAINT ck_user_account_password_hash CHECK ((length(btrim((password_hash)::text)) > 0)),
    CONSTRAINT ck_user_account_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'DISABLED'::character varying])::text[])))
);

--
-- Name: account_action_token account_action_token_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY account_action_token
    ADD CONSTRAINT account_action_token_pkey PRIMARY KEY (token_id);

--
-- Name: account_action_token account_action_token_token_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY account_action_token
    ADD CONSTRAINT account_action_token_token_hash_key UNIQUE (token_hash);

--
-- Name: account_mfa_challenge account_mfa_challenge_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY account_mfa_challenge
    ADD CONSTRAINT account_mfa_challenge_pkey PRIMARY KEY (challenge_hash);

--
-- Name: account_mfa_recovery_code account_mfa_recovery_code_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY account_mfa_recovery_code
    ADD CONSTRAINT account_mfa_recovery_code_pkey PRIMARY KEY (account_id, code_hash);

--
-- Name: account_mfa_secret account_mfa_secret_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY account_mfa_secret
    ADD CONSTRAINT account_mfa_secret_pkey PRIMARY KEY (account_id);

--
-- Name: account_refresh_token account_refresh_token_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY account_refresh_token
    ADD CONSTRAINT account_refresh_token_pkey PRIMARY KEY (token_id);

--
-- Name: account_refresh_token account_refresh_token_token_hash_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY account_refresh_token
    ADD CONSTRAINT account_refresh_token_token_hash_key UNIQUE (token_hash);

--
-- Name: account_suspension_action account_suspension_action_key_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY account_suspension_action
    ADD CONSTRAINT account_suspension_action_key_unique UNIQUE (account_id, idempotency_key);

--
-- Name: account_suspension_action account_suspension_action_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY account_suspension_action
    ADD CONSTRAINT account_suspension_action_pkey PRIMARY KEY (suspension_action_id);

--
-- Name: admission_counter admission_counter_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY admission_counter
    ADD CONSTRAINT admission_counter_pk PRIMARY KEY (seller_id, sku_id);

--
-- Name: admission_ticket admission_ticket_idempotency_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY admission_ticket
    ADD CONSTRAINT admission_ticket_idempotency_unique UNIQUE (campaign_id, idempotency_key);

--
-- Name: admission_ticket admission_ticket_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY admission_ticket
    ADD CONSTRAINT admission_ticket_pk PRIMARY KEY (ticket_id);

--
-- Name: admission_ticket admission_ticket_seller_sku_sequence_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY admission_ticket
    ADD CONSTRAINT admission_ticket_seller_sku_sequence_unique UNIQUE (seller_id, sku_id, sequence);

--
-- Name: auth_origin_bucket auth_origin_bucket_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY auth_origin_bucket
    ADD CONSTRAINT auth_origin_bucket_pk PRIMARY KEY (origin_key, scope);

--
-- Name: auth_subject_bucket auth_subject_bucket_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY auth_subject_bucket
    ADD CONSTRAINT auth_subject_bucket_pk PRIMARY KEY (subject_key, scope);

--
-- Name: buyer_account buyer_account_buyer_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY buyer_account
    ADD CONSTRAINT buyer_account_buyer_id_key UNIQUE (buyer_id);

--
-- Name: buyer_account buyer_account_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY buyer_account
    ADD CONSTRAINT buyer_account_pkey PRIMARY KEY (account_id);

--
-- Name: buyer_checkout buyer_checkout_key_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY buyer_checkout
    ADD CONSTRAINT buyer_checkout_key_unique UNIQUE (buyer_ref, idempotency_key);

--
-- Name: buyer_checkout buyer_checkout_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY buyer_checkout
    ADD CONSTRAINT buyer_checkout_pkey PRIMARY KEY (checkout_id);

--
-- Name: buyer buyer_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY buyer
    ADD CONSTRAINT buyer_pkey PRIMARY KEY (buyer_id);

--
-- Name: buyer_wishlist_item buyer_wishlist_item_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY buyer_wishlist_item
    ADD CONSTRAINT buyer_wishlist_item_pkey PRIMARY KEY (buyer_ref, product_id);

--
-- Name: campaign campaign_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY campaign
    ADD CONSTRAINT campaign_pk PRIMARY KEY (campaign_id);

--
-- Name: inventory_capacity_adjustment inventory_capacity_adjustment_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY inventory_capacity_adjustment
    ADD CONSTRAINT inventory_capacity_adjustment_key PRIMARY KEY (seller_id, location_id, sku_id, idempotency_key);

--
-- Name: inventory_location inventory_location_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY inventory_location
    ADD CONSTRAINT inventory_location_pkey PRIMARY KEY (location_id);

--
-- Name: inventory_location inventory_location_seller_code_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY inventory_location
    ADD CONSTRAINT inventory_location_seller_code_unique UNIQUE (seller_id, location_code);

--
-- Name: inventory_location inventory_location_seller_location_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY inventory_location
    ADD CONSTRAINT inventory_location_seller_location_unique UNIQUE (seller_id, location_id);

--
-- Name: inventory_pool inventory_pool_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY inventory_pool
    ADD CONSTRAINT inventory_pool_pk PRIMARY KEY (seller_id, location_id, sku_id);

--
-- Name: inventory_reservation inventory_reservation_order_sku_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY inventory_reservation
    ADD CONSTRAINT inventory_reservation_order_sku_unique UNIQUE (order_id, seller_id, sku_id);

--
-- Name: inventory_reservation inventory_reservation_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY inventory_reservation
    ADD CONSTRAINT inventory_reservation_pk PRIMARY KEY (reservation_id);

--
-- Name: inventory_transfer inventory_transfer_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY inventory_transfer
    ADD CONSTRAINT inventory_transfer_key UNIQUE (seller_id, sku_id, idempotency_key);

--
-- Name: inventory_transfer inventory_transfer_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY inventory_transfer
    ADD CONSTRAINT inventory_transfer_pkey PRIMARY KEY (transfer_id);

--
-- Name: order_fulfillment order_fulfillment_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_fulfillment
    ADD CONSTRAINT order_fulfillment_pk PRIMARY KEY (order_id);

--
-- Name: order_inventory_event_cursor order_inventory_event_cursor_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_inventory_event_cursor
    ADD CONSTRAINT order_inventory_event_cursor_pkey PRIMARY KEY (consumer_group);

--
-- Name: order_inventory_event order_inventory_event_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_inventory_event
    ADD CONSTRAINT order_inventory_event_pkey PRIMARY KEY (event_seq);

--
-- Name: order_inventory_event order_inventory_event_version_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_inventory_event
    ADD CONSTRAINT order_inventory_event_version_unique UNIQUE (workflow_id, aggregate_version);

--
-- Name: order_inventory_location_command order_inventory_location_command_idempotency_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_inventory_location_command
    ADD CONSTRAINT order_inventory_location_command_idempotency_key_key UNIQUE (idempotency_key);

--
-- Name: order_inventory_location_command order_inventory_location_command_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_inventory_location_command
    ADD CONSTRAINT order_inventory_location_command_pkey PRIMARY KEY (workflow_id, fulfillment_location_id);

--
-- Name: order_inventory_location_item order_inventory_location_item_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_inventory_location_item
    ADD CONSTRAINT order_inventory_location_item_pkey PRIMARY KEY (workflow_id, fulfillment_location_id, seller_id, sku_id);

--
-- Name: order_inventory_reservation_workflow order_inventory_reservation_workflow_idempotency_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_inventory_reservation_workflow
    ADD CONSTRAINT order_inventory_reservation_workflow_idempotency_key_key UNIQUE (idempotency_key);

--
-- Name: order_inventory_reservation_workflow order_inventory_reservation_workflow_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_inventory_reservation_workflow
    ADD CONSTRAINT order_inventory_reservation_workflow_pkey PRIMARY KEY (workflow_id);

--
-- Name: order_inventory_restock_item order_inventory_restock_item_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_inventory_restock_item
    ADD CONSTRAINT order_inventory_restock_item_pkey PRIMARY KEY (restock_key, fulfillment_location_id, seller_id, sku_id);

--
-- Name: order_inventory_restock order_inventory_restock_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_inventory_restock
    ADD CONSTRAINT order_inventory_restock_pkey PRIMARY KEY (restock_key, fulfillment_location_id);

--
-- Name: order_item order_item_order_id_order_item_id_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_item
    ADD CONSTRAINT order_item_order_id_order_item_id_unique UNIQUE (order_id, order_item_id);

--
-- Name: order_item order_item_order_sku_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_item
    ADD CONSTRAINT order_item_order_sku_unique UNIQUE (order_id, seller_id, sku_id);

--
-- Name: order_item order_item_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_item
    ADD CONSTRAINT order_item_pk PRIMARY KEY (order_item_id);

--
-- Name: order_refund_request_item order_refund_request_item_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_refund_request_item
    ADD CONSTRAINT order_refund_request_item_pk PRIMARY KEY (refund_id, order_item_id);

--
-- Name: order_refund_request order_refund_request_order_key_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_refund_request
    ADD CONSTRAINT order_refund_request_order_key_unique UNIQUE (order_id, idempotency_key);

--
-- Name: order_refund_request order_refund_request_payment_refund_operation_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_refund_request
    ADD CONSTRAINT order_refund_request_payment_refund_operation_id_key UNIQUE (payment_refund_operation_id);

--
-- Name: order_refund_request order_refund_request_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_refund_request
    ADD CONSTRAINT order_refund_request_pkey PRIMARY KEY (refund_id);

--
-- Name: order_refund_summary order_refund_summary_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_refund_summary
    ADD CONSTRAINT order_refund_summary_pkey PRIMARY KEY (order_id);

--
-- Name: order_reorder_request order_reorder_request_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_reorder_request
    ADD CONSTRAINT order_reorder_request_pk PRIMARY KEY (seller_id, buyer_ref, idempotency_key);

--
-- Name: order_return_item order_return_item_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_return_item
    ADD CONSTRAINT order_return_item_pkey PRIMARY KEY (return_item_id);

--
-- Name: order_return_item order_return_item_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_return_item
    ADD CONSTRAINT order_return_item_unique UNIQUE (return_id, order_item_id);

--
-- Name: order_return order_return_order_key_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_return
    ADD CONSTRAINT order_return_order_key_unique UNIQUE (order_id, idempotency_key);

--
-- Name: order_return order_return_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_return
    ADD CONSTRAINT order_return_pkey PRIMARY KEY (return_id);

--
-- Name: order_shipment_item order_shipment_item_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_shipment_item
    ADD CONSTRAINT order_shipment_item_pk PRIMARY KEY (shipment_id, order_item_id);

--
-- Name: order_shipment order_shipment_order_id_idempotency_key_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_shipment
    ADD CONSTRAINT order_shipment_order_id_idempotency_key_unique UNIQUE (order_id, idempotency_key);

--
-- Name: order_shipment order_shipment_order_id_shipment_id_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_shipment
    ADD CONSTRAINT order_shipment_order_id_shipment_id_unique UNIQUE (order_id, shipment_id);

--
-- Name: order_shipment order_shipment_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_shipment
    ADD CONSTRAINT order_shipment_pkey PRIMARY KEY (shipment_id);

--
-- Name: payment_attempt payment_attempt_checkout_id_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY payment_attempt
    ADD CONSTRAINT payment_attempt_checkout_id_unique UNIQUE (checkout_id);

--
-- Name: payment_attempt payment_attempt_order_id_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY payment_attempt
    ADD CONSTRAINT payment_attempt_order_id_unique UNIQUE (order_id);

--
-- Name: payment_attempt payment_attempt_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY payment_attempt
    ADD CONSTRAINT payment_attempt_pk PRIMARY KEY (attempt_id);

--
-- Name: payment_provider_webhook_event payment_provider_webhook_event_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY payment_provider_webhook_event
    ADD CONSTRAINT payment_provider_webhook_event_pkey PRIMARY KEY (event_id);

--
-- Name: payment_recovery_job payment_recovery_job_attempt_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY payment_recovery_job
    ADD CONSTRAINT payment_recovery_job_attempt_unique UNIQUE (payment_attempt_id);

--
-- Name: payment_recovery_job payment_recovery_job_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY payment_recovery_job
    ADD CONSTRAINT payment_recovery_job_pkey PRIMARY KEY (recovery_job_id);

--
-- Name: payment_recovery_job payment_recovery_job_refund_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY payment_recovery_job
    ADD CONSTRAINT payment_recovery_job_refund_unique UNIQUE (payment_refund_operation_id);

--
-- Name: payment_recovery_operator_action payment_recovery_operator_action_key_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY payment_recovery_operator_action
    ADD CONSTRAINT payment_recovery_operator_action_key_unique UNIQUE (recovery_job_id, idempotency_key);

--
-- Name: payment_recovery_operator_action payment_recovery_operator_action_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY payment_recovery_operator_action
    ADD CONSTRAINT payment_recovery_operator_action_pkey PRIMARY KEY (operator_action_id);

--
-- Name: payment_refund_operation payment_refund_operation_attempt_key_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY payment_refund_operation
    ADD CONSTRAINT payment_refund_operation_attempt_key_unique UNIQUE (attempt_id, idempotency_key);

--
-- Name: payment_refund_operation payment_refund_operation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY payment_refund_operation
    ADD CONSTRAINT payment_refund_operation_pkey PRIMARY KEY (refund_operation_id);

--
-- Name: payment_refund_operation payment_refund_operation_provider_refund_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY payment_refund_operation
    ADD CONSTRAINT payment_refund_operation_provider_refund_id_key UNIQUE (provider_refund_id);

--
-- Name: payment_refund_operation payment_refund_operation_provider_request_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY payment_refund_operation
    ADD CONSTRAINT payment_refund_operation_provider_request_id_key UNIQUE (provider_request_id);

--
-- Name: paypal_balance_report_disposition paypal_balance_report_disposition_idempotency_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY paypal_balance_report_disposition
    ADD CONSTRAINT paypal_balance_report_disposition_idempotency_key_key UNIQUE (idempotency_key);

--
-- Name: paypal_balance_report_disposition paypal_balance_report_disposition_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY paypal_balance_report_disposition
    ADD CONSTRAINT paypal_balance_report_disposition_pkey PRIMARY KEY (disposition_id);

--
-- Name: paypal_balance_report_row paypal_balance_report_row_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY paypal_balance_report_row
    ADD CONSTRAINT paypal_balance_report_row_pkey PRIMARY KEY (run_id, row_number);

--
-- Name: paypal_balance_report_run paypal_balance_report_run_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY paypal_balance_report_run
    ADD CONSTRAINT paypal_balance_report_run_pkey PRIMARY KEY (run_id);

--
-- Name: paypal_balance_report_run paypal_balance_report_run_report_sha256_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY paypal_balance_report_run
    ADD CONSTRAINT paypal_balance_report_run_report_sha256_key UNIQUE (report_sha256);

--
-- Name: paypal_webhook_retry_attempt_history paypal_webhook_retry_attempt_history_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY paypal_webhook_retry_attempt_history
    ADD CONSTRAINT paypal_webhook_retry_attempt_history_pkey PRIMARY KEY (lease_token);

--
-- Name: paypal_webhook_retry_operator_audit paypal_webhook_retry_operator_audit_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY paypal_webhook_retry_operator_audit
    ADD CONSTRAINT paypal_webhook_retry_operator_audit_pkey PRIMARY KEY (idempotency_key);

--
-- Name: paypal_webhook_retry paypal_webhook_retry_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY paypal_webhook_retry
    ADD CONSTRAINT paypal_webhook_retry_pkey PRIMARY KEY (event_id);

--
-- Name: product_category_assignment product_category_assignment_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY product_category_assignment
    ADD CONSTRAINT product_category_assignment_pk PRIMARY KEY (seller_id, product_id, category_id);

--
-- Name: product_category product_category_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY product_category
    ADD CONSTRAINT product_category_pk PRIMARY KEY (category_id);

--
-- Name: product_category product_category_seller_category_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY product_category
    ADD CONSTRAINT product_category_seller_category_unique UNIQUE (seller_id, category_id);

--
-- Name: product_category product_category_seller_code_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY product_category
    ADD CONSTRAINT product_category_seller_code_unique UNIQUE (seller_id, code);

--
-- Name: product_channel_availability product_channel_availability_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY product_channel_availability
    ADD CONSTRAINT product_channel_availability_pk PRIMARY KEY (product_id, sales_channel);

--
-- Name: product_listing_projection product_listing_projection_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY product_listing_projection
    ADD CONSTRAINT product_listing_projection_pkey PRIMARY KEY (product_id);

--
-- Name: product product_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY product
    ADD CONSTRAINT product_pk PRIMARY KEY (product_id);

--
-- Name: product_review product_review_buyer_product_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY product_review
    ADD CONSTRAINT product_review_buyer_product_unique UNIQUE (buyer_ref, product_id);

--
-- Name: product_review product_review_order_item_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY product_review
    ADD CONSTRAINT product_review_order_item_id_key UNIQUE (order_item_id);

--
-- Name: product_review product_review_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY product_review
    ADD CONSTRAINT product_review_pkey PRIMARY KEY (review_id);

--
-- Name: product product_seller_identity_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY product
    ADD CONSTRAINT product_seller_identity_unique UNIQUE (product_id, seller_id);

--
-- Name: product product_seller_product_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY product
    ADD CONSTRAINT product_seller_product_unique UNIQUE (seller_id, product_id);

--
-- Name: purchase_order purchase_order_order_id_seller_id_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY purchase_order
    ADD CONSTRAINT purchase_order_order_id_seller_id_unique UNIQUE (order_id, seller_id);

--
-- Name: purchase_order purchase_order_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY purchase_order
    ADD CONSTRAINT purchase_order_pk PRIMARY KEY (order_id);

--
-- Name: purchase_order purchase_order_reservation_workflow_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY purchase_order
    ADD CONSTRAINT purchase_order_reservation_workflow_unique UNIQUE (reservation_workflow_id);

--
-- Name: purchase_order purchase_order_ticket_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY purchase_order
    ADD CONSTRAINT purchase_order_ticket_unique UNIQUE (ticket_id);

--
-- Name: refresh_token_family refresh_token_family_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY refresh_token_family
    ADD CONSTRAINT refresh_token_family_pkey PRIMARY KEY (family_id);

--
-- Name: seller_account seller_account_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY seller_account
    ADD CONSTRAINT seller_account_pkey PRIMARY KEY (account_id, seller_id);

--
-- Name: seller_account seller_account_seller_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY seller_account
    ADD CONSTRAINT seller_account_seller_id_key UNIQUE (seller_id);

--
-- Name: seller_coupon seller_coupon_owner_code_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY seller_coupon
    ADD CONSTRAINT seller_coupon_owner_code_unique UNIQUE (seller_id, code);

--
-- Name: seller_coupon seller_coupon_owner_id_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY seller_coupon
    ADD CONSTRAINT seller_coupon_owner_id_unique UNIQUE (seller_id, coupon_id);

--
-- Name: seller_coupon seller_coupon_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY seller_coupon
    ADD CONSTRAINT seller_coupon_pkey PRIMARY KEY (coupon_id);

--
-- Name: seller_coupon_redemption seller_coupon_redemption_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY seller_coupon_redemption
    ADD CONSTRAINT seller_coupon_redemption_pkey PRIMARY KEY (redemption_id);

--
-- Name: seller_coupon_redemption seller_coupon_redemption_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY seller_coupon_redemption
    ADD CONSTRAINT seller_coupon_redemption_unique UNIQUE (coupon_id, order_id);

--
-- Name: seller_payout_profile_action seller_payout_profile_action_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY seller_payout_profile_action
    ADD CONSTRAINT seller_payout_profile_action_pkey PRIMARY KEY (action_id);

--
-- Name: seller_payout_profile_action seller_payout_profile_action_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY seller_payout_profile_action
    ADD CONSTRAINT seller_payout_profile_action_unique UNIQUE (seller_id, idempotency_key);

--
-- Name: seller_payout_profile seller_payout_profile_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY seller_payout_profile
    ADD CONSTRAINT seller_payout_profile_pkey PRIMARY KEY (seller_id);

--
-- Name: seller seller_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY seller
    ADD CONSTRAINT seller_pk PRIMARY KEY (seller_id);

--
-- Name: seller_sku_admission_policy seller_sku_admission_policy_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY seller_sku_admission_policy
    ADD CONSTRAINT seller_sku_admission_policy_pk PRIMARY KEY (seller_id, sku_id);

--
-- Name: seller_sku seller_sku_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY seller_sku
    ADD CONSTRAINT seller_sku_pk PRIMARY KEY (seller_id, sku_id);

--
-- Name: seller_sku seller_sku_product_variant_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY seller_sku
    ADD CONSTRAINT seller_sku_product_variant_unique UNIQUE (product_id, seller_id, sku_id);

--
-- Name: settlement_entry settlement_entry_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY settlement_entry
    ADD CONSTRAINT settlement_entry_pkey PRIMARY KEY (entry_id);

--
-- Name: settlement_journal settlement_journal_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY settlement_journal
    ADD CONSTRAINT settlement_journal_pkey PRIMARY KEY (journal_id);

--
-- Name: settlement_journal settlement_journal_source_attempt_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY settlement_journal
    ADD CONSTRAINT settlement_journal_source_attempt_id_key UNIQUE (source_attempt_id);

--
-- Name: settlement_journal settlement_journal_source_refund_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY settlement_journal
    ADD CONSTRAINT settlement_journal_source_refund_id_key UNIQUE (source_refund_id);

--
-- Name: settlement_payout_dispatch_outbox settlement_payout_dispatch_outbox_paypal_request_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY settlement_payout_dispatch_outbox
    ADD CONSTRAINT settlement_payout_dispatch_outbox_paypal_request_id_key UNIQUE (paypal_request_id);

--
-- Name: settlement_payout_dispatch_outbox settlement_payout_dispatch_outbox_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY settlement_payout_dispatch_outbox
    ADD CONSTRAINT settlement_payout_dispatch_outbox_pkey PRIMARY KEY (payout_id);

--
-- Name: settlement_payout_dispatch_outbox settlement_payout_dispatch_outbox_sender_batch_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY settlement_payout_dispatch_outbox
    ADD CONSTRAINT settlement_payout_dispatch_outbox_sender_batch_id_key UNIQUE (sender_batch_id);

--
-- Name: settlement_payout_dispatch_outbox settlement_payout_dispatch_outbox_sender_item_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY settlement_payout_dispatch_outbox
    ADD CONSTRAINT settlement_payout_dispatch_outbox_sender_item_id_key UNIQUE (sender_item_id);

--
-- Name: settlement_payout_dispatch_outbox settlement_payout_dispatch_provider_batch_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY settlement_payout_dispatch_outbox
    ADD CONSTRAINT settlement_payout_dispatch_provider_batch_unique UNIQUE (provider_batch_id);

--
-- Name: settlement_payout_dispatch_outbox settlement_payout_dispatch_provider_item_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY settlement_payout_dispatch_outbox
    ADD CONSTRAINT settlement_payout_dispatch_provider_item_unique UNIQUE (provider_item_id);

--
-- Name: settlement_payout settlement_payout_key_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY settlement_payout
    ADD CONSTRAINT settlement_payout_key_unique UNIQUE (idempotency_key);

--
-- Name: settlement_payout_operator_action settlement_payout_operator_action_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY settlement_payout_operator_action
    ADD CONSTRAINT settlement_payout_operator_action_pkey PRIMARY KEY (action_id);

--
-- Name: settlement_payout_operator_action settlement_payout_operator_action_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY settlement_payout_operator_action
    ADD CONSTRAINT settlement_payout_operator_action_unique UNIQUE (payout_id, idempotency_key);

--
-- Name: settlement_payout settlement_payout_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY settlement_payout
    ADD CONSTRAINT settlement_payout_pkey PRIMARY KEY (payout_id);

--
-- Name: settlement_refund_reservation settlement_refund_reservation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY settlement_refund_reservation
    ADD CONSTRAINT settlement_refund_reservation_pkey PRIMARY KEY (refund_id);

--
-- Name: settlement_seller_allocation settlement_seller_allocation_ordinal_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY settlement_seller_allocation
    ADD CONSTRAINT settlement_seller_allocation_ordinal_unique UNIQUE (journal_id, allocation_ordinal);

--
-- Name: settlement_seller_allocation settlement_seller_allocation_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY settlement_seller_allocation
    ADD CONSTRAINT settlement_seller_allocation_pk PRIMARY KEY (journal_id, order_id);

--
-- Name: settlement_seller_allocation settlement_seller_allocation_seller_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY settlement_seller_allocation
    ADD CONSTRAINT settlement_seller_allocation_seller_unique UNIQUE (journal_id, seller_id);

--
-- Name: shopping_cart shopping_cart_buyer_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY shopping_cart
    ADD CONSTRAINT shopping_cart_buyer_unique UNIQUE (buyer_ref);

--
-- Name: shopping_cart_item shopping_cart_item_cart_seller_sku_unique; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY shopping_cart_item
    ADD CONSTRAINT shopping_cart_item_cart_seller_sku_unique UNIQUE (cart_id, seller_id, sku_id);

--
-- Name: shopping_cart_item shopping_cart_item_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY shopping_cart_item
    ADD CONSTRAINT shopping_cart_item_pk PRIMARY KEY (cart_item_id);

--
-- Name: shopping_cart shopping_cart_pk; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY shopping_cart
    ADD CONSTRAINT shopping_cart_pk PRIMARY KEY (cart_id);

--
-- Name: user_account user_account_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY user_account
    ADD CONSTRAINT user_account_pkey PRIMARY KEY (account_id);

--
-- Name: admission_ticket_expiry_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX admission_ticket_expiry_idx ON admission_ticket USING btree (expires_at, ticket_id) WHERE ((status)::text = ANY ((ARRAY['WAITING'::character varying, 'CHECKOUT_ACTIVE'::character varying])::text[]));

--
-- Name: admission_ticket_fifo_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX admission_ticket_fifo_idx ON admission_ticket USING btree (seller_id, sku_id, sequence) WHERE ((status)::text = 'WAITING'::text);

--
-- Name: buyer_checkout_cancel_pending_age_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX buyer_checkout_cancel_pending_age_idx ON buyer_checkout USING btree (cancellation_requested_at, checkout_id) WHERE (((state)::text = 'CANCEL_PENDING'::text) AND (cancellation_requested_at IS NOT NULL));

--
-- Name: buyer_checkout_recovery_state_created_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX buyer_checkout_recovery_state_created_idx ON buyer_checkout USING btree (state, created_at, checkout_id);

--
-- Name: idx_buyer_wishlist_item_saved_order; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_buyer_wishlist_item_saved_order ON buyer_wishlist_item USING btree (buyer_ref, saved_at DESC, product_id DESC);

--
-- Name: idx_order_return_item_refund_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_order_return_item_refund_id ON order_return_item USING btree (refund_id) WHERE (refund_id IS NOT NULL);

--
-- Name: idx_order_return_item_shipment; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_order_return_item_shipment ON order_return_item USING btree (shipment_id);

--
-- Name: idx_paypal_balance_report_disposition_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_paypal_balance_report_disposition_created_at ON paypal_balance_report_disposition USING btree (created_at DESC, disposition_id);

--
-- Name: idx_paypal_balance_report_disposition_row_history; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_paypal_balance_report_disposition_row_history ON paypal_balance_report_disposition USING btree (run_id, row_number, created_at, disposition_id);

--
-- Name: idx_paypal_balance_report_row_record_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_paypal_balance_report_row_record_id ON paypal_balance_report_row USING btree (record_id, run_id);

--
-- Name: idx_paypal_balance_report_row_run_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_paypal_balance_report_row_run_status ON paypal_balance_report_row USING btree (run_id, status, row_number);

--
-- Name: idx_paypal_balance_report_run_uploaded_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_paypal_balance_report_run_uploaded_at ON paypal_balance_report_run USING btree (uploaded_at DESC, run_id);

--
-- Name: idx_product_review_product_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_product_review_product_created ON product_review USING btree (product_id, created_at DESC, review_id DESC);

--
-- Name: inventory_reservation_location_pool_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX inventory_reservation_location_pool_idx ON inventory_reservation USING btree (seller_id, location_id, sku_id);

--
-- Name: inventory_reservation_order_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX inventory_reservation_order_idx ON inventory_reservation USING btree (order_id, seller_id, sku_id);

--
-- Name: inventory_reservation_pool_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX inventory_reservation_pool_idx ON inventory_reservation USING btree (seller_id, sku_id);

--
-- Name: ix_account_action_token_account; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_account_action_token_account ON account_action_token USING btree (account_id, purpose, expires_at);

--
-- Name: ix_account_mfa_challenge_expiry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_account_mfa_challenge_expiry ON account_mfa_challenge USING btree (expires_at);

--
-- Name: ix_account_refresh_token_family; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_account_refresh_token_family ON account_refresh_token USING btree (family_id, expires_at);

--
-- Name: ix_account_suspension_action_history; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_account_suspension_action_history ON account_suspension_action USING btree (account_id, created_at DESC);

--
-- Name: ix_auth_origin_bucket_stale; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_auth_origin_bucket_stale ON auth_origin_bucket USING btree (updated_at);

--
-- Name: ix_auth_subject_bucket_stale; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_auth_subject_bucket_stale ON auth_subject_bucket USING btree (updated_at);

--
-- Name: ix_order_refund_request_payment_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_order_refund_request_payment_status ON order_refund_request USING btree (payment_refund_status, order_id);

--
-- Name: ix_order_return_seller_history; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_order_return_seller_history ON order_return USING btree (seller_id, requested_at DESC, return_id DESC);

--
-- Name: ix_payment_attempt_approval_expiry; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_payment_attempt_approval_expiry ON payment_attempt USING btree (approval_expires_at, attempt_id) WHERE (((state)::text = 'AUTHORIZATION_PENDING'::text) AND (approval_expires_at IS NOT NULL));

--
-- Name: ix_payment_provider_webhook_event_resource; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_payment_provider_webhook_event_resource ON payment_provider_webhook_event USING btree (resource_id, received_at);

--
-- Name: ix_payment_recovery_job_due; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_payment_recovery_job_due ON payment_recovery_job USING btree (available_at, first_enqueued_at, recovery_job_id) WHERE ((status)::text = 'PENDING'::text);

--
-- Name: ix_payment_recovery_job_needs_review; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_payment_recovery_job_needs_review ON payment_recovery_job USING btree (first_enqueued_at DESC, recovery_job_id DESC) WHERE ((status)::text = 'NEEDS_REVIEW'::text);

--
-- Name: ix_payment_recovery_operator_action_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_payment_recovery_operator_action_created ON payment_recovery_operator_action USING btree (recovery_job_id, created_at DESC);

--
-- Name: ix_payment_refund_operation_capture; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_payment_refund_operation_capture ON payment_refund_operation USING btree (capture_id, created_at);

--
-- Name: ix_product_listing_projection_active; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_product_listing_projection_active ON product_listing_projection USING btree (seller_id, product_id) WHERE ((status)::text = 'ACTIVE'::text);

--
-- Name: ix_product_primary_category_listing; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_product_primary_category_listing ON product USING btree (primary_category_id, encode(set_byte(set_byte(uuid_send(product_id), 0, (get_byte(uuid_send(product_id), 0) # 128)), 8, (get_byte(uuid_send(product_id), 8) # 128)), 'hex'::text) COLLATE "C") WHERE ((status)::text = 'ACTIVE'::text);

--
-- Name: ix_refresh_family_account; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_refresh_family_account ON refresh_token_family USING btree (account_id, expires_at);

--
-- Name: ix_refresh_family_active_account_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_refresh_family_active_account_created ON refresh_token_family USING btree (account_id, created_at DESC, family_id DESC) INCLUDE (expires_at) WHERE (revoked_at IS NULL);

--
-- Name: ix_seller_payout_profile_action_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_seller_payout_profile_action_created ON seller_payout_profile_action USING btree (seller_id, created_at DESC, action_id DESC);

--
-- Name: ix_settlement_payout_dispatch_due; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_settlement_payout_dispatch_due ON settlement_payout_dispatch_outbox USING btree (available_at, first_enqueued_at, payout_id) WHERE ((status)::text = ANY ((ARRAY['PENDING'::character varying, 'WAITING_PROVIDER'::character varying])::text[]));

--
-- Name: ix_settlement_payout_dispatch_needs_review; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_settlement_payout_dispatch_needs_review ON settlement_payout_dispatch_outbox USING btree (first_enqueued_at, payout_id) WHERE ((status)::text = 'NEEDS_REVIEW'::text);

--
-- Name: ix_settlement_payout_operator_action_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_settlement_payout_operator_action_created ON settlement_payout_operator_action USING btree (payout_id, created_at DESC, action_id DESC);

--
-- Name: ix_settlement_payout_seller_state; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_settlement_payout_seller_state ON settlement_payout USING btree (seller_id, state, period_start DESC);

--
-- Name: ix_settlement_refund_reservation_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_settlement_refund_reservation_pending ON settlement_refund_reservation USING btree (seller_id, created_at, refund_id) WHERE ((status)::text = 'RESERVED'::text);

--
-- Name: order_inventory_event_tx_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX order_inventory_event_tx_idx ON order_inventory_event USING btree (tx_id, event_seq);

--
-- Name: order_inventory_reservation_workflow_due_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX order_inventory_reservation_workflow_due_idx ON order_inventory_reservation_workflow USING btree (next_attempt_at, workflow_id) WHERE ((workflow_state)::text = ANY ((ARRAY['PENDING'::character varying, 'RETRY_PENDING'::character varying, 'COMPENSATING'::character varying, 'COMPENSATION_REQUIRED'::character varying, 'CANCEL_PENDING'::character varying])::text[]));

--
-- Name: order_inventory_restock_due_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX order_inventory_restock_due_idx ON order_inventory_restock USING btree (next_attempt_at, restock_key) WHERE ((restock_state)::text = 'PENDING'::text);

--
-- Name: order_refund_request_order_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX order_refund_request_order_id_idx ON order_refund_request USING btree (order_id);

--
-- Name: order_return_item_order_item_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX order_return_item_order_item_idx ON order_return_item USING btree (order_item_id);

--
-- Name: order_return_order_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX order_return_order_idx ON order_return USING btree (order_id);

--
-- Name: order_shipment_item_order_item_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX order_shipment_item_order_item_idx ON order_shipment_item USING btree (order_id, order_item_id);

--
-- Name: order_shipment_order_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX order_shipment_order_idx ON order_shipment USING btree (order_id);

--
-- Name: paypal_webhook_retry_attempt_history_claimed_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX paypal_webhook_retry_attempt_history_claimed_idx ON paypal_webhook_retry_attempt_history USING btree (event_id, claimed_at DESC);

--
-- Name: paypal_webhook_retry_due_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX paypal_webhook_retry_due_idx ON paypal_webhook_retry USING btree (next_attempt_at, created_at) WHERE (((state)::text = 'PENDING'::text) AND (attempt_count < 8));

--
-- Name: paypal_webhook_retry_exhausted_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX paypal_webhook_retry_exhausted_idx ON paypal_webhook_retry USING btree (finished_at) WHERE ((state)::text = 'EXHAUSTED'::text);

--
-- Name: paypal_webhook_retry_history_recorded_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX paypal_webhook_retry_history_recorded_idx ON paypal_webhook_retry_attempt_history USING btree (recorded_at, outcome);

--
-- Name: paypal_webhook_retry_operator_audit_event_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX paypal_webhook_retry_operator_audit_event_idx ON paypal_webhook_retry_operator_audit USING btree (event_id, created_at);

--
-- Name: paypal_webhook_retry_reaper_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX paypal_webhook_retry_reaper_idx ON paypal_webhook_retry USING btree (lease_until, event_id) WHERE (((state)::text = 'PENDING'::text) AND (attempt_count = 8));

--
-- Name: product_category_assignment_category_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX product_category_assignment_category_idx ON product_category_assignment USING btree (seller_id, category_id, product_id);

--
-- Name: product_listing_sort_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX product_listing_sort_idx ON product USING btree (encode(set_byte(set_byte(uuid_send(seller_id), 0, (get_byte(uuid_send(seller_id), 0) # 128)), 8, (get_byte(uuid_send(seller_id), 8) # 128)), 'hex'::text) COLLATE "C", encode(set_byte(set_byte(uuid_send(product_id), 0, (get_byte(uuid_send(product_id), 0) # 128)), 8, (get_byte(uuid_send(product_id), 8) # 128)), 'hex'::text) COLLATE "C");

--
-- Name: product_name_trgm_idx; Type: INDEX; Schema: public; Owner: -
--

DO $$
DECLARE
    trgm_schema text;
BEGIN
    SELECT n.nspname INTO trgm_schema
    FROM pg_extension e
    JOIN pg_namespace n ON n.oid = e.extnamespace
    WHERE e.extname = 'pg_trgm';
    IF trgm_schema IS NULL THEN
        RAISE EXCEPTION 'pg_trgm extension is required for catalog keyword search';
    END IF;
    EXECUTE format(
        'CREATE INDEX product_name_trgm_idx ON product USING gin (lower(name) %I.gin_trgm_ops)',
        trgm_schema);
END
$$;

--
-- Name: product_seller_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX product_seller_idx ON product USING btree (seller_id, product_id);

--
-- Name: product_seller_primary_category_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX product_seller_primary_category_idx ON product USING btree (seller_id, primary_category_id, product_id);

--
-- Name: purchase_order_buyer_history_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX purchase_order_buyer_history_idx ON purchase_order USING btree (buyer_ref, created_at DESC, order_id DESC);

--
-- Name: purchase_order_buyer_unresolved_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX purchase_order_buyer_unresolved_idx ON purchase_order USING btree (campaign_id, buyer_ref) WHERE ((state)::text = ANY ((ARRAY['PAYMENT_SUBMITTED'::character varying, 'PAYMENT_PENDING'::character varying, 'PAYMENT_UNKNOWN'::character varying, 'PAYMENT_VOID_PENDING'::character varying, 'CANCEL_PENDING'::character varying])::text[]));

--
-- Name: purchase_order_checkout_key_unique; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX purchase_order_checkout_key_unique ON purchase_order USING btree (seller_id, buyer_ref, checkout_key) WHERE (checkout_key IS NOT NULL);

--
-- Name: purchase_order_checkout_seller_unique; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX purchase_order_checkout_seller_unique ON purchase_order USING btree (checkout_id, seller_id) WHERE (checkout_id IS NOT NULL);

--
-- Name: purchase_order_expiry_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX purchase_order_expiry_idx ON purchase_order USING btree (checkout_expires_at) WHERE ((state)::text = 'RESERVED'::text);

--
-- Name: purchase_order_seller_buyer_unresolved_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX purchase_order_seller_buyer_unresolved_idx ON purchase_order USING btree (seller_id, buyer_ref) WHERE ((state)::text = ANY ((ARRAY['PAYMENT_SUBMITTED'::character varying, 'PAYMENT_PENDING'::character varying, 'PAYMENT_UNKNOWN'::character varying, 'PAYMENT_VOID_PENDING'::character varying, 'CANCEL_PENDING'::character varying])::text[]));

--
-- Name: purchase_order_seller_history_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX purchase_order_seller_history_idx ON purchase_order USING btree (seller_id, created_at DESC, order_id DESC);

--
-- Name: seller_sku_product_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX seller_sku_product_idx ON seller_sku USING btree (product_id, seller_id);

--
-- Name: settlement_entry_account_seller_once; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX settlement_entry_account_seller_once ON settlement_entry USING btree (journal_id, account_code, COALESCE(seller_id, '00000000-0000-0000-0000-000000000000'::uuid));

--
-- Name: settlement_entry_seller_account_side_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX settlement_entry_seller_account_side_idx ON settlement_entry USING btree (seller_id, account_code, entry_side);

--
-- Name: settlement_journal_checkout_unique; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX settlement_journal_checkout_unique ON settlement_journal USING btree (checkout_id) WHERE (checkout_id IS NOT NULL);

--
-- Name: settlement_journal_payout_unique; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX settlement_journal_payout_unique ON settlement_journal USING btree (source_payout_id) WHERE (source_payout_id IS NOT NULL);

--
-- Name: settlement_journal_seller_recorded_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX settlement_journal_seller_recorded_idx ON settlement_journal USING btree (seller_id, recorded_at, journal_id);

--
-- Name: settlement_payout_seller_period_active_unique; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX settlement_payout_seller_period_active_unique ON settlement_payout USING btree (seller_id, period_start, period_end) WHERE ((state)::text <> ALL ((ARRAY['FAILED'::character varying, 'CANCELLED'::character varying])::text[]));

--
-- Name: settlement_seller_allocation_seller_journal_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX settlement_seller_allocation_seller_journal_idx ON settlement_seller_allocation USING btree (seller_id, journal_id);

--
-- Name: uk_account_action_token_active; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_account_action_token_active ON account_action_token USING btree (account_id, purpose) WHERE (consumed_at IS NULL);

--
-- Name: uk_user_account_email_ci; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_user_account_email_ci ON user_account USING btree (lower((email)::text));

--
-- Name: ux_payment_attempt_provider_authorization_id; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_payment_attempt_provider_authorization_id ON payment_attempt USING btree (provider_authorization_id) WHERE (provider_authorization_id IS NOT NULL);

--
-- Name: ux_payment_attempt_provider_capture_id; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_payment_attempt_provider_capture_id ON payment_attempt USING btree (provider_capture_id) WHERE (provider_capture_id IS NOT NULL);

--
-- Name: ux_payment_attempt_provider_order_id; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ux_payment_attempt_provider_order_id ON payment_attempt USING btree (provider_order_id) WHERE (provider_order_id IS NOT NULL);

--
-- Name: inventory_location inventory_location_default_undeletable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER inventory_location_default_undeletable BEFORE DELETE ON inventory_location FOR EACH ROW EXECUTE FUNCTION protect_default_inventory_location();

--
-- Name: inventory_transfer inventory_transfer_append_only; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER inventory_transfer_append_only BEFORE DELETE OR UPDATE ON inventory_transfer FOR EACH ROW EXECUTE FUNCTION reject_inventory_transfer_mutation();

--
-- Name: order_inventory_reservation_workflow order_inventory_reservation_workflow_event; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER order_inventory_reservation_workflow_event AFTER INSERT OR UPDATE OF workflow_state ON order_inventory_reservation_workflow FOR EACH ROW EXECUTE FUNCTION order_inventory_record_workflow_event();

--
-- Name: paypal_balance_report_disposition paypal_balance_report_disposition_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER paypal_balance_report_disposition_immutable BEFORE DELETE OR UPDATE ON paypal_balance_report_disposition FOR EACH ROW EXECUTE FUNCTION reject_paypal_balance_report_mutation();

--
-- Name: paypal_balance_report_disposition paypal_balance_report_disposition_no_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER paypal_balance_report_disposition_no_truncate BEFORE TRUNCATE ON paypal_balance_report_disposition FOR EACH STATEMENT EXECUTE FUNCTION reject_paypal_balance_report_mutation();

--
-- Name: paypal_balance_report_row paypal_balance_report_row_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER paypal_balance_report_row_immutable BEFORE DELETE OR UPDATE ON paypal_balance_report_row FOR EACH ROW EXECUTE FUNCTION reject_paypal_balance_report_mutation();

--
-- Name: paypal_balance_report_row paypal_balance_report_row_no_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER paypal_balance_report_row_no_truncate BEFORE TRUNCATE ON paypal_balance_report_row FOR EACH STATEMENT EXECUTE FUNCTION reject_paypal_balance_report_mutation();

--
-- Name: paypal_balance_report_run paypal_balance_report_run_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER paypal_balance_report_run_immutable BEFORE DELETE OR UPDATE ON paypal_balance_report_run FOR EACH ROW EXECUTE FUNCTION reject_paypal_balance_report_mutation();

--
-- Name: paypal_balance_report_run paypal_balance_report_run_no_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER paypal_balance_report_run_no_truncate BEFORE TRUNCATE ON paypal_balance_report_run FOR EACH STATEMENT EXECUTE FUNCTION reject_paypal_balance_report_mutation();

--
-- Name: paypal_webhook_retry_attempt_history paypal_webhook_retry_attempt_history_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER paypal_webhook_retry_attempt_history_guard BEFORE DELETE OR UPDATE ON paypal_webhook_retry_attempt_history FOR EACH ROW EXECUTE FUNCTION guard_paypal_webhook_retry_attempt_history();

--
-- Name: paypal_webhook_retry_attempt_history paypal_webhook_retry_attempt_history_no_truncate; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER paypal_webhook_retry_attempt_history_no_truncate BEFORE TRUNCATE ON paypal_webhook_retry_attempt_history FOR EACH STATEMENT EXECUTE FUNCTION guard_paypal_webhook_retry_attempt_history();

--
-- Name: paypal_webhook_retry_operator_audit paypal_webhook_retry_operator_audit_immutable; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER paypal_webhook_retry_operator_audit_immutable BEFORE DELETE OR UPDATE ON paypal_webhook_retry_operator_audit FOR EACH ROW EXECUTE FUNCTION reject_paypal_webhook_retry_audit_mutation();

--
-- Name: account_action_token account_action_token_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY account_action_token
    ADD CONSTRAINT account_action_token_account_id_fkey FOREIGN KEY (account_id) REFERENCES user_account(account_id) ON DELETE CASCADE;

--
-- Name: account_mfa_challenge account_mfa_challenge_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY account_mfa_challenge
    ADD CONSTRAINT account_mfa_challenge_account_id_fkey FOREIGN KEY (account_id) REFERENCES user_account(account_id) ON DELETE CASCADE;

--
-- Name: account_mfa_recovery_code account_mfa_recovery_code_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY account_mfa_recovery_code
    ADD CONSTRAINT account_mfa_recovery_code_account_id_fkey FOREIGN KEY (account_id) REFERENCES user_account(account_id) ON DELETE CASCADE;

--
-- Name: account_mfa_secret account_mfa_secret_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY account_mfa_secret
    ADD CONSTRAINT account_mfa_secret_account_id_fkey FOREIGN KEY (account_id) REFERENCES user_account(account_id) ON DELETE CASCADE;

--
-- Name: account_refresh_token account_refresh_token_family_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY account_refresh_token
    ADD CONSTRAINT account_refresh_token_family_id_fkey FOREIGN KEY (family_id) REFERENCES refresh_token_family(family_id) ON DELETE CASCADE;

--
-- Name: account_suspension_action account_suspension_action_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY account_suspension_action
    ADD CONSTRAINT account_suspension_action_account_id_fkey FOREIGN KEY (account_id) REFERENCES user_account(account_id);

--
-- Name: admission_counter admission_counter_seller_sku_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY admission_counter
    ADD CONSTRAINT admission_counter_seller_sku_fk FOREIGN KEY (seller_id, sku_id) REFERENCES seller_sku(seller_id, sku_id);

--
-- Name: admission_ticket admission_ticket_campaign_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY admission_ticket
    ADD CONSTRAINT admission_ticket_campaign_fk FOREIGN KEY (campaign_id) REFERENCES campaign(campaign_id);

--
-- Name: admission_ticket admission_ticket_seller_sku_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY admission_ticket
    ADD CONSTRAINT admission_ticket_seller_sku_fk FOREIGN KEY (seller_id, sku_id) REFERENCES seller_sku(seller_id, sku_id);

--
-- Name: buyer_account buyer_account_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY buyer_account
    ADD CONSTRAINT buyer_account_account_id_fkey FOREIGN KEY (account_id) REFERENCES user_account(account_id) ON DELETE CASCADE;

--
-- Name: buyer_account buyer_account_buyer_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY buyer_account
    ADD CONSTRAINT buyer_account_buyer_id_fkey FOREIGN KEY (buyer_id) REFERENCES buyer(buyer_id) ON DELETE CASCADE;

--
-- Name: buyer_wishlist_item buyer_wishlist_item_product_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY buyer_wishlist_item
    ADD CONSTRAINT buyer_wishlist_item_product_id_fkey FOREIGN KEY (product_id) REFERENCES product(product_id) ON DELETE CASCADE;

--
-- Name: campaign campaign_seller_sku_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY campaign
    ADD CONSTRAINT campaign_seller_sku_fk FOREIGN KEY (seller_id, sku_id) REFERENCES seller_sku(seller_id, sku_id);

--
-- Name: inventory_capacity_adjustment inventory_capacity_adjustment_pool_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY inventory_capacity_adjustment
    ADD CONSTRAINT inventory_capacity_adjustment_pool_fk FOREIGN KEY (seller_id, location_id, sku_id) REFERENCES inventory_pool(seller_id, location_id, sku_id);

--
-- Name: inventory_location inventory_location_seller_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY inventory_location
    ADD CONSTRAINT inventory_location_seller_id_fkey FOREIGN KEY (seller_id) REFERENCES seller(seller_id);

--
-- Name: inventory_pool inventory_pool_location_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY inventory_pool
    ADD CONSTRAINT inventory_pool_location_fk FOREIGN KEY (seller_id, location_id) REFERENCES inventory_location(seller_id, location_id);

--
-- Name: inventory_pool inventory_pool_seller_sku_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY inventory_pool
    ADD CONSTRAINT inventory_pool_seller_sku_fk FOREIGN KEY (seller_id, sku_id) REFERENCES seller_sku(seller_id, sku_id);

--
-- Name: inventory_reservation inventory_reservation_pool_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY inventory_reservation
    ADD CONSTRAINT inventory_reservation_pool_fk FOREIGN KEY (seller_id, location_id, sku_id) REFERENCES inventory_pool(seller_id, location_id, sku_id);

--
-- Name: inventory_transfer inventory_transfer_destination_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY inventory_transfer
    ADD CONSTRAINT inventory_transfer_destination_fk FOREIGN KEY (seller_id, destination_location_id, sku_id) REFERENCES inventory_pool(seller_id, location_id, sku_id);

--
-- Name: inventory_transfer inventory_transfer_source_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY inventory_transfer
    ADD CONSTRAINT inventory_transfer_source_fk FOREIGN KEY (seller_id, source_location_id, sku_id) REFERENCES inventory_pool(seller_id, location_id, sku_id);

--
-- Name: order_fulfillment order_fulfillment_order_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_fulfillment
    ADD CONSTRAINT order_fulfillment_order_fk FOREIGN KEY (order_id) REFERENCES purchase_order(order_id);

--
-- Name: order_inventory_location_command order_inventory_location_command_workflow_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_inventory_location_command
    ADD CONSTRAINT order_inventory_location_command_workflow_id_fkey FOREIGN KEY (workflow_id) REFERENCES order_inventory_reservation_workflow(workflow_id) ON DELETE CASCADE;

--
-- Name: order_inventory_location_item order_inventory_location_item_command_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_inventory_location_item
    ADD CONSTRAINT order_inventory_location_item_command_fk FOREIGN KEY (workflow_id, fulfillment_location_id) REFERENCES order_inventory_location_command(workflow_id, fulfillment_location_id) ON DELETE CASCADE;

--
-- Name: order_inventory_restock_item order_inventory_restock_item_parent; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_inventory_restock_item
    ADD CONSTRAINT order_inventory_restock_item_parent FOREIGN KEY (restock_key, fulfillment_location_id) REFERENCES order_inventory_restock(restock_key, fulfillment_location_id) ON DELETE CASCADE;

--
-- Name: order_inventory_restock order_inventory_restock_workflow_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_inventory_restock
    ADD CONSTRAINT order_inventory_restock_workflow_id_fkey FOREIGN KEY (workflow_id) REFERENCES order_inventory_reservation_workflow(workflow_id);

--
-- Name: order_item order_item_order_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_item
    ADD CONSTRAINT order_item_order_fk FOREIGN KEY (order_id) REFERENCES purchase_order(order_id);

--
-- Name: order_item order_item_order_seller_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_item
    ADD CONSTRAINT order_item_order_seller_fk FOREIGN KEY (order_id, seller_id) REFERENCES purchase_order(order_id, seller_id);

--
-- Name: order_item order_item_product_sku_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_item
    ADD CONSTRAINT order_item_product_sku_fk FOREIGN KEY (product_id, seller_id, sku_id) REFERENCES seller_sku(product_id, seller_id, sku_id);

--
-- Name: order_refund_request_item order_refund_request_item_order_item_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_refund_request_item
    ADD CONSTRAINT order_refund_request_item_order_item_id_fkey FOREIGN KEY (order_item_id) REFERENCES order_item(order_item_id);

--
-- Name: order_refund_request_item order_refund_request_item_refund_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_refund_request_item
    ADD CONSTRAINT order_refund_request_item_refund_id_fkey FOREIGN KEY (refund_id) REFERENCES order_refund_request(refund_id);

--
-- Name: order_refund_request order_refund_request_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_refund_request
    ADD CONSTRAINT order_refund_request_order_id_fkey FOREIGN KEY (order_id) REFERENCES purchase_order(order_id);

--
-- Name: order_refund_request order_refund_request_payment_refund_operation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_refund_request
    ADD CONSTRAINT order_refund_request_payment_refund_operation_id_fkey FOREIGN KEY (payment_refund_operation_id) REFERENCES payment_refund_operation(refund_operation_id);

--
-- Name: order_refund_summary order_refund_summary_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_refund_summary
    ADD CONSTRAINT order_refund_summary_order_id_fkey FOREIGN KEY (order_id) REFERENCES purchase_order(order_id);

--
-- Name: order_reorder_request order_reorder_request_seller_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_reorder_request
    ADD CONSTRAINT order_reorder_request_seller_fk FOREIGN KEY (seller_id) REFERENCES seller(seller_id);

--
-- Name: order_reorder_request order_reorder_request_source_order_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_reorder_request
    ADD CONSTRAINT order_reorder_request_source_order_fk FOREIGN KEY (source_order_id) REFERENCES purchase_order(order_id);

--
-- Name: order_return_item order_return_item_order_item_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_return_item
    ADD CONSTRAINT order_return_item_order_item_id_fkey FOREIGN KEY (order_item_id) REFERENCES order_item(order_item_id);

--
-- Name: order_return_item order_return_item_refund_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_return_item
    ADD CONSTRAINT order_return_item_refund_id_fkey FOREIGN KEY (refund_id) REFERENCES order_refund_request(refund_id);

--
-- Name: order_return_item order_return_item_return_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_return_item
    ADD CONSTRAINT order_return_item_return_id_fkey FOREIGN KEY (return_id) REFERENCES order_return(return_id) ON DELETE CASCADE;

--
-- Name: order_return_item order_return_item_shipment_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_return_item
    ADD CONSTRAINT order_return_item_shipment_fk FOREIGN KEY (shipment_id) REFERENCES order_shipment(shipment_id);

--
-- Name: order_return order_return_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_return
    ADD CONSTRAINT order_return_order_id_fkey FOREIGN KEY (order_id) REFERENCES purchase_order(order_id);

--
-- Name: order_shipment_item order_shipment_item_order_item_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_shipment_item
    ADD CONSTRAINT order_shipment_item_order_item_fk FOREIGN KEY (order_id, order_item_id) REFERENCES order_item(order_id, order_item_id);

--
-- Name: order_shipment_item order_shipment_item_shipment_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_shipment_item
    ADD CONSTRAINT order_shipment_item_shipment_fk FOREIGN KEY (order_id, shipment_id) REFERENCES order_shipment(order_id, shipment_id) ON DELETE CASCADE;

--
-- Name: order_shipment order_shipment_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY order_shipment
    ADD CONSTRAINT order_shipment_order_id_fkey FOREIGN KEY (order_id) REFERENCES purchase_order(order_id) ON DELETE CASCADE;

--
-- Name: payment_attempt payment_attempt_checkout_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY payment_attempt
    ADD CONSTRAINT payment_attempt_checkout_id_fkey FOREIGN KEY (checkout_id) REFERENCES buyer_checkout(checkout_id);

--
-- Name: payment_recovery_job payment_recovery_job_payment_attempt_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY payment_recovery_job
    ADD CONSTRAINT payment_recovery_job_payment_attempt_id_fkey FOREIGN KEY (payment_attempt_id) REFERENCES payment_attempt(attempt_id);

--
-- Name: payment_recovery_job payment_recovery_job_payment_refund_operation_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY payment_recovery_job
    ADD CONSTRAINT payment_recovery_job_payment_refund_operation_id_fkey FOREIGN KEY (payment_refund_operation_id) REFERENCES payment_refund_operation(refund_operation_id);

--
-- Name: payment_recovery_operator_action payment_recovery_operator_action_recovery_job_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY payment_recovery_operator_action
    ADD CONSTRAINT payment_recovery_operator_action_recovery_job_id_fkey FOREIGN KEY (recovery_job_id) REFERENCES payment_recovery_job(recovery_job_id);

--
-- Name: payment_refund_operation payment_refund_operation_attempt_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY payment_refund_operation
    ADD CONSTRAINT payment_refund_operation_attempt_id_fkey FOREIGN KEY (attempt_id) REFERENCES payment_attempt(attempt_id);

--
-- Name: paypal_balance_report_disposition paypal_balance_report_disposition_run_id_row_number_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY paypal_balance_report_disposition
    ADD CONSTRAINT paypal_balance_report_disposition_run_id_row_number_fkey FOREIGN KEY (run_id, row_number) REFERENCES paypal_balance_report_row(run_id, row_number);

--
-- Name: paypal_balance_report_row paypal_balance_report_row_run_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY paypal_balance_report_row
    ADD CONSTRAINT paypal_balance_report_row_run_id_fkey FOREIGN KEY (run_id) REFERENCES paypal_balance_report_run(run_id);

--
-- Name: product_category_assignment product_category_assignment_category_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY product_category_assignment
    ADD CONSTRAINT product_category_assignment_category_fk FOREIGN KEY (seller_id, category_id) REFERENCES product_category(seller_id, category_id);

--
-- Name: product_category_assignment product_category_assignment_product_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY product_category_assignment
    ADD CONSTRAINT product_category_assignment_product_fk FOREIGN KEY (seller_id, product_id) REFERENCES product(seller_id, product_id) ON DELETE CASCADE;

--
-- Name: product_category product_category_parent_seller_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY product_category
    ADD CONSTRAINT product_category_parent_seller_fk FOREIGN KEY (seller_id, parent_category_id) REFERENCES product_category(seller_id, category_id);

--
-- Name: product_category product_category_seller_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY product_category
    ADD CONSTRAINT product_category_seller_fk FOREIGN KEY (seller_id) REFERENCES seller(seller_id);

--
-- Name: product_channel_availability product_channel_availability_product_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY product_channel_availability
    ADD CONSTRAINT product_channel_availability_product_fk FOREIGN KEY (product_id) REFERENCES product(product_id);

--
-- Name: product_listing_projection product_listing_projection_product_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY product_listing_projection
    ADD CONSTRAINT product_listing_projection_product_id_fkey FOREIGN KEY (product_id) REFERENCES product(product_id) ON DELETE CASCADE;

--
-- Name: product_review product_review_order_item_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY product_review
    ADD CONSTRAINT product_review_order_item_id_fkey FOREIGN KEY (order_item_id) REFERENCES order_item(order_item_id) ON DELETE RESTRICT;

--
-- Name: product_review product_review_product_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY product_review
    ADD CONSTRAINT product_review_product_id_fkey FOREIGN KEY (product_id) REFERENCES product(product_id) ON DELETE CASCADE;

--
-- Name: product product_seller_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY product
    ADD CONSTRAINT product_seller_fk FOREIGN KEY (seller_id) REFERENCES seller(seller_id);

--
-- Name: product product_seller_primary_category_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY product
    ADD CONSTRAINT product_seller_primary_category_fk FOREIGN KEY (seller_id, primary_category_id) REFERENCES product_category(seller_id, category_id);

--
-- Name: purchase_order purchase_order_campaign_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY purchase_order
    ADD CONSTRAINT purchase_order_campaign_fk FOREIGN KEY (campaign_id) REFERENCES campaign(campaign_id);

--
-- Name: purchase_order purchase_order_checkout_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY purchase_order
    ADD CONSTRAINT purchase_order_checkout_id_fkey FOREIGN KEY (checkout_id) REFERENCES buyer_checkout(checkout_id);

--
-- Name: purchase_order purchase_order_fulfillment_location_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY purchase_order
    ADD CONSTRAINT purchase_order_fulfillment_location_fk FOREIGN KEY (seller_id, fulfillment_location_id) REFERENCES inventory_location(seller_id, location_id);

--
-- Name: purchase_order purchase_order_reservation_workflow_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY purchase_order
    ADD CONSTRAINT purchase_order_reservation_workflow_id_fkey FOREIGN KEY (reservation_workflow_id) REFERENCES order_inventory_reservation_workflow(workflow_id);

--
-- Name: purchase_order purchase_order_seller_coupon_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY purchase_order
    ADD CONSTRAINT purchase_order_seller_coupon_fk FOREIGN KEY (seller_id, seller_coupon_id) REFERENCES seller_coupon(seller_id, coupon_id) ON DELETE RESTRICT;

--
-- Name: refresh_token_family refresh_token_family_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY refresh_token_family
    ADD CONSTRAINT refresh_token_family_account_id_fkey FOREIGN KEY (account_id) REFERENCES user_account(account_id) ON DELETE CASCADE;

--
-- Name: seller_account seller_account_account_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY seller_account
    ADD CONSTRAINT seller_account_account_id_fkey FOREIGN KEY (account_id) REFERENCES user_account(account_id) ON DELETE CASCADE;

--
-- Name: seller_account seller_account_seller_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY seller_account
    ADD CONSTRAINT seller_account_seller_id_fkey FOREIGN KEY (seller_id) REFERENCES seller(seller_id) ON DELETE CASCADE;

--
-- Name: seller_coupon_redemption seller_coupon_redemption_coupon_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY seller_coupon_redemption
    ADD CONSTRAINT seller_coupon_redemption_coupon_fk FOREIGN KEY (seller_id, coupon_id) REFERENCES seller_coupon(seller_id, coupon_id) ON DELETE RESTRICT;

--
-- Name: seller_coupon_redemption seller_coupon_redemption_order_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY seller_coupon_redemption
    ADD CONSTRAINT seller_coupon_redemption_order_fk FOREIGN KEY (order_id, seller_id) REFERENCES purchase_order(order_id, seller_id) ON DELETE RESTRICT;

--
-- Name: seller_coupon seller_coupon_seller_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY seller_coupon
    ADD CONSTRAINT seller_coupon_seller_id_fkey FOREIGN KEY (seller_id) REFERENCES seller(seller_id) ON DELETE RESTRICT;

--
-- Name: seller_payout_profile_action seller_payout_profile_action_seller_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY seller_payout_profile_action
    ADD CONSTRAINT seller_payout_profile_action_seller_id_fkey FOREIGN KEY (seller_id) REFERENCES seller_payout_profile(seller_id);

--
-- Name: seller_payout_profile seller_payout_profile_owner_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY seller_payout_profile
    ADD CONSTRAINT seller_payout_profile_owner_fk FOREIGN KEY (recipient_account_id, seller_id) REFERENCES seller_account(account_id, seller_id);

--
-- Name: seller_payout_profile seller_payout_profile_seller_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY seller_payout_profile
    ADD CONSTRAINT seller_payout_profile_seller_id_fkey FOREIGN KEY (seller_id) REFERENCES seller(seller_id);

--
-- Name: seller_sku_admission_policy seller_sku_admission_policy_sku_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY seller_sku_admission_policy
    ADD CONSTRAINT seller_sku_admission_policy_sku_fk FOREIGN KEY (seller_id, sku_id) REFERENCES seller_sku(seller_id, sku_id);

--
-- Name: seller_sku seller_sku_product_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY seller_sku
    ADD CONSTRAINT seller_sku_product_fk FOREIGN KEY (product_id, seller_id) REFERENCES product(product_id, seller_id);

--
-- Name: seller_sku seller_sku_seller_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY seller_sku
    ADD CONSTRAINT seller_sku_seller_fk FOREIGN KEY (seller_id) REFERENCES seller(seller_id);

--
-- Name: settlement_entry settlement_entry_journal_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY settlement_entry
    ADD CONSTRAINT settlement_entry_journal_id_fkey FOREIGN KEY (journal_id) REFERENCES settlement_journal(journal_id);

--
-- Name: settlement_journal settlement_journal_checkout_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY settlement_journal
    ADD CONSTRAINT settlement_journal_checkout_id_fkey FOREIGN KEY (checkout_id) REFERENCES buyer_checkout(checkout_id);

--
-- Name: settlement_journal settlement_journal_source_payout_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY settlement_journal
    ADD CONSTRAINT settlement_journal_source_payout_id_fkey FOREIGN KEY (source_payout_id) REFERENCES settlement_payout(payout_id);

--
-- Name: settlement_payout_dispatch_outbox settlement_payout_dispatch_outbox_payout_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY settlement_payout_dispatch_outbox
    ADD CONSTRAINT settlement_payout_dispatch_outbox_payout_id_fkey FOREIGN KEY (payout_id) REFERENCES settlement_payout(payout_id);

--
-- Name: settlement_payout_operator_action settlement_payout_operator_action_payout_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY settlement_payout_operator_action
    ADD CONSTRAINT settlement_payout_operator_action_payout_id_fkey FOREIGN KEY (payout_id) REFERENCES settlement_payout(payout_id);

--
-- Name: settlement_payout settlement_payout_seller_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY settlement_payout
    ADD CONSTRAINT settlement_payout_seller_id_fkey FOREIGN KEY (seller_id) REFERENCES seller(seller_id);

--
-- Name: settlement_seller_allocation settlement_seller_allocation_journal_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY settlement_seller_allocation
    ADD CONSTRAINT settlement_seller_allocation_journal_id_fkey FOREIGN KEY (journal_id) REFERENCES settlement_journal(journal_id);

--
-- Name: settlement_seller_allocation settlement_seller_allocation_order_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY settlement_seller_allocation
    ADD CONSTRAINT settlement_seller_allocation_order_id_fkey FOREIGN KEY (order_id) REFERENCES purchase_order(order_id);

--
-- Name: settlement_seller_allocation settlement_seller_allocation_seller_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY settlement_seller_allocation
    ADD CONSTRAINT settlement_seller_allocation_seller_id_fkey FOREIGN KEY (seller_id) REFERENCES seller(seller_id);

--
-- Name: shopping_cart_item shopping_cart_item_cart_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY shopping_cart_item
    ADD CONSTRAINT shopping_cart_item_cart_fk FOREIGN KEY (cart_id) REFERENCES shopping_cart(cart_id) ON DELETE CASCADE;

--
-- Name: shopping_cart_item shopping_cart_item_seller_sku_fk; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY shopping_cart_item
    ADD CONSTRAINT shopping_cart_item_seller_sku_fk FOREIGN KEY (seller_id, sku_id) REFERENCES seller_sku(seller_id, sku_id);

--
-- PostgreSQL database dump complete
--
