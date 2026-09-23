package pl.commercelink.receipts.dev;

import org.junit.jupiter.api.Test;
import pl.commercelink.receipts.api.FiscalData;
import pl.commercelink.receipts.api.Receipt;
import pl.commercelink.receipts.api.ReceiptException;
import pl.commercelink.receipts.api.ReceiptOutcomeUnknownException;
import pl.commercelink.receipts.api.ReceiptRejectedException;
import pl.commercelink.receipts.api.ReceiptState;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevReceiptBookTest {

    private static final String KEY = "order-1:R1";
    private static final String FIRST_ID = "dev-20260923T101500Z-000001";
    private static final String FIRST_URL = "https://receipts-dev.local/r/" + FIRST_ID;

    private final DevReceiptBook book = new DevReceiptBook(DevRequests.CLOCK);

    @Test
    void defaultIsFiscalisedAtOnceWithLinkAndFiscalData() {
        // when
        Receipt receipt = book.issue(KEY, DevReceiptScenario.DEFAULT);

        // then
        assertEquals(ReceiptState.FISCALISED, receipt.state());
        assertEquals(KEY, receipt.receiptKey());
        assertEquals(FIRST_ID, receipt.providerReceiptId());
        assertEquals(FIRST_URL, receipt.documentUrl());
        assertEquals(new FiscalData("DEV00000001", "20260923T101500Z-000001", Instant.parse("2026-09-23T10:15:00Z")),
                receipt.fiscal());
        assertEquals(receipt, book.find(KEY).orElseThrow());
        assertEquals(receipt, book.fetch(FIRST_ID));
    }

    @Test
    void idsCarryTheBootStampAndASequence() {
        // when
        Receipt first = book.issue("a:R1", DevReceiptScenario.DEFAULT);
        Receipt second = book.issue("b:R1", DevReceiptScenario.DEFAULT);

        // then
        assertEquals("20260923T101500Z", book.bootStamp());
        assertEquals("dev-20260923T101500Z-000001", first.providerReceiptId());
        assertEquals("dev-20260923T101500Z-000002", second.providerReceiptId());
        assertEquals("20260923T101500Z-000002", second.fiscal().receiptNumber());
    }

    @Test
    void retryWithSameKeyReturnsTheSameReceiptWithoutCreatingAnother() {
        // given
        Receipt first = book.issue(KEY, DevReceiptScenario.DEFAULT);

        // when
        Receipt second = book.issue(KEY, DevReceiptScenario.DEFAULT);

        // then
        assertEquals(first, second);
        assertEquals(1, book.createCalls());
        assertEquals(2, book.remoteCalls());
    }

    @Test
    void pendingIsFiscalisedOnTheSecondFetch() {
        // given
        Receipt issued = book.issue(KEY, DevReceiptScenario.PENDING);

        // when
        Receipt foundBeforeFetch = book.find(KEY).orElseThrow();
        Receipt firstFetch = book.fetch(issued.providerReceiptId());
        Receipt secondFetch = book.fetch(issued.providerReceiptId());

        // then
        assertEquals(ReceiptState.PENDING, issued.state());
        assertEquals(ReceiptState.PENDING, foundBeforeFetch.state());
        assertEquals(ReceiptState.PENDING, firstFetch.state());
        assertEquals(ReceiptState.FISCALISED, secondFetch.state());
        assertEquals(FIRST_URL, secondFetch.documentUrl());
    }

    @Test
    void failReportsThePrinterRefusalOnTheFirstFetch() {
        // given
        Receipt issued = book.issue(KEY, DevReceiptScenario.FAIL);

        // when
        Receipt fetched = book.fetch(issued.providerReceiptId());

        // then
        assertEquals(ReceiptState.PENDING, issued.state());
        assertEquals(ReceiptState.FAILED, fetched.state());
        assertEquals("fiscal_error", fetched.failure().code());
        assertEquals("Niepoprawna wartość brutto na pozycji 1", fetched.failure().message());
        assertEquals(ReceiptState.FAILED, book.find(KEY).orElseThrow().state());
    }

    @Test
    void retryOfAFailedKeyIsRejected() {
        // given
        Receipt issued = book.issue(KEY, DevReceiptScenario.FAIL);
        book.fetch(issued.providerReceiptId());

        // when / then
        ReceiptRejectedException e = assertThrows(ReceiptRejectedException.class,
                () -> book.issue(KEY, DevReceiptScenario.FAIL));
        assertEquals("fiscal_error", e.code());
        assertEquals(1, book.createCalls());
    }

    @Test
    void rejectRefusesEveryAttemptAndStoresNothing() {
        // when / then
        ReceiptRejectedException first = assertThrows(ReceiptRejectedException.class,
                () -> book.issue(KEY, DevReceiptScenario.REJECT));
        assertThrows(ReceiptRejectedException.class, () -> book.issue(KEY, DevReceiptScenario.REJECT));
        assertThrows(ReceiptRejectedException.class, () -> book.issue("order-1:R2", DevReceiptScenario.REJECT));
        assertEquals("rejected", first.code());
        assertEquals(Optional.empty(), book.find(KEY));
        assertEquals(0, book.createCalls());
    }

    @Test
    void unknownStoresAFiscalisedReceiptAndLosesTheFirstResponse() {
        // when
        assertThrows(ReceiptOutcomeUnknownException.class, () -> book.issue(KEY, DevReceiptScenario.UNKNOWN));
        Optional<Receipt> found = book.find(KEY);
        Receipt retried = book.issue(KEY, DevReceiptScenario.UNKNOWN);

        // then
        assertEquals(ReceiptState.FISCALISED, found.orElseThrow().state());
        assertEquals(found.get(), retried);
        assertEquals(1, book.createCalls());
    }

    @Test
    void unknownUnorderedStaysPendingUntilIssueIsRetried() {
        // given
        assertThrows(ReceiptOutcomeUnknownException.class,
                () -> book.issue(KEY, DevReceiptScenario.UNKNOWN_UNORDERED));

        // when
        Receipt found = book.find(KEY).orElseThrow();
        Receipt polledOnce = book.fetch(found.providerReceiptId());
        Receipt polledAgain = book.fetch(found.providerReceiptId());
        Receipt polledThrice = book.fetch(found.providerReceiptId());

        // then
        assertEquals(ReceiptState.PENDING, found.state());
        assertEquals(ReceiptState.PENDING, polledOnce.state());
        assertEquals(ReceiptState.PENDING, polledAgain.state());
        assertEquals(ReceiptState.PENDING, polledThrice.state());
    }

    @Test
    void unknownUnorderedIsFiscalisedOnTheFetchAfterTheRetry() {
        // given
        assertThrows(ReceiptOutcomeUnknownException.class,
                () -> book.issue(KEY, DevReceiptScenario.UNKNOWN_UNORDERED));
        book.fetch(FIRST_ID);

        // when
        Receipt retried = book.issue(KEY, DevReceiptScenario.UNKNOWN_UNORDERED);
        Receipt fetched = book.fetch(FIRST_ID);

        // then
        assertEquals(ReceiptState.PENDING, retried.state());
        assertEquals(FIRST_ID, retried.providerReceiptId());
        assertEquals(ReceiptState.FISCALISED, fetched.state());
        assertEquals(FIRST_URL, fetched.documentUrl());
        assertEquals(1, book.createCalls());
    }

    @Test
    void nolinkGetsItsLinkOnTheFirstFetchKeepingFiscalData() {
        // given
        Receipt issued = book.issue(KEY, DevReceiptScenario.NOLINK);

        // when
        Receipt found = book.find(KEY).orElseThrow();
        Receipt fetched = book.fetch(issued.providerReceiptId());

        // then
        assertEquals(ReceiptState.FISCALISED, issued.state());
        assertNull(issued.documentUrl());
        assertNull(found.documentUrl());
        assertEquals(FIRST_URL, fetched.documentUrl());
        assertEquals(issued.fiscal(), fetched.fiscal());
    }

    @Test
    void nolinkNeverNeverGetsALinkFromFetch() {
        // given
        Receipt issued = book.issue(KEY, DevReceiptScenario.NOLINK_NEVER);

        // when
        book.fetch(issued.providerReceiptId());
        Receipt fetched = book.fetch(issued.providerReceiptId());

        // then
        assertEquals(ReceiptState.FISCALISED, fetched.state());
        assertNull(fetched.documentUrl());
    }

    @Test
    void stuckStaysPendingOnFetchAndRetry() {
        // given
        Receipt issued = book.issue(KEY, DevReceiptScenario.STUCK);

        // when
        for (int i = 0; i < 5; i++) {
            book.fetch(issued.providerReceiptId());
        }
        Receipt retried = book.issue(KEY, DevReceiptScenario.STUCK);

        // then
        assertEquals(ReceiptState.PENDING, book.fetch(issued.providerReceiptId()).state());
        assertEquals(ReceiptState.PENDING, retried.state());
    }

    @Test
    void unavailableFailsTheFirstAttemptAndThenBehavesAsDefault() {
        // when
        ReceiptException first = assertThrows(ReceiptException.class,
                () -> book.issue(KEY, DevReceiptScenario.UNAVAILABLE));
        Optional<Receipt> foundBetween = book.find(KEY);
        Receipt retried = book.issue(KEY, DevReceiptScenario.UNAVAILABLE);

        // then
        assertEquals(ReceiptException.class, first.getClass());
        assertEquals(Optional.empty(), foundBetween);
        assertEquals(ReceiptState.FISCALISED, retried.state());
        assertEquals(FIRST_URL, retried.documentUrl());
    }

    @Test
    void unavailableGivesEveryNewKeyAFreshFirstAttempt() {
        // given
        assertThrows(ReceiptException.class, () -> book.issue(KEY, DevReceiptScenario.UNAVAILABLE));
        book.issue(KEY, DevReceiptScenario.UNAVAILABLE);

        // when / then
        assertThrows(ReceiptException.class, () -> book.issue("order-1:R2", DevReceiptScenario.UNAVAILABLE));
    }

    @Test
    void fetchOfUnknownIdThrows() {
        // when / then
        ReceiptException e = assertThrows(ReceiptException.class, () -> book.fetch("dev-19990101T000000Z-000001"));
        assertTrue(e.getMessage().contains("restart"));
    }

    @Test
    void findDoesNotMovePendingForward() {
        // given
        Receipt issued = book.issue(KEY, DevReceiptScenario.PENDING);

        // when
        book.find(KEY);
        book.find(KEY);
        Receipt firstFetch = book.fetch(issued.providerReceiptId());

        // then
        assertEquals(ReceiptState.PENDING, firstFetch.state());
    }

    @Test
    void settleFiscalisesAStuckReceipt() {
        // given
        Receipt issued = book.issue(KEY, DevReceiptScenario.STUCK);

        // when
        Receipt settled = book.settle(issued.providerReceiptId(), DevReceiptBook.Event.FISCALISED).orElseThrow();

        // then
        assertEquals(ReceiptState.FISCALISED, settled.state());
        assertEquals(FIRST_URL, settled.documentUrl());
        assertEquals(settled, book.fetch(issued.providerReceiptId()));
    }

    @Test
    void settleFailsAPendingReceipt() {
        // given
        Receipt issued = book.issue(KEY, DevReceiptScenario.PENDING);

        // when
        Receipt settled = book.settle(issued.providerReceiptId(), DevReceiptBook.Event.FAILED).orElseThrow();

        // then
        assertEquals(ReceiptState.FAILED, settled.state());
        assertEquals(DevReceiptBook.PRINTER_ERROR, settled.failure());
    }

    @Test
    void settleDoesNotTouchAnUnorderedReceipt() {
        // given
        assertThrows(ReceiptOutcomeUnknownException.class,
                () -> book.issue(KEY, DevReceiptScenario.UNKNOWN_UNORDERED));

        // when
        Receipt settled = book.settle(FIRST_ID, DevReceiptBook.Event.FISCALISED).orElseThrow();

        // then
        assertEquals(ReceiptState.PENDING, settled.state());
    }

    @Test
    void settleLinkAddsTheLinkToAFiscalisedReceiptWithoutOne() {
        // given
        Receipt issued = book.issue(KEY, DevReceiptScenario.NOLINK_NEVER);

        // when
        Receipt settled = book.settle(issued.providerReceiptId(), DevReceiptBook.Event.LINK).orElseThrow();

        // then
        assertEquals(FIRST_URL, settled.documentUrl());
        assertEquals(issued.fiscal(), settled.fiscal());
    }

    @Test
    void settleNeverMovesATerminalReceiptBackOrSideways() {
        // given
        Receipt fiscalised = book.issue("a:R1", DevReceiptScenario.DEFAULT);
        Receipt failing = book.issue("b:R1", DevReceiptScenario.FAIL);
        book.fetch(failing.providerReceiptId());

        // when
        Receipt afterFail = book.settle(fiscalised.providerReceiptId(), DevReceiptBook.Event.FAILED).orElseThrow();
        Receipt afterFiscalise = book.settle(failing.providerReceiptId(), DevReceiptBook.Event.FISCALISED).orElseThrow();
        Receipt afterLink = book.settle(failing.providerReceiptId(), DevReceiptBook.Event.LINK).orElseThrow();

        // then
        assertEquals(fiscalised, afterFail);
        assertEquals(ReceiptState.FAILED, afterFiscalise.state());
        assertEquals(ReceiptState.FAILED, afterLink.state());
    }

    @Test
    void settleLinkOnAPendingReceiptChangesNothing() {
        // given
        Receipt issued = book.issue(KEY, DevReceiptScenario.STUCK);

        // when
        Receipt settled = book.settle(issued.providerReceiptId(), DevReceiptBook.Event.LINK).orElseThrow();

        // then
        assertEquals(issued, settled);
    }

    @Test
    void settleOfUnknownIdIsEmpty() {
        // when / then
        assertEquals(Optional.empty(), book.settle("nope", DevReceiptBook.Event.FISCALISED));
    }

    @Test
    void seededPendingIsSettledUnderTheGivenIdAndKey() {
        // given
        book.seedPending("tck-order-1:R1", "tck-provider-1");
        book.seedPending("tck-order-1:R1", "tck-provider-1");

        // when
        Receipt settled = book.settle("tck-provider-1", DevReceiptBook.Event.FISCALISED).orElseThrow();

        // then
        assertEquals("tck-order-1:R1", settled.receiptKey());
        assertEquals("tck-provider-1", settled.providerReceiptId());
        assertEquals(ReceiptState.FISCALISED, settled.state());
        assertEquals(0, book.createCalls());
    }

    @Test
    void scenarioIsKeptWithTheReceipt() {
        // given
        Receipt issued = book.issue(KEY, DevReceiptScenario.STUCK);

        // when
        Receipt retriedWithOtherScenario = book.issue(KEY, DevReceiptScenario.DEFAULT);

        // then
        assertEquals(ReceiptState.PENDING, retriedWithOtherScenario.state());
        assertEquals(issued.providerReceiptId(), retriedWithOtherScenario.providerReceiptId());
    }

    @Test
    void booksCreatedAtDifferentMomentsNeverShareIds() {
        // given
        DevReceiptBook afterRestart = new DevReceiptBook(java.time.Clock.fixed(
                Instant.parse("2026-09-23T10:15:01Z"), java.time.ZoneOffset.UTC));

        // when
        Receipt before = book.issue(KEY, DevReceiptScenario.DEFAULT);
        Receipt after = afterRestart.issue(KEY, DevReceiptScenario.DEFAULT);

        // then
        assertNotEquals(before.providerReceiptId(), after.providerReceiptId());
        assertNotEquals(before.fiscal().receiptNumber(), after.fiscal().receiptNumber());
    }
}
