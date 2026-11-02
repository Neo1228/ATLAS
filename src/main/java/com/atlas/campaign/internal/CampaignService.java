package com.atlas.campaign.internal;

import com.atlas.campaign.CampaignApi;
import com.atlas.campaign.CampaignNotEligibleException;
import com.atlas.campaign.CampaignStatus;
import com.atlas.campaign.CampaignView;
import com.atlas.campaign.CreateCampaignCommand;

import com.atlas.inventory.CreateInventoryPoolCommand;
import com.atlas.inventory.InventoryApi;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CampaignService implements CampaignApi {

    private final JdbcTemplate jdbc;
    private final InventoryApi inventory;
    private final Clock clock;
    private final Supplier<UUID> ids;

    @Autowired
    public CampaignService(JdbcTemplate jdbc, InventoryApi inventory, Clock clock) {
        this(jdbc, inventory, clock, UUID::randomUUID);
    }

    CampaignService(JdbcTemplate jdbc, InventoryApi inventory, Clock clock, Supplier<UUID> ids) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.clock = clock;
        this.ids = ids;
    }

    @Override
    @Transactional
    public CampaignView create(CreateCampaignCommand command) {
        if (command == null || command.skuId() == null || command.skuId().isBlank()
                || command.skuId().length() > 128 || command.inventoryCapacity() <= 0
                || command.queueTicketTtl() == null || command.queueTicketTtl().isZero()
                || command.queueTicketTtl().isNegative() || command.queueTicketTtl().toSeconds() <= 0
                || command.maxActiveCheckouts() <= 0) {
            throw new IllegalArgumentException("Invalid campaign configuration");
        }
        UUID campaignId = ids.get();
        jdbc.update("""
                INSERT INTO campaign (campaign_id, sku_id, status, queue_ticket_ttl_seconds, max_active_checkouts, created_at)
                VALUES (?, ?, 'CLOSED', ?, ?, ?)
                """, campaignId, command.skuId(), command.queueTicketTtl().toSeconds(),
                command.maxActiveCheckouts(), java.sql.Timestamp.from(clock.instant()));
        inventory.createPool(new CreateInventoryPoolCommand(campaignId, command.skuId(), command.inventoryCapacity()));
        return find(campaignId);
    }

    @Override
    @Transactional
    public CampaignView open(UUID campaignId) {
        int changed = jdbc.update("UPDATE campaign SET status = 'OPEN' WHERE campaign_id = ?", campaignId);
        if (changed == 0) throw new IllegalArgumentException("Unknown campaign: " + campaignId);
        return find(campaignId);
    }

    @Override
    @Transactional(readOnly = true)
    public CampaignView requireOpen(UUID campaignId, String skuId) {
        CampaignView campaign = find(campaignId);
        if (campaign == null || campaign.status() != CampaignStatus.OPEN || !campaign.skuId().equals(skuId)) {
            throw new CampaignNotEligibleException(campaignId, skuId);
        }
        return campaign;
    }

    @Override
    @Transactional(readOnly = true)
    public CampaignView find(UUID campaignId) {
        return jdbc.query("""
                SELECT campaign_id, sku_id, status, queue_ticket_ttl_seconds, max_active_checkouts
                FROM campaign WHERE campaign_id = ?
                """, rs -> rs.next() ? new CampaignView(rs.getObject("campaign_id", UUID.class),
                rs.getString("sku_id"), CampaignStatus.valueOf(rs.getString("status")),
                java.time.Duration.ofSeconds(rs.getLong("queue_ticket_ttl_seconds")),
                rs.getInt("max_active_checkouts")) : null, campaignId);
    }
}
