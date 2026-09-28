package pl.commercelink.receipts.dev;

import org.junit.jupiter.api.Test;
import pl.commercelink.receipts.api.Money;
import pl.commercelink.receipts.api.PaymentForm;
import pl.commercelink.receipts.api.ReceiptBuyer;
import pl.commercelink.receipts.api.ReceiptLine;
import pl.commercelink.receipts.api.ReceiptPayment;
import pl.commercelink.receipts.api.ReceiptRequest;
import pl.commercelink.receipts.api.ReceiptValidationException;
import pl.commercelink.receipts.api.VatRate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pl.commercelink.receipts.dev.DevRequests.goods;
import static pl.commercelink.receipts.dev.DevRequests.request;

class DevReceiptRequestRulesTest {

    @Test
    void validRequestPasses() {
        // given
        ReceiptRequest request = request("o-1:R1", goods("Kabel HDMI 2m", "HDMI-2"), goods("Zażółć gęślą jaźń"));

        // when / then
        assertDoesNotThrow(() -> DevReceiptRequestRules.check(request, 40));
    }

    @Test
    void nullRequestIsRefused() {
        // when / then
        assertThrows(ReceiptValidationException.class, () -> DevReceiptRequestRules.check(null, 40));
    }

    @Test
    void missingBuyerEmailIsRefused() {
        // given
        ReceiptRequest request = ReceiptRequest.builder()
                .receiptKey("o-1:R1").orderId("o-1").saleDate(LocalDateTime.of(2026, 9, 23, 12, 0))
                .line(goods("Kabel HDMI 2m"))
                .payment(ReceiptPayment.of(PaymentForm.CARD, Money.ofGrosze(1000)))
                .buyer(ReceiptBuyer.builder().taxId("5213000001").build())
                .build();

        // when / then
        ReceiptValidationException e = assertThrows(ReceiptValidationException.class,
                () -> DevReceiptRequestRules.check(request, 40));
        assertTrue(e.getMessage().contains("email"));
    }

    @Test
    void zeroValueLineCannotEvenBeBuilt() {
        // given: receipts-api's ReceiptRequest.Builder.build() now refuses such a line itself, so receipts-dev no
        // longer needs its own 0 PLN rule.
        ReceiptLine freeShipping = ReceiptLine.shipping("Dostawa gratis", Money.ZERO, VatRate.VAT_23).build();

        // when / then
        assertThrows(ReceiptValidationException.class, () -> request("o-1:R1", goods("Kabel HDMI 2m"), freeShipping));
    }

    @Test
    void multiplePaymentFormsAreRefused() {
        // given
        ReceiptRequest request = DevRequests.requestWithTwoPaymentForms("o-1:R1", goods("Kabel HDMI 2m"));

        // when / then
        ReceiptValidationException e = assertThrows(ReceiptValidationException.class,
                () -> DevReceiptRequestRules.check(request, 40));
        assertTrue(e.getMessage().contains("one payment"));
    }

    @Test
    void nameLongerThanTheDevicePrintsIsRefused() {
        // given
        ReceiptRequest request = request("o-1:R1", goods("Monitor gamingowy 27 cali 240 Hz IPS HDR400 czarny"));

        // when / then
        ReceiptValidationException e = assertThrows(ReceiptValidationException.class,
                () -> DevReceiptRequestRules.check(request, 40));
        assertTrue(e.getMessage().contains("ReceiptLineNames.normalize"));
    }

    @Test
    void nameWithCharactersOutsideWindows1250IsRefused() {
        // given
        ReceiptRequest request = request("o-1:R1", goods("Kabel „HDMI“ 2m"));

        // when / then
        assertThrows(ReceiptValidationException.class, () -> DevReceiptRequestRules.check(request, 40));
    }

    @Test
    void nameWithDoubleSpaceIsRefused() {
        // given
        ReceiptRequest request = request("o-1:R1", goods("Kabel  HDMI"));

        // when / then
        assertThrows(ReceiptValidationException.class, () -> DevReceiptRequestRules.check(request, 40));
    }

    @Test
    void nameLimitFollowsTheGivenLength() {
        // given
        ReceiptRequest request = request("o-1:R1", ReceiptLine.goods("Kabel HDMI 2m", new BigDecimal("1"),
                Money.ofGrosze(1000), VatRate.VAT_23).build());

        // when / then
        assertThrows(ReceiptValidationException.class, () -> DevReceiptRequestRules.check(request, 5));
    }
}
