package com.atlas.settlement.service.impl;

import com.atlas.settlement.repository.SettlementBalanceCheckpointRepository;
import com.atlas.settlement.service.SettlementBalanceCheckpointService;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettlementBalanceCheckpointServiceImpl implements SettlementBalanceCheckpointService {
    private final SettlementBalanceCheckpointRepository checkpoints;

    public SettlementBalanceCheckpointServiceImpl(SettlementBalanceCheckpointRepository checkpoints) {
        this.checkpoints = Objects.requireNonNull(checkpoints);
    }

    /** 커서 잠금, 범위 계산, 마감 행 갱신, 커서 이동을 한 트랜잭션으로 한다. 잔액 읽기는 커서와 마감 행을 같은 스냅숏에서 본다. */
    @Override
    @Transactional
    public boolean foldOnce(int maxEntries, Duration safetyLag) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        if (safetyLag == null || safetyLag.isNegative()) {
            throw new IllegalArgumentException("safetyLag must not be negative");
        }
        Optional<UUID> locked = checkpoints.lockCursor();
        if (locked.isEmpty()) {
            return false;
        }
        UUID from = locked.get();
        UUID horizon = SettlementBalanceCheckpointRepository.uuidV7Floor(checkpoints.writeHorizon(safetyLag));
        if (compareUnsigned(horizon, from) <= 0) {
            return false;
        }
        UUID to = checkpoints.batchEnd(from, horizon, maxEntries);
        checkpoints.fold(from, to);
        checkpoints.advanceCursor(to);
        return !to.equals(horizon);
    }

    /** PostgreSQL 은 UUID 를 부호 없는 바이트 순서로 비교한다. Java UUID.compareTo 는 부호 있는 long 비교라 따로 센다. */
    private static int compareUnsigned(UUID left, UUID right) {
        int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }
}
