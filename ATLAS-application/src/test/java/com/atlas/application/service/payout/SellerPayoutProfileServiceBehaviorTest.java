package com.atlas.application.service.payout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.atlas.account.dto.response.VerifiedSellerAccountResponse;
import com.atlas.account.service.AccountService;
import com.atlas.application.controller.dto.SellerPayoutProfileRequest;
import com.atlas.application.controller.dto.SellerPayoutProfileResponse;
import com.atlas.application.controller.dto.VerifySellerPayoutProfileRequest;
import com.atlas.application.service.payout.SellerPayoutProfileService.ActiveRecipient;
import com.atlas.payment.provider.paypal.PayPalPayoutsProperties;
import com.atlas.settlement.exception.SettlementException;
import com.atlas.settlement.service.SellerPayoutProfile;
import com.atlas.settlement.service.SellerPayoutProfile.State;
import com.atlas.settlement.service.SellerPayoutProfileStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SellerPayoutProfileServiceBehaviorTest {
    private static final Instant NOW = Instant.parse("2026-11-22T00:00:00Z");
    private static final String KEY = "idem-1";
    private static final String EMAIL = "seller@example.test";
    private static final String CONFIRMATION_REASON = "seller_confirmed_verified_email_and_usd_wallet";
    private final SellerPayoutProfileStore profiles = mock(SellerPayoutProfileStore.class);
    private final AccountService accounts = mock(AccountService.class);
    private final PayPalPayoutsProperties properties = new PayPalPayoutsProperties();
    private final SellerPayoutProfileService service = new SellerPayoutProfileService(
            profiles, accounts, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID accountId = UUID.randomUUID();
    private final UUID sellerId = UUID.randomUUID();

    @Test
    void configureSubmitsNewProfileForNormalizedCountryAndRecordsSubmittedAction() {
        givenVerifiedAccount();
        SellerPayoutProfile submitted = profile(State.PENDING_REVIEW, 1, accountId, "US");
        when(profiles.find(sellerId)).thenReturn(Optional.empty());
        when(profiles.submit(sellerId, accountId, "US", NOW)).thenReturn(submitted);
        when(profiles.recordAction(sellerId, 1, "PROFILE_SUBMITTED", KEY, configureHash("US"),
                accountId.toString(), CONFIRMATION_REASON, NOW)).thenReturn(true);

        SellerPayoutProfileResponse response = service.configure(accountId, sellerId, KEY, request("us"));

        assertThat(response.sellerId()).isEqualTo(sellerId);
        assertThat(response.countryCode()).isEqualTo("US");
        assertThat(response.payoutCurrency()).isEqualTo("USD");
        assertThat(response.status()).isEqualTo("PENDING_REVIEW");
        assertThat(response.profileVersion()).isEqualTo(1);
        assertThat(response.verifiedAt()).isNull();
        verify(profiles).recordAction(sellerId, 1, "PROFILE_SUBMITTED", KEY, configureHash("US"),
                accountId.toString(), CONFIRMATION_REASON, NOW);
    }

    @Test
    void configureWithUnchangedFactsConfirmsExistingProfileWithoutResubmitting() {
        givenVerifiedAccount();
        SellerPayoutProfile active = profile(State.ACTIVE, 3, accountId, "US");
        when(profiles.find(sellerId)).thenReturn(Optional.of(active));
        when(profiles.recordAction(any(), anyInt(), anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(true);

        SellerPayoutProfileResponse response = service.configure(accountId, sellerId, KEY, request("US"));

        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.profileVersion()).isEqualTo(3);
        verify(profiles, never()).submit(any(), any(), anyString(), any());
        verify(profiles).recordAction(sellerId, 3, "PROFILE_CONFIRMED", KEY, configureHash("US"),
                accountId.toString(), CONFIRMATION_REASON, NOW);
    }

    @Test
    void configureWithDifferentCountryFromExistingProfileSubmitsANewVersion() {
        properties.setSupportedCountries(Set.of("US", "ca"));
        givenVerifiedAccount();
        SellerPayoutProfile active = profile(State.ACTIVE, 3, accountId, "US");
        SellerPayoutProfile resubmitted = profile(State.PENDING_REVIEW, 4, accountId, "CA");
        when(profiles.find(sellerId)).thenReturn(Optional.of(active));
        when(profiles.submit(sellerId, accountId, "CA", NOW)).thenReturn(resubmitted);
        when(profiles.recordAction(any(), anyInt(), anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(true);

        SellerPayoutProfileResponse response = service.configure(accountId, sellerId, KEY, request("ca"));

        assertThat(response.status()).isEqualTo("PENDING_REVIEW");
        assertThat(response.countryCode()).isEqualTo("CA");
        assertThat(response.profileVersion()).isEqualTo(4);
        verify(profiles).recordAction(sellerId, 4, "PROFILE_SUBMITTED", KEY, configureHash("CA"),
                accountId.toString(), CONFIRMATION_REASON, NOW);
    }

    @Test
    void configureWithDifferentAccountFromExistingProfileSubmitsANewVersion() {
        givenVerifiedAccount();
        SellerPayoutProfile otherAccount = profile(State.ACTIVE, 2, UUID.randomUUID(), "US");
        SellerPayoutProfile resubmitted = profile(State.PENDING_REVIEW, 3, accountId, "US");
        when(profiles.find(sellerId)).thenReturn(Optional.of(otherAccount));
        when(profiles.submit(sellerId, accountId, "US", NOW)).thenReturn(resubmitted);
        when(profiles.recordAction(any(), anyInt(), anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(true);

        SellerPayoutProfileResponse response = service.configure(accountId, sellerId, KEY, request("US"));

        assertThat(response.status()).isEqualTo("PENDING_REVIEW");
        verify(profiles).submit(sellerId, accountId, "US", NOW);
    }

    @Test
    void configureRefusesToReactivateASuspendedProfileWithUnchangedFacts() {
        givenVerifiedAccount();
        when(profiles.find(sellerId)).thenReturn(Optional.of(profile(State.SUSPENDED, 2, accountId, "US")));

        assertCode(() -> service.configure(accountId, sellerId, KEY, request("US")),
                SettlementException.Code.CONFLICT);
        verify(profiles, never()).submit(any(), any(), anyString(), any());
        verify(profiles, never()).recordAction(any(), anyInt(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any());
    }

    @Test
    void configureReplayWithSameIdempotencyKeyAndFactsReturnsStoredProfileWithoutWriting() {
        givenVerifiedAccount();
        when(profiles.findRequestHash(sellerId, KEY)).thenReturn(Optional.of(configureHash("US")));
        when(profiles.find(sellerId)).thenReturn(Optional.of(profile(State.PENDING_REVIEW, 1, accountId, "US")));

        SellerPayoutProfileResponse response = service.configure(accountId, sellerId, KEY, request("US"));

        assertThat(response.status()).isEqualTo("PENDING_REVIEW");
        verify(profiles, never()).submit(any(), any(), anyString(), any());
        verify(profiles, never()).recordAction(any(), anyInt(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any());
    }

    @Test
    void configureReplayWithSameKeyButDifferentFactsIsRejectedAsConflict() {
        properties.setSupportedCountries(Set.of("US", "CA"));
        givenVerifiedAccount();
        when(profiles.findRequestHash(sellerId, KEY)).thenReturn(Optional.of(configureHash("US")));

        assertCode(() -> service.configure(accountId, sellerId, KEY, request("CA")),
                SettlementException.Code.CONFLICT);
        verify(profiles, never()).submit(any(), any(), anyString(), any());
        verify(profiles, never()).find(any());
    }

    @Test
    void configureLosingTheActionRaceWithSameHashReturnsTheStoredProfile() {
        givenVerifiedAccount();
        SellerPayoutProfile pending = profile(State.PENDING_REVIEW, 1, accountId, "US");
        when(profiles.findRequestHash(sellerId, KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(configureHash("US")));
        when(profiles.find(sellerId)).thenReturn(Optional.empty())
                .thenReturn(Optional.of(pending));
        when(profiles.submit(sellerId, accountId, "US", NOW)).thenReturn(pending);
        when(profiles.recordAction(any(), anyInt(), anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(false);

        SellerPayoutProfileResponse response = service.configure(accountId, sellerId, KEY, request("US"));

        assertThat(response.profileVersion()).isEqualTo(1);
        assertThat(response.status()).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void configureLosingTheActionRaceWithDifferentHashIsRejectedAsConflict() {
        givenVerifiedAccount();
        SellerPayoutProfile pending = profile(State.PENDING_REVIEW, 1, accountId, "US");
        when(profiles.findRequestHash(sellerId, KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(configureHash("CA")));
        when(profiles.find(sellerId)).thenReturn(Optional.empty());
        when(profiles.submit(sellerId, accountId, "US", NOW)).thenReturn(pending);
        when(profiles.recordAction(any(), anyInt(), anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(false);

        assertCode(() -> service.configure(accountId, sellerId, KEY, request("US")),
                SettlementException.Code.CONFLICT);
    }

    @Test
    void configureFailsLoudlyWhenTheIdempotencyActionVanishesAfterAConflictingInsert() {
        givenVerifiedAccount();
        SellerPayoutProfile pending = profile(State.PENDING_REVIEW, 1, accountId, "US");
        when(profiles.find(sellerId)).thenReturn(Optional.empty());
        when(profiles.submit(sellerId, accountId, "US", NOW)).thenReturn(pending);
        when(profiles.recordAction(any(), anyInt(), anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.configure(accountId, sellerId, KEY, request("US")))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void configureRejectsBlankIdempotencyKey(String key) {
        assertCode(() -> service.configure(accountId, sellerId, key, request("US")),
                SettlementException.Code.INVALID_REQUEST);
        verifyNoInteractions(profiles, accounts);
    }

    @Test
    void configureRejectsIdempotencyKeyLongerThan128CharactersButAcceptsExactly128() {
        givenVerifiedAccount();
        SellerPayoutProfile pending = profile(State.PENDING_REVIEW, 1, accountId, "US");
        when(profiles.find(sellerId)).thenReturn(Optional.empty());
        when(profiles.submit(sellerId, accountId, "US", NOW)).thenReturn(pending);
        when(profiles.recordAction(any(), anyInt(), anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(true);

        assertCode(() -> service.configure(accountId, sellerId, "k".repeat(129), request("US")),
                SettlementException.Code.INVALID_REQUEST);
        assertThat(service.configure(accountId, sellerId, "k".repeat(128), request("US")).status())
                .isEqualTo("PENDING_REVIEW");
    }

    @Test
    void configureRejectsMissingIdentifiersAndRequest() {
        assertCode(() -> service.configure(null, sellerId, KEY, request("US")), SettlementException.Code.INVALID_REQUEST);
        assertCode(() -> service.configure(accountId, null, KEY, request("US")), SettlementException.Code.INVALID_REQUEST);
        assertCode(() -> service.configure(accountId, sellerId, KEY, null), SettlementException.Code.INVALID_REQUEST);
        verifyNoInteractions(profiles, accounts);
    }

    @Test
    void configureRequiresBothUsdWalletAndVerifiedEmailConfirmations() {
        assertCode(() -> service.configure(accountId, sellerId, KEY, new SellerPayoutProfileRequest("US", false, true)),
                SettlementException.Code.INVALID_REQUEST);
        assertCode(() -> service.configure(accountId, sellerId, KEY, new SellerPayoutProfileRequest("US", true, false)),
                SettlementException.Code.INVALID_REQUEST);
        verifyNoInteractions(profiles, accounts);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"U", "USA", "U1", "한국"})
    void configureRejectsCountryCodeThatIsNotTwoLetters(String country) {
        assertCode(() -> service.configure(accountId, sellerId, KEY, request(country)),
                SettlementException.Code.INVALID_REQUEST);
        verifyNoInteractions(profiles, accounts);
    }

    @Test
    void configureRejectsCountryOutsideTheAllowlistBeforeLookingUpTheAccount() {
        assertCode(() -> service.configure(accountId, sellerId, KEY, request("KR")),
                SettlementException.Code.CONFLICT);
        verifyNoInteractions(profiles, accounts);
    }

    @Test
    void configureRejectsSellerAccountThatIsNotVerifiedAndWritesNothing() {
        when(accounts.findVerifiedSellerAccount(accountId, sellerId)).thenReturn(Optional.empty());

        assertCode(() -> service.configure(accountId, sellerId, KEY, request("US")),
                SettlementException.Code.CONFLICT);
        verifyNoInteractions(profiles);
    }

    @Test
    void verifyActivatesPendingProfileAndRecordsOperatorEvidence() {
        givenVerifiedAccount();
        SellerPayoutProfile active = profile(State.ACTIVE, 1, accountId, "US");
        when(profiles.find(sellerId)).thenReturn(Optional.of(profile(State.PENDING_REVIEW, 1, accountId, "US")));
        when(profiles.verify(sellerId, 1, "operator-1", NOW)).thenReturn(Optional.of(active));
        when(profiles.recordAction(sellerId, 1, "PROFILE_VERIFIED", KEY, verifyHash(1, "case-2026/11"),
                "operator-1", "case-2026/11", NOW)).thenReturn(true);

        SellerPayoutProfileResponse response = service.verify(sellerId, "operator-1", KEY, verifyRequest(1, "case-2026/11"));

        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.verifiedAt()).isEqualTo(NOW);
        verify(profiles).recordAction(sellerId, 1, "PROFILE_VERIFIED", KEY, verifyHash(1, "case-2026/11"),
                "operator-1", "case-2026/11", NOW);
    }

    @Test
    void verifyOfAnAlreadyActiveProfileIsANoOpThatReturnsTheProfile() {
        when(profiles.find(sellerId)).thenReturn(Optional.of(profile(State.ACTIVE, 2, accountId, "US")));

        SellerPayoutProfileResponse response = service.verify(sellerId, "operator-1", KEY, verifyRequest(2, "case-1"));

        assertThat(response.status()).isEqualTo("ACTIVE");
        verify(profiles, never()).verify(any(), anyInt(), anyString(), any());
        verify(profiles, never()).recordAction(any(), anyInt(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any());
        verifyNoInteractions(accounts);
    }

    @Test
    void verifyWithStaleProfileVersionIsRejectedAsConflict() {
        when(profiles.find(sellerId)).thenReturn(Optional.of(profile(State.PENDING_REVIEW, 2, accountId, "US")));

        assertCode(() -> service.verify(sellerId, "operator-1", KEY, verifyRequest(1, "case-1")),
                SettlementException.Code.CONFLICT);
        verify(profiles, never()).verify(any(), anyInt(), anyString(), any());
    }

    @Test
    void verifyOfASuspendedProfileIsRejectedAsConflict() {
        when(profiles.find(sellerId)).thenReturn(Optional.of(profile(State.SUSPENDED, 1, accountId, "US")));

        assertCode(() -> service.verify(sellerId, "operator-1", KEY, verifyRequest(1, "case-1")),
                SettlementException.Code.CONFLICT);
        verify(profiles, never()).verify(any(), anyInt(), anyString(), any());
    }

    @Test
    void verifyOfAnUnknownSellerIsNotFound() {
        when(profiles.find(sellerId)).thenReturn(Optional.empty());

        assertCode(() -> service.verify(sellerId, "operator-1", KEY, verifyRequest(1, "case-1")),
                SettlementException.Code.NOT_FOUND);
    }

    @Test
    void verifyReplayWithSameHashReturnsStoredProfileWithoutVerifyingAgain() {
        when(profiles.findRequestHash(sellerId, KEY)).thenReturn(Optional.of(verifyHash(1, "case-1")));
        when(profiles.find(sellerId)).thenReturn(Optional.of(profile(State.ACTIVE, 1, accountId, "US")));

        SellerPayoutProfileResponse response = service.verify(sellerId, "operator-1", KEY, verifyRequest(1, "case-1"));

        assertThat(response.status()).isEqualTo("ACTIVE");
        verify(profiles, never()).verify(any(), anyInt(), anyString(), any());
    }

    @Test
    void verifyReplayWithSameKeyButDifferentEvidenceIsRejectedAsConflict() {
        when(profiles.findRequestHash(sellerId, KEY)).thenReturn(Optional.of(verifyHash(1, "case-1")));

        assertCode(() -> service.verify(sellerId, "operator-1", KEY, verifyRequest(1, "case-2")),
                SettlementException.Code.CONFLICT);
        verify(profiles, never()).verify(any(), anyInt(), anyString(), any());
    }

    @Test
    void verifyLosingTheStateRaceToTheSameRequestReturnsTheStoredProfile() {
        givenVerifiedAccount();
        when(profiles.findRequestHash(sellerId, KEY)).thenReturn(Optional.empty())
                .thenReturn(Optional.of(verifyHash(1, "case-1")));
        when(profiles.find(sellerId)).thenReturn(Optional.of(profile(State.PENDING_REVIEW, 1, accountId, "US")))
                .thenReturn(Optional.of(profile(State.ACTIVE, 1, accountId, "US")));
        when(profiles.verify(sellerId, 1, "operator-1", NOW)).thenReturn(Optional.empty());

        SellerPayoutProfileResponse response = service.verify(sellerId, "operator-1", KEY, verifyRequest(1, "case-1"));

        assertThat(response.status()).isEqualTo("ACTIVE");
        verify(profiles, never()).recordAction(any(), anyInt(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any());
    }

    @Test
    void verifyLosingTheStateRaceToADifferentRequestIsRejectedAsConflict() {
        givenVerifiedAccount();
        when(profiles.find(sellerId)).thenReturn(Optional.of(profile(State.PENDING_REVIEW, 1, accountId, "US")));
        when(profiles.verify(sellerId, 1, "operator-1", NOW)).thenReturn(Optional.empty());

        assertCode(() -> service.verify(sellerId, "operator-1", KEY, verifyRequest(1, "case-1")),
                SettlementException.Code.CONFLICT);
        verify(profiles, never()).recordAction(any(), anyInt(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any());
    }

    @Test
    void verifyFailsLoudlyWhenVerifiedProfilePointsAtADifferentPayoutAccount() {
        givenVerifiedAccount();
        when(profiles.find(sellerId)).thenReturn(Optional.of(profile(State.PENDING_REVIEW, 1, accountId, "US")));
        when(profiles.verify(sellerId, 1, "operator-1", NOW))
                .thenReturn(Optional.of(profile(State.ACTIVE, 1, UUID.randomUUID(), "US")));

        assertThatThrownBy(() -> service.verify(sellerId, "operator-1", KEY, verifyRequest(1, "case-1")))
                .isInstanceOf(IllegalStateException.class);
        verify(profiles, never()).recordAction(any(), anyInt(), anyString(), anyString(), anyString(), anyString(),
                anyString(), any());
    }

    @Test
    void verifyLosingTheActionRaceWithSameHashReturnsTheStoredProfile() {
        givenVerifiedAccount();
        SellerPayoutProfile active = profile(State.ACTIVE, 1, accountId, "US");
        when(profiles.findRequestHash(sellerId, KEY)).thenReturn(Optional.empty())
                .thenReturn(Optional.of(verifyHash(1, "case-1")));
        when(profiles.find(sellerId)).thenReturn(Optional.of(profile(State.PENDING_REVIEW, 1, accountId, "US")))
                .thenReturn(Optional.of(active));
        when(profiles.verify(sellerId, 1, "operator-1", NOW)).thenReturn(Optional.of(active));
        when(profiles.recordAction(any(), anyInt(), anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(false);

        SellerPayoutProfileResponse response = service.verify(sellerId, "operator-1", KEY, verifyRequest(1, "case-1"));

        assertThat(response.status()).isEqualTo("ACTIVE");
    }

    @Test
    void verifyLosingTheActionRaceWithDifferentHashIsRejectedAsConflict() {
        givenVerifiedAccount();
        SellerPayoutProfile active = profile(State.ACTIVE, 1, accountId, "US");
        when(profiles.findRequestHash(sellerId, KEY)).thenReturn(Optional.empty())
                .thenReturn(Optional.of(verifyHash(1, "other")));
        when(profiles.find(sellerId)).thenReturn(Optional.of(profile(State.PENDING_REVIEW, 1, accountId, "US")));
        when(profiles.verify(sellerId, 1, "operator-1", NOW)).thenReturn(Optional.of(active));
        when(profiles.recordAction(any(), anyInt(), anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(false);

        assertCode(() -> service.verify(sellerId, "operator-1", KEY, verifyRequest(1, "case-1")),
                SettlementException.Code.CONFLICT);
    }

    @Test
    void verifyRejectsProfileWhoseCountryIsNoLongerAllowedWithoutActivating() {
        properties.setSupportedCountries(Set.of("US"));
        when(profiles.find(sellerId)).thenReturn(Optional.of(profile(State.PENDING_REVIEW, 1, accountId, "CA")));

        assertCode(() -> service.verify(sellerId, "operator-1", KEY, verifyRequest(1, "case-1")),
                SettlementException.Code.CONFLICT);
        verify(profiles, never()).verify(any(), anyInt(), anyString(), any());
    }

    @Test
    void verifyRejectsProfileWhoseLinkedAccountIsNoLongerVerifiedWithoutActivating() {
        when(profiles.find(sellerId)).thenReturn(Optional.of(profile(State.PENDING_REVIEW, 1, accountId, "US")));
        when(accounts.findVerifiedSellerAccount(accountId, sellerId)).thenReturn(Optional.empty());

        assertCode(() -> service.verify(sellerId, "operator-1", KEY, verifyRequest(1, "case-1")),
                SettlementException.Code.CONFLICT);
        verify(profiles, never()).verify(any(), anyInt(), anyString(), any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void verifyRejectsBlankOperatorOrIdempotencyKey(String blank) {
        assertCode(() -> service.verify(sellerId, blank, KEY, verifyRequest(1, "case-1")),
                SettlementException.Code.INVALID_REQUEST);
        assertCode(() -> service.verify(sellerId, "operator-1", blank, verifyRequest(1, "case-1")),
                SettlementException.Code.INVALID_REQUEST);
        verifyNoInteractions(profiles, accounts);
    }

    @Test
    void verifyRejectsOversizedOperatorAndIdempotencyKey() {
        assertCode(() -> service.verify(sellerId, "o".repeat(129), KEY, verifyRequest(1, "case-1")),
                SettlementException.Code.INVALID_REQUEST);
        assertCode(() -> service.verify(sellerId, "operator-1", "k".repeat(129), verifyRequest(1, "case-1")),
                SettlementException.Code.INVALID_REQUEST);
        verifyNoInteractions(profiles, accounts);
    }

    @Test
    void verifyRejectsMissingSellerRequestOrNonPositiveProfileVersion() {
        assertCode(() -> service.verify(null, "operator-1", KEY, verifyRequest(1, "case-1")),
                SettlementException.Code.INVALID_REQUEST);
        assertCode(() -> service.verify(sellerId, "operator-1", KEY, null), SettlementException.Code.INVALID_REQUEST);
        assertCode(() -> service.verify(sellerId, "operator-1", KEY, verifyRequest(0, "case-1")),
                SettlementException.Code.INVALID_REQUEST);
        verifyNoInteractions(profiles, accounts);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "-leading-dash", "has space", "semi;colon", "한글-증빙"})
    void verifyRejectsMalformedEvidenceReference(String evidence) {
        assertCode(() -> service.verify(sellerId, "operator-1", KEY, verifyRequest(1, evidence)),
                SettlementException.Code.INVALID_REQUEST);
        verifyNoInteractions(profiles, accounts);
    }

    @Test
    void verifyRejectsEvidenceReferenceLongerThan500Characters() {
        assertCode(() -> service.verify(sellerId, "operator-1", KEY, verifyRequest(1, "e".repeat(501))),
                SettlementException.Code.INVALID_REQUEST);
        verifyNoInteractions(profiles, accounts);
    }

    @Test
    void findReturnsProfileForTheOwningAccount() {
        when(accounts.ownsSeller(accountId, sellerId)).thenReturn(true);
        when(profiles.find(sellerId)).thenReturn(Optional.of(profile(State.ACTIVE, 5, accountId, "US")));

        SellerPayoutProfileResponse response = service.find(accountId, sellerId);

        assertThat(response.sellerId()).isEqualTo(sellerId);
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.profileVersion()).isEqualTo(5);
    }

    @Test
    void findIsNotFoundWhenOwningAccountHasNoProfileYet() {
        when(accounts.ownsSeller(accountId, sellerId)).thenReturn(true);
        when(profiles.find(sellerId)).thenReturn(Optional.empty());

        assertCode(() -> service.find(accountId, sellerId), SettlementException.Code.NOT_FOUND);
    }

    @Test
    void findRejectsMissingAccountOrSeller() {
        assertCode(() -> service.find(null, sellerId), SettlementException.Code.INVALID_REQUEST);
        assertCode(() -> service.find(accountId, null), SettlementException.Code.INVALID_REQUEST);
        verifyNoInteractions(profiles, accounts);
    }

    @Test
    void requireActiveRecipientReturnsVerifiedEmailVersionAndCountryOfTheActiveProfile() {
        givenVerifiedAccount();
        when(profiles.find(sellerId)).thenReturn(Optional.of(profile(State.ACTIVE, 7, accountId, "US")));

        ActiveRecipient recipient = service.requireActiveRecipient(sellerId);

        assertThat(recipient.sellerId()).isEqualTo(sellerId);
        assertThat(recipient.accountId()).isEqualTo(accountId);
        assertThat(recipient.profileVersion()).isEqualTo(7);
        assertThat(recipient.countryCode()).isEqualTo("US");
        assertThat(recipient.currency()).isEqualTo("USD");
        assertThat(recipient.verifiedEmail()).isEqualTo(EMAIL);
        assertThat(recipient.toString()).doesNotContain(EMAIL).contains("[REDACTED]");
    }

    @Test
    void requireActiveRecipientReflectsProfileVersionChanges() {
        givenVerifiedAccount();
        when(profiles.find(sellerId)).thenReturn(Optional.of(profile(State.ACTIVE, 7, accountId, "US")))
                .thenReturn(Optional.of(profile(State.ACTIVE, 8, accountId, "US")));

        assertThat(service.requireActiveRecipient(sellerId).profileVersion()).isEqualTo(7);
        assertThat(service.requireActiveRecipient(sellerId).profileVersion()).isEqualTo(8);
    }

    @Test
    void requireActiveRecipientIsNotFoundWhenSellerHasNoProfile() {
        when(profiles.find(sellerId)).thenReturn(Optional.empty());

        assertCode(() -> service.requireActiveRecipient(sellerId), SettlementException.Code.NOT_FOUND);
        verifyNoInteractions(accounts);
    }

    @Test
    void requireActiveRecipientRejectsMissingSeller() {
        assertCode(() -> service.requireActiveRecipient(null), SettlementException.Code.INVALID_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING_REVIEW", "SUSPENDED"})
    void requireActiveRecipientRejectsProfilesThatAreNotActive(String state) {
        when(profiles.find(sellerId)).thenReturn(Optional.of(profile(State.valueOf(state), 1, accountId, "US")));

        assertCode(() -> service.requireActiveRecipient(sellerId), SettlementException.Code.CONFLICT);
        verifyNoInteractions(accounts);
    }

    @Test
    void requireActiveRecipientRejectsActiveProfileWhoseCountryLeftTheAllowlist() {
        properties.setSupportedCountries(Set.of("CA"));
        when(profiles.find(sellerId)).thenReturn(Optional.of(profile(State.ACTIVE, 1, accountId, "US")));

        assertCode(() -> service.requireActiveRecipient(sellerId), SettlementException.Code.CONFLICT);
        verifyNoInteractions(accounts);
    }

    @Test
    void requireActiveRecipientRejectsActiveProfileWhoseAccountEmailIsNoLongerVerified() {
        when(profiles.find(sellerId)).thenReturn(Optional.of(profile(State.ACTIVE, 1, accountId, "US")));
        when(accounts.findVerifiedSellerAccount(accountId, sellerId)).thenReturn(Optional.empty());

        assertCode(() -> service.requireActiveRecipient(sellerId), SettlementException.Code.CONFLICT);
    }

    private void givenVerifiedAccount() {
        when(accounts.findVerifiedSellerAccount(accountId, sellerId))
                .thenReturn(Optional.of(new VerifiedSellerAccountResponse(accountId, EMAIL)));
    }

    private SellerPayoutProfile profile(State state, int version, UUID recipientAccountId, String country) {
        boolean active = state == State.ACTIVE;
        return new SellerPayoutProfile(sellerId, recipientAccountId, country, "USD", state, version, NOW,
                active ? NOW : null, active ? "operator-1" : null);
    }

    private SellerPayoutProfileRequest request(String country) {
        return new SellerPayoutProfileRequest(country, true, true);
    }

    private VerifySellerPayoutProfileRequest verifyRequest(int version, String evidence) {
        return new VerifySellerPayoutProfileRequest(version, evidence);
    }

    private String configureHash(String country) {
        return sha256("profile|" + accountId + "|" + sellerId + "|" + country + "|USD");
    }

    private String verifyHash(int version, String evidence) {
        return sha256("verify|" + sellerId + "|" + version + "|" + evidence);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void assertCode(ThrowingCallable call, SettlementException.Code code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(SettlementException.class,
                exception -> assertThat(exception.code()).isEqualTo(code));
    }
}
