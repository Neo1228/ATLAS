package com.atlas.account.service;

import com.atlas.account.dto.response.AccountResponse;
import java.util.List;

/**
 * 알림 outbox(V9)에 기록된 인증 메일을 보낸다. worker 조립은 {@code atlas-application}이 맡는다(ADR-0102).
 *
 * <p>전달은 최소 한 번이다. SMTP가 받은 뒤 완료 기록 전에 프로세스가 죽으면 임대 만료 뒤 다시 보내고, 그때 새 토큰을
 * 발급하므로 앞 메일의 토큰은 무효가 된다(사용자가 재요청할 때와 같다).
 */
public interface AccountNotificationService {
    /** 기한이 된 행을 최대 {@code limit}건 처리한다. 처리할 행이 없으면 빈 목록이다. */
    List<AccountResponse.NotificationDelivery> deliverDue(int limit);

    AccountResponse.NotificationBacklog backlog();
}
