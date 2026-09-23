package pl.commercelink.receipts.dev;

import pl.commercelink.receipts.api.Receipt;
import pl.commercelink.receipts.api.ReceiptKeys;
import pl.commercelink.receipts.api.ReceiptProvider;
import pl.commercelink.receipts.api.ReceiptRequest;

import java.util.Optional;

/**
 * In-memory receipt provider for the dev profile. Created on every call by the descriptor; all state lives in the
 * shared {@link DevReceiptBook}. Validation, the scenario marker and the store's override are checked before the book
 * is touched, the way a real adapter refuses bad input before any remote call.
 */
final class DevReceiptProvider implements ReceiptProvider {

    private final DevReceiptBook book;
    private final String scenarioOverride;

    DevReceiptProvider(DevReceiptBook book, String scenarioOverride) {
        this.book = book;
        this.scenarioOverride = scenarioOverride;
    }

    @Override
    public Receipt issue(ReceiptRequest request) {
        DevReceiptRequestRules.check(request, maxLineNameLength());
        DevReceiptScenario fromLines = DevReceiptScenario.fromLines(request.lines());
        DevReceiptScenario scenario = DevReceiptScenario.fromOverride(scenarioOverride).orElse(fromLines);
        return book.issue(request.receiptKey(), scenario);
    }

    @Override
    public Optional<Receipt> find(String receiptKey) {
        ReceiptKeys.requireValid(receiptKey);
        return book.find(receiptKey);
    }

    @Override
    public Receipt fetch(String providerReceiptId) {
        return book.fetch(providerReceiptId);
    }

    @Override
    public boolean requiresBuyerEmail() {
        return true;
    }

    @Override
    public boolean pushesStatusUpdates() {
        return true;
    }
}
