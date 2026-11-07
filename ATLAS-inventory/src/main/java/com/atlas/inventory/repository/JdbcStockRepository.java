package com.atlas.inventory.repository;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcStockRepository {
    private final JdbcTemplate jdbc;
    public JdbcStockRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void lockOrder(UUID orderId){jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",(RowCallbackHandler)rs->{},orderId.toString());}
    public UUID defaultLocation(UUID sellerId){jdbc.update("INSERT INTO inventory_location(seller_id,location_code,name,priority,active) VALUES(?,'DEFAULT','Default',0,TRUE) ON CONFLICT(seller_id,location_code) DO NOTHING",sellerId);return jdbc.queryForObject("SELECT location_id FROM inventory_location WHERE seller_id=? AND location_code='DEFAULT'",UUID.class,sellerId);}
    public void ensurePool(UUID sellerId,String skuId,int initialStock){UUID loc=defaultLocation(sellerId);jdbc.update("INSERT INTO inventory_pool(seller_id,location_id,sku_id,initial_capacity,available,reserved,claimed) VALUES(?,?,?,?,?,0,0) ON CONFLICT(seller_id,location_id,sku_id) DO NOTHING",sellerId,loc,skuId,initialStock,initialStock);}
    public int reserve(UUID seller,String sku,UUID loc,int q){return jdbc.update("UPDATE inventory_pool p SET available=available-?,reserved=reserved+? FROM inventory_location l WHERE p.seller_id=? AND p.sku_id=? AND p.location_id=? AND p.available>=? AND l.seller_id=p.seller_id AND l.location_id=p.location_id AND l.active",q,q,seller,sku,loc,q);}
    public int claim(UUID seller,String sku,UUID loc,int q){return jdbc.update("UPDATE inventory_pool SET reserved=reserved-?,claimed=claimed+? WHERE seller_id=? AND sku_id=? AND location_id=? AND reserved>=?",q,q,seller,sku,loc,q);}
    public int release(UUID seller,String sku,UUID loc,int q){return jdbc.update("UPDATE inventory_pool SET available=available+?,reserved=reserved-? WHERE seller_id=? AND sku_id=? AND location_id=? AND reserved>=?",q,q,seller,sku,loc,q);}
    public int restoreClaimed(UUID seller,String sku,UUID loc,int q){return jdbc.update("UPDATE inventory_pool SET available=available+?,claimed=claimed-? WHERE seller_id=? AND sku_id=? AND location_id=? AND claimed>=?",q,q,seller,sku,loc,q);}
}
