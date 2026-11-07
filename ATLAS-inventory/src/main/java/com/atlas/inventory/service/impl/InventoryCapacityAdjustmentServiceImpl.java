package com.atlas.inventory.service.impl;

import com.atlas.inventory.dto.request.AdjustInventoryCapacityRequest;
import com.atlas.inventory.dto.response.InventoryAdjustmentResponse;
import com.atlas.inventory.dto.response.InventoryPoolResponse;
import com.atlas.inventory.exception.InventoryException;
import com.atlas.inventory.persistence.projection.CapacityAdjustmentRecord;
import com.atlas.inventory.persistence.projection.InventoryPoolState;
import com.atlas.inventory.repository.JdbcCapacityAdjustmentRepository;
import com.atlas.inventory.service.InventoryCapacityAdjustmentService;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class InventoryCapacityAdjustmentServiceImpl implements InventoryCapacityAdjustmentService {
    private final JdbcCapacityAdjustmentRepository adjustments;
    private final TransactionTemplate transactions;
    public InventoryCapacityAdjustmentServiceImpl(JdbcCapacityAdjustmentRepository adjustments,PlatformTransactionManager manager){this.adjustments=Objects.requireNonNull(adjustments);this.transactions=new TransactionTemplate(Objects.requireNonNull(manager));}
            // Legacy adjustment remains explicitly bound to DEFAULT even when DEFAULT is inactive; only fulfillment selection honors active.
    @Override public InventoryAdjustmentResponse adjust(AdjustInventoryCapacityRequest r){Objects.requireNonNull(r);return adjustInternal(r.sellerId(),r.skuId(),null,r.idempotencyKey(),r.delta(),r.reason());}
    @Override public InventoryAdjustmentResponse adjust(UUID sellerId,String skuId,UUID locationId,String key,int delta,String reason){return adjustInternal(sellerId,skuId,Objects.requireNonNull(locationId),key,delta,reason);}
    private InventoryAdjustmentResponse adjustInternal(UUID seller,String sku,UUID location,String key,int delta,String reason){
        if(seller==null||sku==null||sku.isBlank()||sku.length()>128||key==null||key.isBlank()||key.length()>256||reason==null||reason.isBlank()||delta==0||delta==Integer.MIN_VALUE)throw invalid("seller, SKU, idempotency key, reason and nonzero delta are required");
        return Objects.requireNonNull(transactions.execute(status->{
            // A null location is the legacy DEFAULT endpoint; its pool identity is resolved by repository.
            if(location==null) adjustments.lock(seller,sku,key); else adjustments.lock(seller,location,sku,key);
            var prior=location==null?adjustments.find(seller,sku,key):adjustments.find(seller,location,sku,key);
            if(prior.isPresent()){var p=prior.get();if(p.delta()!=delta||!p.reason().equals(reason))throw new InventoryException(InventoryException.Code.CONFLICT,"idempotency key already used for different adjustment facts");return response(p);}
            if (location != null) adjustments.ensureLocationPool(seller, location, sku);
            var recorded=location==null?adjustments.record(seller,sku,key,delta,reason):adjustments.record(seller,location,sku,key,delta,reason);
            if(recorded.isPresent())return response(recorded.get());
            var current=location==null?adjustments.findPool(seller,sku):adjustments.findPool(seller,location,sku);
            InventoryPoolState pool=current.orElseThrow(()->new InventoryException(InventoryException.Code.POOL_NOT_FOUND,"inventory pool not found"));
            if(delta<0&&pool.available() < -delta)throw new InventoryException(InventoryException.Code.INSUFFICIENT_STOCK,"adjustment exceeds available inventory");
            if(delta>0&&(pool.initialCapacity()>Integer.MAX_VALUE-delta||pool.available()>Integer.MAX_VALUE-delta))throw invalid("adjustment exceeds maximum inventory capacity");
            throw new InventoryException(InventoryException.Code.INSUFFICIENT_STOCK,"capacity adjustment was not applied");
        }));
    }
    private static InventoryAdjustmentResponse response(CapacityAdjustmentRecord r){InventoryPoolState p=r.pool();return new InventoryAdjustmentResponse(r.sellerId(),r.skuId(),r.idempotencyKey(),r.delta(),r.reason(),new InventoryPoolResponse(r.sellerId(),r.skuId(),p.initialCapacity(),p.available(),p.reserved(),p.claimed()),r.createdAt());}
    private static InventoryException invalid(String m){return new InventoryException(InventoryException.Code.INVALID_REQUEST,m);}
}
