package com.atlas.order.persistence.entity;

import com.atlas.order.persistence.state.OrderReturnInspectionResult;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "order_return_item")
public class OrderReturnItem {
    @Id @GeneratedValue @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "return_item_id", nullable = false)
    private UUID returnItemId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "return_id", nullable = false) private OrderReturn orderReturn;
    @Column(name = "order_item_id", nullable = false) private UUID orderItemId;
    @Column(name = "quantity", nullable = false) private int quantity;
    @Enumerated(EnumType.STRING) @Column(name = "inspection_result", length = 16)
    private OrderReturnInspectionResult inspectionResult;
    @Column(name = "refund_id") private UUID refundId;
    @Column(name = "refund_amount_won") private Long refundAmountWon;
    @Column(name = "inspected_at") private Instant inspectedAt;

    protected OrderReturnItem() { }
    public OrderReturnItem(UUID orderItemId, int quantity) { this.orderItemId = orderItemId; this.quantity = quantity; }
    void setOrderReturn(OrderReturn orderReturn) { this.orderReturn = orderReturn; }
    public void inspect(OrderReturnInspectionResult result, UUID refundId, Long amount, Instant at) {
        this.inspectionResult = result; this.refundId = refundId; this.refundAmountWon = amount; this.inspectedAt = at;
    }
    public UUID returnItemId() { return returnItemId; }
    public UUID orderItemId() { return orderItemId; }
    public int quantity() { return quantity; }
    public OrderReturnInspectionResult inspectionResult() { return inspectionResult; }
    public UUID refundId() { return refundId; }
    public Long refundAmountWon() { return refundAmountWon; }
    public Instant inspectedAt() { return inspectedAt; }
}
