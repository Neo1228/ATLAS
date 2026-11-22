package com.atlas.application.service.payout;

import com.atlas.account.domain.VerifiedSellerAccount;
import com.atlas.account.service.AccountService;
import com.atlas.application.controller.dto.SellerPayoutProfileRequest;
import com.atlas.application.controller.dto.SellerPayoutProfileResponse;
import com.atlas.application.controller.dto.VerifySellerPayoutProfileRequest;
import com.atlas.payment.provider.paypal.PayPalPayoutsProperties;
import com.atlas.settlement.exception.SettlementException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class SellerPayoutProfileService {
    private static final String USD = "USD";
    private final SellerPayoutProfileRepository profiles;
    private final AccountService accounts;
    private final PayPalPayoutsProperties payoutsProperties;
    private final Clock clock;

    public SellerPayoutProfileService(SellerPayoutProfileRepository profiles, AccountService accounts,
            PayPalPayoutsProperties payoutsProperties, Clock clock) {
        this.profiles = Objects.requireNonNull(profiles);
        this.accounts = Objects.requireNonNull(accounts);
        this.payoutsProperties = Objects.requireNonNull(payoutsProperties);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional
    public SellerPayoutProfileResponse configure(UUID accountId, UUID sellerId, String idempotencyKey,
            SellerPayoutProfileRequest request) {
        if (accountId == null || sellerId == null || request == null || !StringUtils.hasText(idempotencyKey)
                || idempotencyKey.length() > 128 || !request.usdWalletConfirmed()
                || !request.verifiedAccountEmailConfirmed()) {
            throw invalid("A verified Seller account, USD-recipient confirmation, and idempotency key are required");
        }
        String countryCode = normalizeCountry(request.countryCode());
        requireSupportedCountry(countryCode);
        VerifiedSellerAccount account = accounts.findVerifiedSellerAccount(accountId, sellerId)
                .orElseThrow(() -> conflict("An active Seller account with a verified email is required"));
        String requestHash = hash("profile|" + account.accountId() + "|" + sellerId + "|" + countryCode + "|USD");
        Optional<String> priorHash = profiles.findRequestHash(sellerId, idempotencyKey);
        if (priorHash.isPresent()) {
            if (!priorHash.get().equals(requestHash)) throw conflict("Idempotency-Key was reused for different payout-profile facts");
            return toResponse(requireProfile(sellerId));
        }

        Instant now = clock.instant();
        Optional<SellerPayoutProfile> prior = profiles.find(sellerId);
        SellerPayoutProfile profile;
        String action;
        if (prior.isPresent() && prior.get().recipientAccountId().equals(account.accountId())
                && prior.get().countryCode().equals(countryCode) && prior.get().payoutCurrency().equals(USD)) {
            if (prior.get().state() == SellerPayoutProfile.State.SUSPENDED) {
                throw conflict("Suspended payout profile requires operator review");
            }
            profile = prior.get();
            action = "PROFILE_CONFIRMED";
        } else {
            profile = profiles.submit(sellerId, account.accountId(), countryCode, now);
            action = "PROFILE_SUBMITTED";
        }
        if (!profiles.recordAction(sellerId, profile.profileVersion(), action, idempotencyKey,
                requestHash, account.accountId().toString(), "seller_confirmed_verified_email_and_usd_wallet", now)) {
            String existingHash = profiles.findRequestHash(sellerId, idempotencyKey).orElseThrow(
                    () -> new IllegalStateException("Payout profile idempotency action disappeared"));
            if (!existingHash.equals(requestHash)) throw conflict("Idempotency-Key was reused for different payout-profile facts");
            return toResponse(requireProfile(sellerId));
        }
        return toResponse(profile);
    }

    @Transactional
    public SellerPayoutProfileResponse verify(UUID sellerId, String actor, String idempotencyKey,
            VerifySellerPayoutProfileRequest request) {
        if (sellerId == null || !StringUtils.hasText(actor) || actor.length() > 128
                || !StringUtils.hasText(idempotencyKey) || idempotencyKey.length() > 128 || request == null
                || request.profileVersion() < 1 || !StringUtils.hasText(request.evidenceReference())
                || request.evidenceReference().length() > 500
                || !request.evidenceReference().matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,499}")) {
            throw invalid("Seller, operator, profile version, evidence reference, and idempotency key are required");
        }
        String requestHash = hash("verify|" + sellerId + "|" + request.profileVersion()
                + "|" + request.evidenceReference());
        Optional<String> priorHash = profiles.findRequestHash(sellerId, idempotencyKey);
        if (priorHash.isPresent()) {
            if (!priorHash.get().equals(requestHash)) throw conflict("Idempotency-Key was reused for different payout-profile facts");
            return toResponse(requireProfile(sellerId));
        }
        SellerPayoutProfile profile = requireProfile(sellerId);
        if (profile.profileVersion() != request.profileVersion()) {
            throw conflict("Payout profile changed; review the latest version");
        }
        if (profile.state() == SellerPayoutProfile.State.ACTIVE) return toResponse(profile);
        if (profile.state() != SellerPayoutProfile.State.PENDING_REVIEW) {
            throw conflict("Only a payout profile pending review can be verified");
        }
        requireSupportedCountry(profile.countryCode());
        VerifiedSellerAccount account = accounts.findVerifiedSellerAccount(profile.recipientAccountId(), sellerId)
                .orElseThrow(() -> conflict("The linked Seller account is not active or email-verified"));
        Instant now = clock.instant();
        Optional<SellerPayoutProfile> verifiedProfile =
                profiles.verify(sellerId, request.profileVersion(), actor, now);
        if (verifiedProfile.isEmpty()) {
            Optional<String> concurrentHash = profiles.findRequestHash(sellerId, idempotencyKey);
            if (concurrentHash.isPresent() && concurrentHash.get().equals(requestHash)) {
                return toResponse(requireProfile(sellerId));
            }
            throw conflict("Payout profile changed during verification");
        }
        SellerPayoutProfile verified = verifiedProfile.get();
        if (!verified.recipientAccountId().equals(account.accountId())) {
            throw new IllegalStateException("Verified payout account mapping changed");
        }
        if (!profiles.recordAction(sellerId, verified.profileVersion(), "PROFILE_VERIFIED", idempotencyKey,
                requestHash, actor, request.evidenceReference(), now)) {
            String existingHash = profiles.findRequestHash(sellerId, idempotencyKey).orElseThrow(
                    () -> new IllegalStateException("Payout profile verification action disappeared"));
            if (!existingHash.equals(requestHash)) throw conflict("Idempotency-Key was reused for different payout-profile facts");
            return toResponse(requireProfile(sellerId));
        }
        return toResponse(verified);
    }

    @Transactional(readOnly = true)
    public SellerPayoutProfileResponse find(UUID accountId, UUID sellerId) {
        if (accountId == null || sellerId == null) throw invalid("Account and Seller are required");
        if (!accounts.ownsSeller(accountId, sellerId)) {
            throw notFound("Seller payout profile not found");
        }
        return toResponse(requireProfile(sellerId));
    }

    @Transactional(readOnly = true)
    public ActiveRecipient requireActiveRecipient(UUID sellerId) {
        SellerPayoutProfile profile = requireProfile(sellerId);
        if (profile.state() != SellerPayoutProfile.State.ACTIVE) {
            throw conflict("Seller payout profile is not active");
        }
        requireSupportedCountry(profile.countryCode());
        VerifiedSellerAccount account = accounts.findVerifiedSellerAccount(profile.recipientAccountId(), sellerId)
                .orElseThrow(() -> conflict("The linked Seller account is not active or email-verified"));
        return new ActiveRecipient(sellerId, profile.recipientAccountId(), profile.profileVersion(),
                profile.countryCode(), profile.payoutCurrency(), account.email());
    }

    private SellerPayoutProfile requireProfile(UUID sellerId) {
        if (sellerId == null) throw invalid("Seller is required");
        return profiles.find(sellerId).orElseThrow(() -> notFound("Seller payout profile not found"));
    }

    private void requireSupportedCountry(String countryCode) {
        Set<String> supported = payoutsProperties.getSupportedCountries().stream()
                .map(country -> country.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        if (!supported.contains(countryCode)) {
            throw conflict("PayPal Payouts is not enabled for the Seller recipient country");
        }
    }

    private static String normalizeCountry(String countryCode) {
        if (countryCode == null || !countryCode.matches("[A-Za-z]{2}")) {
            throw invalid("Country code must contain two letters");
        }
        return countryCode.toUpperCase(Locale.ROOT);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static SellerPayoutProfileResponse toResponse(SellerPayoutProfile profile) {
        return new SellerPayoutProfileResponse(profile.sellerId(), profile.countryCode(), profile.payoutCurrency(),
                profile.state().name(), profile.profileVersion(), profile.updatedAt(), profile.verifiedAt());
    }

    private static SettlementException invalid(String message) {
        return new SettlementException(SettlementException.Code.INVALID_REQUEST, message);
    }

    private static SettlementException conflict(String message) {
        return new SettlementException(SettlementException.Code.CONFLICT, message);
    }

    private static SettlementException notFound(String message) {
        return new SettlementException(SettlementException.Code.NOT_FOUND, message);
    }

    public record ActiveRecipient(UUID sellerId, UUID accountId, int profileVersion,
            String countryCode, String currency, String verifiedEmail) {
        @Override
        public String toString() {
            return "ActiveRecipient[sellerId=" + sellerId + ", accountId=" + accountId
                    + ", profileVersion=" + profileVersion + ", verifiedEmail=[REDACTED]]";
        }
    }
}
