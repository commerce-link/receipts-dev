package pl.commercelink.receipts.dev;

import pl.commercelink.receipts.api.Money;
import pl.commercelink.receipts.api.PaymentForm;
import pl.commercelink.receipts.api.ReceiptBuyer;
import pl.commercelink.receipts.api.ReceiptLine;
import pl.commercelink.receipts.api.ReceiptPayment;
import pl.commercelink.receipts.api.ReceiptRequest;
import pl.commercelink.receipts.api.VatRate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** Valid requests for the adapter's own tests: one payment covering every line, buyer e-mail present. */
final class DevRequests {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-23T10:15:00Z"), ZoneOffset.UTC);

    private DevRequests() {
    }

    static ReceiptLine goods(String name) {
        return ReceiptLine.goods(name, BigDecimal.ONE, Money.ofGrosze(1000), VatRate.VAT_23).build();
    }

    static ReceiptLine goods(String name, String sku) {
        return ReceiptLine.goods(name, BigDecimal.ONE, Money.ofGrosze(1000), VatRate.VAT_23).sku(sku).build();
    }

    static ReceiptRequest request(String receiptKey, ReceiptLine... lines) {
        ReceiptRequest.Builder builder = ReceiptRequest.builder()
                .receiptKey(receiptKey)
                .orderId("order-" + receiptKey.replace(':', '-'))
                .saleDate(LocalDateTime.of(2026, 9, 23, 12, 0))
                .buyer(ReceiptBuyer.builder().email("buyer@example.com").build());
        Money total = Money.ZERO;
        for (ReceiptLine line : lines) {
            builder.line(line);
            total = total.plus(line.totalGross());
        }
        return builder.payment(ReceiptPayment.of(PaymentForm.TRANSFER, total)).build();
    }

    /** A request paid with two payment forms (CARD 5.00 + TRANSFER 5.00), matching a 10.00 PLN line. */
    static ReceiptRequest requestWithTwoPaymentForms(String receiptKey, ReceiptLine line) {
        return ReceiptRequest.builder()
                .receiptKey(receiptKey)
                .orderId("order-" + receiptKey.replace(':', '-'))
                .saleDate(LocalDateTime.of(2026, 9, 23, 12, 0))
                .line(line)
                .payment(ReceiptPayment.of(PaymentForm.CARD, Money.ofGrosze(500)))
                .payment(ReceiptPayment.of(PaymentForm.TRANSFER, Money.ofGrosze(500)))
                .buyer(ReceiptBuyer.builder().email("buyer@example.com").build())
                .build();
    }
}
