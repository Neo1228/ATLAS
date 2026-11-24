package com.atlas.account.dto.response;

public record AccountLoginResponse(boolean mfaRequired, AccountTokenResponse tokens, String challengeToken) {
}
