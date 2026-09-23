package pl.commercelink.receipts.dev;

import pl.commercelink.receipts.api.Money;
import pl.commercelink.receipts.api.PaymentForm;
import pl.commercelink.receipts.api.ReceiptBuyer;
import pl.commercelink.receipts.api.ReceiptLine;
import pl.commercelink.receipts.api.ReceiptPayment;
import pl.commercelink.receipts.api.ReceiptRequest;
import pl.commercelink.receipts.api.VatRate;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** The contract with the PENDING marker on the first line, so fiscalisation and failure arrive through settle. */
class DevReceiptProviderAsyncContractTest extends DevReceiptProviderContractTestBase {

    @Override
    protected ReceiptRequest sampleRequest(String receiptKey) {
        return ReceiptRequest.builder()
                .receiptKey(receiptKey)
                .orderId("tck-order-" + receiptKey.replace(':', '-'))
                .saleDate(LocalDateTime.of(2026, 9, 22, 12, 0))
                .line(ReceiptLine.goods("Kabel HDMI 2m SIM-RECEIPT-PENDING", new BigDecimal("2"), Money.ofGrosze(2499), VatRate.VAT_23).sku("HDMI-2"))
                .line(ReceiptLine.shipping("Dostawa kurier", Money.ofGrosze(1599), VatRate.VAT_23))
                .payment(ReceiptPayment.of(PaymentForm.TRANSFER, Money.ofGrosze(6597)).label("Przelewy24"))
                .buyer(ReceiptBuyer.builder().email("tck@example.com").build())
                .build();
    }
}
