package com.atlas.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.order.exception.LocationClientException;
import com.atlas.order.model.RemoteReservationStatus;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * order 가 inventory-location 응답을 해석하는 어휘가 계약 파일({@code contracts/inventory-location-vocabulary.json})과
 * 같다. inventory-location 쪽 {@code VocabularyContractTest} 가 같은 파일로 자기 쪽을 비교하므로 두 서비스의 어휘가
 * 어긋나면 어느 한쪽 CI 가 실패한다. 어긋나면 모르는 예약 상태는 재시도로, 재입고는 NEEDS_REVIEW 로 끝난다.
 */
class InventoryLocationVocabularyContractTest {

    private static final JsonNode CONTRACT = read();

    @Test
    void remoteReservationStatusMatchesTheContract() {
        assertThat(Arrays.stream(RemoteReservationStatus.values()).map(Enum::name).toList())
                .containsExactlyInAnyOrderElementsOf(values("reservationStatus"));
    }

    @Test
    void errorCodesOrderBranchesOnAreCodesTheRemoteActuallyDefines() {
        List<String> branchedOn = List.of(
                LocationClientException.INSUFFICIENT_STOCK, LocationClientException.INVALID_STATE,
                LocationClientException.RESERVATION_CANCELLED, LocationClientException.RESERVATION_EXPIRED);
        assertThat(branchedOn).containsExactlyInAnyOrderElementsOf(values("errorCodesOrderBranchesOn"));
        assertThat(values("errorCode")).containsAll(branchedOn);
    }

    private static List<String> values(String field) {
        List<String> values = new ArrayList<>();
        CONTRACT.get(field).forEach(node -> values.add(node.stringValue()));
        return values;
    }

    private static JsonNode read() {
        try {
            return new ObjectMapper().readTree(Path.of("..", "contracts", "inventory-location-vocabulary.json").toFile());
        } catch (RuntimeException e) {
            throw new IllegalStateException("계약 파일을 읽을 수 없다: 테스트 작업 디렉터리는 모듈 디렉터리여야 한다", e);
        }
    }
}
