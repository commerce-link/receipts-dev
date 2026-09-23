package pl.commercelink.receipts.dev;

import pl.commercelink.provider.api.WebhookContext;
import pl.commercelink.provider.api.WebhookExecutor;
import pl.commercelink.provider.api.WebhookOutcome;
import pl.commercelink.provider.api.WebhookStatusResponse;
import pl.commercelink.receipts.api.Receipt;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Status webhook of the dev adapter: whoever sends it plays the fiscal device. The body is
 * {@code "<providerReceiptId> <FISCALISED|FAILED|LINK>"}, signed with HMAC-SHA256 under the store's
 * {@code webhookSecret} in {@link #SIGNATURE_HEADER} (hex). A verified event moves the receipt in the book as
 * {@link DevReceiptBook#settle} does and reports its current state.
 *
 * <p>No secret configured, a missing or wrong signature: {@code REJECTED}. A verified call that names no receipt of
 * this book or cannot be parsed has nothing to report: {@link WebhookOutcome#empty()}. Never throws.
 */
final class DevReceiptWebhookExecutor implements WebhookExecutor<Receipt> {

    static final String SIGNATURE_HEADER = "X-Receipts-Dev-Signature";

    private static final String REJECTED = "REJECTED";

    private final DevReceiptBook book;

    DevReceiptWebhookExecutor(DevReceiptBook book) {
        this.book = book;
    }

    @Override
    public WebhookOutcome<Receipt> execute(String payload, WebhookContext context) {
        Map<String, String> config = context.providerConfig();
        String secret = config == null ? null : config.get(DevReceiptsDescriptor.WEBHOOK_SECRET);
        String signature = context.header(SIGNATURE_HEADER);
        if (payload == null || secret == null || secret.isBlank() || signature == null
                || !MessageDigest.isEqual(sign(payload, secret).getBytes(StandardCharsets.UTF_8),
                        signature.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))) {
            return WebhookOutcome.of(null, new WebhookStatusResponse(REJECTED));
        }
        String[] parts = payload.strip().split(" ");
        if (parts.length != 2) {
            return WebhookOutcome.empty();
        }
        return event(parts[1])
                .flatMap(event -> book.settle(parts[0], event))
                .map(receipt -> WebhookOutcome.of(receipt, (Object) new WebhookStatusResponse("OK")))
                .orElseGet(WebhookOutcome::empty);
    }

    /** Hex HMAC-SHA256 of the UTF-8 payload; what the sender puts in {@link #SIGNATURE_HEADER}. */
    static String sign(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    private static Optional<DevReceiptBook.Event> event(String value) {
        for (DevReceiptBook.Event event : DevReceiptBook.Event.values()) {
            if (event.name().equals(value.toUpperCase(Locale.ROOT))) {
                return Optional.of(event);
            }
        }
        return Optional.empty();
    }
}
