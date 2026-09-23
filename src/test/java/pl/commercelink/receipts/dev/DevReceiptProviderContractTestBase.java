package pl.commercelink.receipts.dev;

import pl.commercelink.receipts.api.Receipt;
import pl.commercelink.receipts.api.ReceiptProvider;
import pl.commercelink.receipts.api.ReceiptState;
import pl.commercelink.receipts.api.testing.ReceiptProviderContractTest;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/** Hooks shared by both contract runs: one book per test, failure providers built from store overrides. */
abstract class DevReceiptProviderContractTestBase extends ReceiptProviderContractTest {

    private final DevReceiptBook book = new DevReceiptBook();
    private final DevReceiptProvider provider = new DevReceiptProvider(book, null);

    @Override
    protected ReceiptProvider provider() {
        return provider;
    }

    @Override
    protected String uniqueReceiptKey() {
        return UUID.randomUUID() + ":R1";
    }

    @Override
    protected void settle(Receipt pending, ReceiptState target) {
        DevReceiptBook.Event event = target == ReceiptState.FAILED ? DevReceiptBook.Event.FAILED : DevReceiptBook.Event.FISCALISED;
        book.settle(pending.providerReceiptId(), event);
    }

    @Override
    protected Optional<ReceiptProvider> providerWithFailingTransport() {
        return Optional.of(new DevReceiptProvider(new DevReceiptBook(), "UNKNOWN"));
    }

    @Override
    protected Optional<ReceiptProvider> providerWithRejectingBackend() {
        return Optional.of(new DevReceiptProvider(new DevReceiptBook(), "REJECT"));
    }

    @Override
    protected Optional<ReceiptProvider> providerLosingNextResponse() {
        // Shares the book: the lost issue stores an unordered receipt that only the retry through provider() orders.
        return Optional.of(new DevReceiptProvider(book, "UNKNOWN_UNORDERED"));
    }

    @Override
    protected OptionalInt createCalls() {
        return OptionalInt.of(book.createCalls());
    }

    @Override
    protected OptionalInt remoteCalls() {
        return OptionalInt.of(book.remoteCalls());
    }
}
