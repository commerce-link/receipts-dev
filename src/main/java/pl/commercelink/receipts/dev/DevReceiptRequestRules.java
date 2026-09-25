package pl.commercelink.receipts.dev;

import pl.commercelink.receipts.api.LineKind;
import pl.commercelink.receipts.api.PaymentForm;
import pl.commercelink.receipts.api.ReceiptLine;
import pl.commercelink.receipts.api.ReceiptLineNames;
import pl.commercelink.receipts.api.ReceiptMedium;
import pl.commercelink.receipts.api.ReceiptPayment;
import pl.commercelink.receipts.api.ReceiptRequest;
import pl.commercelink.receipts.api.ReceiptValidationException;
import pl.commercelink.receipts.api.VatRate;

import java.util.EnumSet;
import java.util.Set;

/**
 * The refusals real providers give before any remote call, so the app meets them locally: a missing buyer e-mail,
 * more than one payment form on a receipt (Fakturownia allows only one), a name the device cannot print as is, and
 * an enum constant this adapter does not know. A line worth 0 PLN is refused earlier, by
 * {@code ReceiptRequest.Builder.build()} itself.
 */
final class DevReceiptRequestRules {

    // The constants receipts-api 0.1.0 defines, listed explicitly: a constant added in a later version is refused,
    // never guessed (ReceiptProvider, "Evolving enums").
    private static final Set<ReceiptMedium> MEDIA = EnumSet.of(ReceiptMedium.ELECTRONIC);
    private static final Set<LineKind> KINDS = EnumSet.of(LineKind.GOODS, LineKind.SHIPPING, LineKind.SERVICE);
    private static final Set<VatRate> RATES = EnumSet.of(VatRate.VAT_23, VatRate.VAT_8, VatRate.VAT_5, VatRate.VAT_0,
            VatRate.EXEMPT);
    private static final Set<PaymentForm> FORMS = EnumSet.of(PaymentForm.CASH, PaymentForm.CARD, PaymentForm.TRANSFER,
            PaymentForm.MOBILE, PaymentForm.VOUCHER, PaymentForm.CREDIT, PaymentForm.OTHER);

    private DevReceiptRequestRules() {
    }

    /** Throws {@link ReceiptValidationException} on the first broken rule. */
    static void check(ReceiptRequest request, int maxLineNameLength) {
        if (request == null) {
            throw new ReceiptValidationException("request is required");
        }
        request.validate();
        String email = request.buyer().email();
        require(email != null && !email.isBlank(), "buyer email is required for an e-receipt");
        require(MEDIA.contains(request.medium()), "unsupported medium " + request.medium());
        for (int i = 0; i < request.lines().size(); i++) {
            checkLine("line " + i + ": ", request.lines().get(i), maxLineNameLength);
        }
        for (int i = 0; i < request.payments().size(); i++) {
            ReceiptPayment payment = request.payments().get(i);
            require(FORMS.contains(payment.form()), "payment " + i + ": unsupported form " + payment.form());
        }
        if (request.payments().stream().map(ReceiptPayment::form).distinct().count() > 1) {
            throw new ReceiptValidationException("receipts-dev: one payment type per receipt, as Fakturownia requires");
        }
    }

    private static void checkLine(String prefix, ReceiptLine line, int maxLineNameLength) {
        require(KINDS.contains(line.kind()), prefix + "unsupported kind " + line.kind());
        require(RATES.contains(line.vatRate()), prefix + "unsupported vatRate " + line.vatRate());
        require(line.name().equals(ReceiptLineNames.normalize(line.name(), maxLineNameLength)),
                prefix + "name is not printable as is; pass it through ReceiptLineNames.normalize(name, "
                        + maxLineNameLength + "): " + line.name());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new ReceiptValidationException(message);
        }
    }
}
