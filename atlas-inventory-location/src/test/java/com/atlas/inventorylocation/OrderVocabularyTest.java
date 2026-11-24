package com.atlas.inventorylocation;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.inventorylocation.exception.InventoryLocationException;
import com.atlas.inventorylocation.model.LocationReservationStatus;
import com.atlas.order.exception.LocationClientException;
import com.atlas.order.model.RemoteReservationStatus;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * order 가 이 서비스의 HTTP 응답을 해석하는 어휘가 이 서비스가 실제로 돌려주는 값과 같다.
 *
 * <p>두 서비스는 독립 배포되어 main 코드는 서로를 모른다. 대신 제공하는 쪽(이 서비스)의 테스트가 소비하는 쪽
 * (order)의 기대를 컴파일러가 보이는 타입으로 직접 비교한다. 어긋나면 order 는 모르는 예약 상태를 재시도로,
 * 재입고는 NEEDS_REVIEW 로 끝낸다.
 */
class OrderVocabularyTest {

    @Test
    void orderUnderstandsExactlyTheReservationStatusesThisServiceReturns() {
        assertThat(names(RemoteReservationStatus.values()))
                .containsExactlyInAnyOrderElementsOf(names(LocationReservationStatus.values()));
    }

    @Test
    void errorCodesOrderBranchesOnAreCodesThisServiceDefines() {
        List<String> branchedOn = List.of(
                LocationClientException.INSUFFICIENT_STOCK, LocationClientException.INVALID_STATE,
                LocationClientException.RESERVATION_CANCELLED, LocationClientException.RESERVATION_EXPIRED);
        assertThat(names(InventoryLocationException.Code.values())).containsAll(branchedOn);
    }

    private static List<String> names(Enum<?>[] constants) {
        return Arrays.stream(constants).map(Enum::name).toList();
    }
}
