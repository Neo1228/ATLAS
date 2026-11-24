package com.atlas.inventorylocation.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.inventorylocation.exception.InventoryLocationException;
import com.atlas.inventorylocation.model.LocationReservationStatus;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 이 서비스가 order 에게 돌려주는 값이 계약 파일({@code contracts/inventory-location-vocabulary.json})과 같다.
 * order 쪽 {@code InventoryLocationVocabularyContractTest} 가 같은 파일을 읽어 자기 쪽을 비교한다.
 */
class VocabularyContractTest {

    private static final JsonNode CONTRACT = read();

    @Test
    void reservationStatusMatchesTheContract() {
        assertThat(names(LocationReservationStatus.values())).containsExactlyInAnyOrderElementsOf(values("reservationStatus"));
    }

    @Test
    void errorCodesMatchTheContract() {
        assertThat(names(InventoryLocationException.Code.values())).containsExactlyInAnyOrderElementsOf(values("errorCode"));
    }

    private static List<String> names(Enum<?>[] constants) {
        return Arrays.stream(constants).map(Enum::name).toList();
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
