package pl.commercelink.receipts.dev;

import org.junit.jupiter.api.Test;
import pl.commercelink.provider.api.WebhookContext;
import pl.commercelink.provider.api.WebhookOutcome;
import pl.commercelink.provider.api.WebhookStatusResponse;
import pl.commercelink.receipts.api.Receipt;
import pl.commercelink.receipts.api.ReceiptOutcomeUnknownException;
import pl.commercelink.receipts.api.ReceiptState;

import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DevReceiptWebhookExecutorTest {

    private static final String SECRET = "dev-secret";
    private static final Map<String, String> CONFIG = Map.of("webhookSecret", SECRET);

    private final DevReceiptBook book = new DevReceiptBook(DevRequests.CLOCK);
    private final DevReceiptWebhookExecutor executor = new DevReceiptWebhookExecutor(book);

    @Test
    void signedFiscalisedEventFiscalisesAStuckReceipt() {
        // given
        Receipt stuck = book.issue("o-1:R1", DevReceiptScenario.STUCK);

        // when
        WebhookOutcome<Receipt> outcome = execute(stuck.providerReceiptId() + " FISCALISED", CONFIG);

        // then
        assertEquals(ReceiptState.FISCALISED, outcome.result().state());
        assertEquals("o-1:R1", outcome.result().receiptKey());
        assertEquals(new WebhookStatusResponse("OK"), outcome.responseBody());
        assertEquals(ReceiptState.FISCALISED, book.fetch(stuck.providerReceiptId()).state());
    }

    @Test
    void signedFailedEventFailsAPendingReceipt() {
        // given
        Receipt pending = book.issue("o-1:R1", DevReceiptScenario.PENDING);

        // when
        WebhookOutcome<Receipt> outcome = execute(pending.providerReceiptId() + " FAILED", CONFIG);

        // then
        assertEquals(ReceiptState.FAILED, outcome.result().state());
        assertEquals("fiscal_error", outcome.result().failure().code());
    }

    @Test
    void signedLinkEventAddsTheMissingLink() {
        // given
        Receipt paper = book.issue("o-1:R1", DevReceiptScenario.NOLINK_NEVER);

        // when
        WebhookOutcome<Receipt> outcome = execute(paper.providerReceiptId() + " LINK", CONFIG);

        // then
        assertEquals("https://receipts-dev.local/r/" + paper.providerReceiptId(), outcome.result().documentUrl());
    }

    @Test
    void eventOnAnUnorderedReceiptReportsItStillPending() {
        // given
        assertThrows(ReceiptOutcomeUnknownException.class, () -> book.issue("o-1:R1", DevReceiptScenario.UNKNOWN_UNORDERED));
        String id = book.find("o-1:R1").orElseThrow().providerReceiptId();

        // when
        WebhookOutcome<Receipt> outcome = execute(id + " FISCALISED", CONFIG);

        // then
        assertEquals(ReceiptState.PENDING, outcome.result().state());
    }

    @Test
    void signatureInUpperCaseWithWhitespaceIsAccepted() {
        // given
        Receipt stuck = book.issue("o-1:R1", DevReceiptScenario.STUCK);
        String payload = stuck.providerReceiptId() + " FISCALISED";
        String signature = " " + DevReceiptWebhookExecutor.sign(payload, SECRET).toUpperCase(Locale.ROOT) + "\n";

        // when
        WebhookOutcome<Receipt> outcome = executor.execute(payload,
                new WebhookContext(Map.of("x-receipts-dev-signature", signature), CONFIG));

        // then
        assertEquals(ReceiptState.FISCALISED, outcome.result().state());
    }

    @Test
    void missingSignatureIsRejected() {
        // given
        Receipt stuck = book.issue("o-1:R1", DevReceiptScenario.STUCK);

        // when
        WebhookOutcome<Receipt> outcome = executor.execute(stuck.providerReceiptId() + " FISCALISED",
                new WebhookContext(Map.of(), CONFIG));

        // then
        assertRejected(outcome);
        assertEquals(ReceiptState.PENDING, book.fetch(stuck.providerReceiptId()).state());
    }

    @Test
    void signatureWithAnotherSecretIsRejected() {
        // given
        Receipt stuck = book.issue("o-1:R1", DevReceiptScenario.STUCK);
        String payload = stuck.providerReceiptId() + " FISCALISED";

        // when
        WebhookOutcome<Receipt> outcome = executor.execute(payload, new WebhookContext(
                Map.of(DevReceiptWebhookExecutor.SIGNATURE_HEADER, DevReceiptWebhookExecutor.sign(payload, "other")), CONFIG));

        // then
        assertRejected(outcome);
    }

    @Test
    void secretStoredWithTrailingWhitespaceIsStrippedBeforeVerifying() {
        // given
        Receipt stuck = book.issue("o-1:R1", DevReceiptScenario.STUCK);
        String payload = stuck.providerReceiptId() + " FISCALISED";
        String signature = DevReceiptWebhookExecutor.sign(payload, "s3cret");

        // when
        WebhookOutcome<Receipt> outcome = executor.execute(payload, new WebhookContext(
                Map.of(DevReceiptWebhookExecutor.SIGNATURE_HEADER, signature),
                Map.of("webhookSecret", "s3cret\n")));

        // then
        assertEquals(ReceiptState.FISCALISED, outcome.result().state());
    }

    @Test
    void blankSecretInConfigurationRejectsEveryCall() {
        // given
        Receipt stuck = book.issue("o-1:R1", DevReceiptScenario.STUCK);
        String payload = stuck.providerReceiptId() + " FISCALISED";

        // when
        WebhookOutcome<Receipt> blank = executor.execute(payload, new WebhookContext(
                Map.of(DevReceiptWebhookExecutor.SIGNATURE_HEADER, DevReceiptWebhookExecutor.sign(payload, " ")),
                Map.of("webhookSecret", " ")));
        WebhookOutcome<Receipt> absent = executor.execute(payload, new WebhookContext(
                Map.of(DevReceiptWebhookExecutor.SIGNATURE_HEADER, DevReceiptWebhookExecutor.sign(payload, "x")), Map.of()));

        // then
        assertRejected(blank);
        assertRejected(absent);
    }

    @Test
    void signedEventForAnUnknownIdYieldsNothing() {
        // when
        WebhookOutcome<Receipt> outcome = execute("dev-unknown-000001 FISCALISED", CONFIG);

        // then
        assertEquals(WebhookOutcome.empty(), outcome);
    }

    @Test
    void signedMalformedBodyYieldsNothing() {
        // given
        Receipt stuck = book.issue("o-1:R1", DevReceiptScenario.STUCK);

        // when / then
        assertEquals(WebhookOutcome.empty(), execute(stuck.providerReceiptId(), CONFIG));
        assertEquals(WebhookOutcome.empty(), execute(stuck.providerReceiptId() + " PRINTED", CONFIG));
        assertEquals(WebhookOutcome.empty(), execute(stuck.providerReceiptId() + " FISCALISED extra", CONFIG));
        assertEquals(WebhookOutcome.empty(), execute("", CONFIG));
    }

    @Test
    void nullPayloadIsRejectedWithoutThrowing() {
        // when
        WebhookOutcome<Receipt> outcome = executor.execute(null, new WebhookContext(Map.of(), CONFIG));

        // then
        assertRejected(outcome);
    }

    @Test
    void surroundingWhitespaceAndLowerCaseEventAreAccepted() {
        // given
        Receipt stuck = book.issue("o-1:R1", DevReceiptScenario.STUCK);

        // when
        WebhookOutcome<Receipt> outcome = execute("  " + stuck.providerReceiptId() + " fiscalised\n", CONFIG);

        // then
        assertEquals(ReceiptState.FISCALISED, outcome.result().state());
    }

    private WebhookOutcome<Receipt> execute(String payload, Map<String, String> config) {
        String signature = DevReceiptWebhookExecutor.sign(payload, SECRET);
        return executor.execute(payload, new WebhookContext(Map.of(DevReceiptWebhookExecutor.SIGNATURE_HEADER, signature), config));
    }

    private static void assertRejected(WebhookOutcome<Receipt> outcome) {
        assertNull(outcome.result());
        assertEquals(new WebhookStatusResponse("REJECTED"), outcome.responseBody());
    }
}
