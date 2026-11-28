package com.atlas.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.account.model.AccountActionTokenPurpose;
import com.atlas.account.model.AccountNotificationStatus;
import com.atlas.account.model.AccountSuspensionActionType;
import com.atlas.account.model.AuthAttemptScope;
import com.atlas.admission.model.CampaignStatus;
import com.atlas.llm.model.ChatRole;
import com.atlas.admission.model.TicketStatus;
import com.atlas.catalog.model.ProductSalesChannel;
import com.atlas.catalog.model.ProductStatus;
import com.atlas.catalog.model.SellerCouponRedemptionState;
import com.atlas.inventory.model.ReservationStatus;
import com.atlas.order.model.BuyerCheckoutState;
import com.atlas.order.model.FulfillmentStatus;
import com.atlas.order.model.LocationReservationState;
import com.atlas.order.model.LocationReservationWorkflowState;
import com.atlas.order.model.LocationRestockState;
import com.atlas.order.model.OrderRefundPaymentStatus;
import com.atlas.order.model.OrderRefundStatus;
import com.atlas.order.model.OrderReturnInspectionResult;
import com.atlas.order.model.OrderReturnStatus;
import com.atlas.order.model.ReturnReason;
import com.atlas.order.model.OrderShipmentStatus;
import com.atlas.order.model.OrderSource;
import com.atlas.order.model.OrderState;
import com.atlas.payment.model.PaymentPhase;
import com.atlas.payment.model.PaymentRecoveryJobStatus;
import com.atlas.payment.model.PaymentRecoveryOperationType;
import com.atlas.payment.model.PaymentRecoveryWorkStage;
import com.atlas.payment.model.ProviderAdjustmentKind;
import com.atlas.payment.model.ProviderAdjustmentStatus;
import com.atlas.payment.model.PaymentRefundState;
import com.atlas.payment.model.PaymentState;
import com.atlas.payment.model.WebhookRequeueOutcome;
import com.atlas.payment.model.WebhookRetryAttemptOutcome;
import com.atlas.settlement.model.PayPalBalanceReportDispositionType;
import com.atlas.settlement.model.PayPalBalanceReportRowStatus;
import com.atlas.settlement.model.PayoutDispatchState;
import com.atlas.settlement.model.PayoutOperatorActionType;
import com.atlas.settlement.model.RefundReservationState;
import com.atlas.settlement.model.SellerPayoutProfile;
import com.atlas.settlement.model.SellerPayoutProfileActionType;
import com.atlas.settlement.model.SettlementPayoutState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * 값 집합이 DB CHECK 제약으로 고정된 컬럼은 Java enum 의 이름 집합과 정확히 같아야 한다.
 *
 * <p>enum 이름이 그대로 저장되므로 어느 한쪽에만 값이 추가되면 저장이 제약 위반으로 실패하거나, 읽을 때
 * {@code valueOf} 가 실패한다. 이 테스트는 그 어긋남을 마이그레이션을 적용한 실제 PostgreSQL 에서 잡는다.
 * 값 집합 제약이 있는 컬럼이 이 목록에 없으면 두 번째 테스트가 실패하므로, 새 제약을 추가할 때 소유 enum 을
 * 함께 정해야 한다.
 */
@Testcontainers
class EnumCheckConstraintContractTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.6");

    private static JdbcTemplate jdbc;

    /** "table.column" -> 그 컬럼의 값 집합을 소유하는 enum. */
    private static final Map<String, Class<? extends Enum<?>>> OWNERS = Map.ofEntries(
            Map.entry("admission_ticket.status", TicketStatus.class),
            Map.entry("assistant_message.role", ChatRole.class),
            Map.entry("inventory_reservation.status", ReservationStatus.class),
            Map.entry("campaign.status", CampaignStatus.class),
            Map.entry("buyer_checkout.state", BuyerCheckoutState.class),
            Map.entry("purchase_order.state", OrderState.class),
            Map.entry("purchase_order.order_source", OrderSource.class),
            Map.entry("order_fulfillment.status", FulfillmentStatus.class),
            Map.entry("order_shipment.status", OrderShipmentStatus.class),
            Map.entry("order_return.return_status", OrderReturnStatus.class),
            Map.entry("order_return_item.inspection_result", OrderReturnInspectionResult.class),
            Map.entry("order_return.reason_code", ReturnReason.class),
            Map.entry("order_refund_request.refund_status", OrderRefundStatus.class),
            Map.entry("order_refund_summary.refund_status", OrderRefundStatus.class),
            Map.entry("order_refund_request.payment_refund_status", OrderRefundPaymentStatus.class),
            Map.entry("order_inventory_location_command.command_state", LocationReservationState.class),
            Map.entry("order_inventory_reservation_workflow.workflow_state", LocationReservationWorkflowState.class),
            Map.entry("order_inventory_restock.restock_state", LocationRestockState.class),
            Map.entry("payment_attempt.state", PaymentState.class),
            Map.entry("payment_attempt.payment_phase", PaymentPhase.class),
            Map.entry("payment_refund_operation.refund_state", PaymentRefundState.class),
            Map.entry("payment_recovery_job.status", PaymentRecoveryJobStatus.class),
            Map.entry("payment_recovery_job.operation_type", PaymentRecoveryOperationType.class),
            Map.entry("payment_recovery_job.work_stage", PaymentRecoveryWorkStage.class),
            Map.entry("payment_provider_adjustment.kind", ProviderAdjustmentKind.class),
            Map.entry("payment_provider_adjustment.status", ProviderAdjustmentStatus.class),
            Map.entry("paypal_webhook_retry_attempt_history.outcome", WebhookRetryAttemptOutcome.class),
            Map.entry("paypal_webhook_retry_operator_audit.outcome", WebhookRequeueOutcome.class),
            Map.entry("product.status", ProductStatus.class),
            Map.entry("product_channel_availability.sales_channel", ProductSalesChannel.class),
            Map.entry("seller_coupon_redemption.state", SellerCouponRedemptionState.class),
            Map.entry("settlement_payout.state", SettlementPayoutState.class),
            Map.entry("settlement_payout_dispatch_outbox.status", PayoutDispatchState.class),
            Map.entry("settlement_payout_operator_action.action", PayoutOperatorActionType.class),
            Map.entry("seller_payout_profile.status", SellerPayoutProfile.State.class),
            Map.entry("seller_payout_profile_action.action", SellerPayoutProfileActionType.class),
            Map.entry("settlement_refund_reservation.status", RefundReservationState.class),
            Map.entry("paypal_balance_report_row.status", PayPalBalanceReportRowStatus.class),
            Map.entry("paypal_balance_report_disposition.disposition", PayPalBalanceReportDispositionType.class),
            Map.entry("account_action_token.purpose", AccountActionTokenPurpose.class),
            Map.entry("account_notification_outbox.purpose", AccountActionTokenPurpose.class),
            Map.entry("account_notification_outbox.status", AccountNotificationStatus.class),
            Map.entry("account_suspension_action.action", AccountSuspensionActionType.class),
            Map.entry("auth_origin_bucket.scope", AuthAttemptScope.OriginBucket.class),
            Map.entry("auth_subject_bucket.scope", AuthAttemptScope.SubjectBucket.class));

    /** enum 에는 있지만 DB 에는 저장되지 않는 값. 환불 요약 행이 없을 때 응답이 만드는 NONE 이 그렇다. */
    private static final Map<String, Set<String>> NOT_STORED = Map.of(
            // 시스템 프롬프트는 코드 상수라 저장하지 않는다(ADR-0109).
            "assistant_message.role", Set.of("SYSTEM"),
            "order_refund_request.refund_status", Set.of("NONE"),
            "order_refund_summary.refund_status", Set.of("NONE"));

    /**
     * 값 집합 제약이 있지만 Java 가 그 값을 읽지 않고 SQL 안에서만 쓰는 컬럼. 이 컬럼을 Java 로 읽기 시작하면
     * 소유 enum 을 만들고 {@link #OWNERS} 로 옮겨야 한다.
     */
    private static final Set<String> SQL_ONLY = Set.of(
            "paypal_webhook_retry.state",
            "payment_provider_webhook_event.processing_status",
            "user_account.status",
            "settlement_entry.entry_side",
            // 계정 코드와 차변·대변의 허용 조합 규칙이며 독립된 값 집합이 아니다.
            "settlement_entry.account_code",
            // BuyerCheckoutState 의 부분집합(EXPIRED 하나)이고 Java 는 이미 BuyerCheckoutState 로 읽는다.
            "buyer_checkout.cancellation_target");

    @BeforeAll
    static void migrate() {
        String schema = "enum_contract_" + UUID.randomUUID().toString().replace("-", "");
        new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()))
                .execute("CREATE SCHEMA " + schema);
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        String url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?")
                + "currentSchema=" + schema;
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword()));
    }

    @Test
    void everyOwnedColumnHasTheSameValueSetAsItsEnum() {
        List<String> mismatches = new ArrayList<>();
        OWNERS.forEach((column, owner) -> {
            TreeSet<String> db = constraintValues(column);
            TreeSet<String> fromEnum = new TreeSet<>(Arrays.stream(owner.getEnumConstants()).map(Enum::name).toList());
            fromEnum.removeAll(NOT_STORED.getOrDefault(column, Set.of()));
            if (db.isEmpty()) {
                mismatches.add(column + ": DB 에 값 집합 CHECK 제약이 없다");
            } else if (!db.equals(fromEnum)) {
                mismatches.add(column + ": DB=" + db + " enum(" + owner.getSimpleName() + ")=" + fromEnum);
            }
        });
        assertThat(mismatches).as("enum 과 CHECK 제약의 값 집합 불일치").isEmpty();
    }

    @Test
    void everyConstrainedColumnHasAnOwningEnum() {
        // 값 집합 제약이 있는 컬럼 중 이 목록에 빠진 것을 찾는다.
        List<String> unowned = new ArrayList<>();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT c.relname AS tbl, pg_get_constraintdef(k.oid) AS def
                FROM pg_constraint k JOIN pg_class c ON c.oid = k.conrelid
                WHERE k.contype = 'c' AND c.relnamespace = current_schema()::regnamespace
                """);
        Pattern column = Pattern.compile("\\(+(\\w+)\\)+(?:::text)? = (?:ANY|'[A-Z])");
        for (Map<String, Object> row : rows) {
            Matcher m = column.matcher((String) row.get("def"));
            String key = m.find() ? row.get("tbl") + "." + m.group(1) : null;
            if (key != null && !OWNERS.containsKey(key) && !SQL_ONLY.contains(key)) {
                unowned.add(key);
            }
        }
        assertThat(unowned).as("값 집합 CHECK 제약이 있지만 소유 enum 이 지정되지 않은 컬럼").isEmpty();
    }

    private static TreeSet<String> constraintValues(String column) {
        String table = column.substring(0, column.indexOf('.'));
        String name = column.substring(column.indexOf('.') + 1);
        List<String> defs = jdbc.queryForList("""
                SELECT pg_get_constraintdef(k.oid)
                FROM pg_constraint k JOIN pg_class c ON c.oid = k.conrelid
                WHERE k.contype = 'c' AND c.relname = ? AND c.relnamespace = current_schema()::regnamespace
                """, String.class, table);
        Pattern own = Pattern.compile("\\(+" + name + "\\)+(?:::text)? = (?:ANY|'[A-Z])");
        TreeSet<String> values = new TreeSet<>();
        for (String def : defs) {
            if (own.matcher(def).find()) {
                Matcher v = Pattern.compile("'([A-Z][A-Z0-9_]*)'").matcher(def);
                while (v.find()) {
                    values.add(v.group(1));
                }
            }
        }
        return values;
    }
}
