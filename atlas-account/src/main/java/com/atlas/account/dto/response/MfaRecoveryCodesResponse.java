package com.atlas.account.dto.response;

import java.util.List;

public record MfaRecoveryCodesResponse(List<String> recoveryCodes) {
}
