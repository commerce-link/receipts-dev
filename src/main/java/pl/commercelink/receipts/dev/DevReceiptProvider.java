package pl.commercelink.receipts.dev;

import pl.commercelink.receipts.api.Receipt;
import pl.commercelink.receipts.api.ReceiptException;
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
    private final String documentUrlBase;

    DevReceiptProvider(DevReceiptBook book, String scenarioOverride) {
        this(book, scenarioOverride, null);
    }

    DevReceiptProvider(DevReceiptBook book, String scenarioOverride, String documentUrlBase) {
        this.book = book;
        this.scenarioOverride = scenarioOverride;
        this.documentUrlBase = documentUrlBase;
    }

    @Override
    public Receipt issue(ReceiptRequest request) {
        DevReceiptRequestRules.check(request, maxLineNameLength());
        // The store override applies to every receipt "whatever the lines say" (useful for marketplace orders
        // whose names cannot be controlled), so lines are only parsed for a scenario marker when it is empty.
        DevReceiptScenario scenario = DevReceiptScenario.fromOverride(scenarioOverride)
                .orElseGet(() -> DevReceiptScenario.fromLines(request.lines(), maxLineNameLength()));
        return book.issue(request.receiptKey(), scenario, resolveDocumentUrlBase());
    }

    /**
     * The store's {@code documentUrlBase}, defaulted and checked the way {@code scenarioOverride} is: resolved on
     * every issue, before the book is touched. Blank or unset falls back to {@link DevReceiptBook#DOCUMENT_URL_PREFIX};
     * anything else must start with {@code http://} or {@code https://}, or the configuration is refused like a real
     * provider given a broken setting.
     */
    private String resolveDocumentUrlBase() {
        if (documentUrlBase == null || documentUrlBase.isBlank()) {
            return DevReceiptBook.DOCUMENT_URL_PREFIX;
        }
        String trimmed = documentUrlBase.strip();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new ReceiptException("receipts-dev: invalid documentUrlBase '" + trimmed
                    + "'; must start with http:// or https://");
        }
        return trimmed;
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
}
