package com.atlas.application.controller.assistant;

import com.atlas.application.dto.request.AssistantHttpRequest;
import com.atlas.agent.dto.request.AssistantTurnRequest;
import com.atlas.agent.dto.response.AssistantToolResponse;
import com.atlas.agent.dto.response.AssistantTurnResponse;
import com.atlas.agent.dto.response.SpendingCapResponse;
import com.atlas.agent.service.ShoppingAssistantService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 쇼핑 비서 HTTP(ADR-0109). {@code /api/buyers/{buyerRef}/...} 경로는 {@code AccountOwnershipInterceptor}가 인증 주체의
 * 구매자인지 확인하므로, 비서의 모든 도구가 그 구매자 권한으로만 실행된다.
 */
@RequiredArgsConstructor
@RestController
public class ShoppingAssistantController {
    private final ShoppingAssistantService assistant;

    @PostMapping("/api/buyers/{buyerRef}/assistant/turns")
    public AssistantTurnResponse converse(@PathVariable String buyerRef,
                                          @Valid @RequestBody AssistantHttpRequest.Turn request) {
        return assistant.converse(buyerRef, new AssistantTurnRequest(request.conversationId(), request.message(),
                request.confirmedTotalWon()));
    }

    @GetMapping("/api/buyers/{buyerRef}/assistant/spending-cap")
    public SpendingCapResponse findSpendingCap(@PathVariable String buyerRef) {
        return assistant.findSpendingCap(buyerRef);
    }

    @PutMapping("/api/buyers/{buyerRef}/assistant/spending-cap")
    public SpendingCapResponse setSpendingCap(@PathVariable String buyerRef,
                                              @Valid @RequestBody AssistantHttpRequest.SpendingCap request) {
        return assistant.setSpendingCap(buyerRef, request.capWon());
    }

    @DeleteMapping("/api/buyers/{buyerRef}/assistant/spending-cap")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearSpendingCap(@PathVariable String buyerRef) {
        assistant.clearSpendingCap(buyerRef);
    }

    /** MCP {@code tools/list} 결과와 같은 모양. 실행 경로는 위 대화 API 하나뿐이다(MCP 서버를 따로 띄우지 않는다). */
    @GetMapping("/api/assistant/tools")
    public Map<String, List<AssistantToolResponse>> tools() {
        return Map.of("tools", assistant.tools());
    }
}
