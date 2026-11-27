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

    /**
     * 커서 잠금, pending 확인, 마감 행 갱신, 커서 이동, 새 pending 기록을 한 트랜잭션으로 한다. 잔액 읽기는 커서와 마감
     * 행을 같은 스냅숏에서 본다. 커서는 앞 마감이 남긴 pending 까지만, 그 xid 보다 먼저 시작한 쓰기가 모두 끝난 뒤에만
     * 옮긴다({@link SettlementBalanceCheckpointRepository} 커서 규칙). pending 이 아직 끝나지 않았으면 새 pending 으로
     * 바꾸지 않는다(긴 쓰기가 이어져도 더 이른 경계를 먼저 넘는다).
     */
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
        Optional<SettlementBalanceCheckpointRepository.Pending> pending = checkpoints.pending();
        if (pending.isPresent()) {
            if (!pending.get().settled()) {
                return false;
            }
            UUID target = pending.get().entryId();
            if (compareUnsigned(target, from) > 0) {
                UUID to = checkpoints.batchEnd(from, target, maxEntries);
                checkpoints.fold(from, to);
                checkpoints.advanceCursor(to);
                if (!to.equals(target)) {
                    return true;
                }
            }
        }
        checkpoints.recordPending(safetyLag);
        return false;
    }

    /** PostgreSQL 은 UUID 를 부호 없는 바이트 순서로 비교한다. Java UUID.compareTo 는 부호 있는 long 비교라 따로 센다. */
    private static int compareUnsigned(UUID left, UUID right) {
        int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
    }
}
