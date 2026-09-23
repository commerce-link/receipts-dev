package pl.commercelink.receipts.dev;

import pl.commercelink.receipts.api.Receipt;
import pl.commercelink.receipts.api.ReceiptProviderDescriptor;
import pl.commercelink.receipts.api.testing.ReceiptWebhookContractTest;
import pl.commercelink.receipts.api.testing.SignedWebhook;

import java.util.Map;
import java.util.Optional;

/**
 * The webhook kit against the dev executor. The kit's sample receipts are not in the book, so each valid webhook
 * first seeds an ordered PENDING receipt under the sample's key and id, then signs the event that settles it.
 */
class DevReceiptWebhookContractTest extends ReceiptWebhookContractTest {

    private static final Map<String, String> CONFIG = Map.of(DevReceiptsDescriptor.WEBHOOK_SECRET, "tck-secret");

    private final DevReceiptBook book = new DevReceiptBook(DevRequests.CLOCK);
    private final DevReceiptsDescriptor descriptor = new DevReceiptsDescriptor(book);

    @Override
    protected ReceiptProviderDescriptor descriptor() {
        return descriptor;
    }

    @Override
    protected Map<String, String> providerConfig() {
        return CONFIG;
    }

    @Override
    protected SignedWebhook validWebhook(Receipt receipt) {
        book.seedPending(receipt.receiptKey(), receipt.providerReceiptId());
        return signed(receipt.providerReceiptId() + " " + receipt.state().name());
    }

    @Override
    protected SignedWebhook unauthenticated(SignedWebhook valid) {
        return new SignedWebhook(valid.payload(), Map.of());
    }

    @Override
    protected Optional<SignedWebhook> tampered(SignedWebhook valid) {
        return Optional.of(new SignedWebhook(valid.payload().replace("FISCALISED", "FAILED"), valid.headers()));
    }

    @Override
    protected Optional<SignedWebhook> irrelevantWebhook() {
        return Optional.of(signed("dev-19990101T000000Z-000001 FISCALISED"));
    }

    private static SignedWebhook signed(String payload) {
        String signature = DevReceiptWebhookExecutor.sign(payload, CONFIG.get(DevReceiptsDescriptor.WEBHOOK_SECRET));
        return new SignedWebhook(payload, Map.of(DevReceiptWebhookExecutor.SIGNATURE_HEADER, signature));
    }
}
