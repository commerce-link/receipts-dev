package pl.commercelink.receipts.dev;

import org.junit.jupiter.api.Test;
import pl.commercelink.receipts.api.Receipt;
import pl.commercelink.receipts.api.ReceiptException;
import pl.commercelink.receipts.api.ReceiptLineNames;
import pl.commercelink.receipts.api.ReceiptMedium;
import pl.commercelink.receipts.api.ReceiptOutcomeUnknownException;
import pl.commercelink.receipts.api.ReceiptRequest;
import pl.commercelink.receipts.api.ReceiptState;
import pl.commercelink.receipts.api.ReceiptValidationException;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pl.commercelink.receipts.dev.DevRequests.goods;
import static pl.commercelink.receipts.dev.DevRequests.request;

class DevReceiptProviderTest {

    private final DevReceiptBook book = new DevReceiptBook(DevRequests.CLOCK);

    @Test
    void lineMarkerSelectsTheScenario() {
        // given
        DevReceiptProvider provider = new DevReceiptProvider(book, null);

        // when
        Receipt receipt = provider.issue(request("o-1:R1", goods("Kabel HDMI 2m"), goods("Etui", "SIM-RECEIPT-STUCK")));

        // then
        assertEquals(ReceiptState.PENDING, receipt.state());
    }

    @Test
    void overrideWinsOverTheLineMarker() {
        // given
        DevReceiptProvider provider = new DevReceiptProvider(book, "DEFAULT");

        // when
        Receipt receipt = provider.issue(request("o-1:R1", goods("Etui SIM-RECEIPT-STUCK")));

        // then
        assertEquals(ReceiptState.FISCALISED, receipt.state());
    }

    @Test
    void overrideAppliesToLinesWithoutMarker() {
        // given
        DevReceiptProvider provider = new DevReceiptProvider(book, "unknown_unordered");

        // when / then
        assertThrows(ReceiptOutcomeUnknownException.class, () -> provider.issue(request("o-1:R1", goods("Kabel HDMI 2m"))));
    }

    @Test
    void unknownOverrideFailsWithoutTouchingTheBook() {
        // given
        DevReceiptProvider provider = new DevReceiptProvider(book, "PENDNG");

        // when
        ReceiptException e = assertThrows(ReceiptException.class, () -> provider.issue(request("o-1:R1", goods("Kabel"))));

        // then
        assertEquals(ReceiptException.class, e.getClass());
        assertEquals(0, book.remoteCalls());
    }

    @Test
    void overrideSkipsLineMarkerResolutionEvenWithAnUnknownMarker() {
        // given: the store override applies to every receipt "whatever the lines say" (marketplace order names
        // cannot be controlled), so fromLines() is not even called when the override is set.
        DevReceiptProvider provider = new DevReceiptProvider(book, "PENDING");

        // when
        Receipt receipt = provider.issue(request("o-1:R1", goods("SIM-RECEIPT-X kabel")));

        // then
        assertEquals(ReceiptState.PENDING, receipt.state());
    }

    @Test
    void configuredDocumentUrlBaseIsUsedForTheLink() {
        // given
        DevReceiptProvider provider = new DevReceiptProvider(book, null, "https://app.example.com/store/9/e-paragon/");

        // when
        Receipt receipt = provider.issue(request("o-1:R1", goods("Kabel")));

        // then
        assertEquals("https://app.example.com/store/9/e-paragon/" + receipt.providerReceiptId(), receipt.documentUrl());
    }

    @Test
    void blankDocumentUrlBaseKeepsTheDefaultLink() {
        // given
        DevReceiptProvider provider = new DevReceiptProvider(book, null, "  ");

        // when
        Receipt receipt = provider.issue(request("o-1:R1", goods("Kabel")));

        // then
        assertEquals("https://receipts-dev.local/r/" + receipt.providerReceiptId(), receipt.documentUrl());
    }

    @Test
    void invalidDocumentUrlBaseIsRefusedBeforeAnyRemoteCall() {
        // given
        DevReceiptProvider provider = new DevReceiptProvider(book, null, "ftp://app.example.com/e-paragon/");

        // when
        ReceiptException e = assertThrows(ReceiptException.class, () -> provider.issue(request("o-1:R1", goods("Kabel"))));

        // then
        assertEquals(ReceiptException.class, e.getClass());
        assertTrue(e.getMessage().contains("documentUrlBase"));
        assertEquals(0, book.remoteCalls());
    }

    @Test
    void multiplePaymentFormsAreRefusedBeforeAnyRemoteCall() {
        // given
        DevReceiptProvider provider = new DevReceiptProvider(book, null);
        ReceiptRequest request = DevRequests.requestWithTwoPaymentForms("o-1:R1", goods("Kabel HDMI 2m"));

        // when / then
        ReceiptValidationException e = assertThrows(ReceiptValidationException.class, () -> provider.issue(request));
        assertTrue(e.getMessage().contains("one payment"));
        assertEquals(0, book.createCalls());
    }

    @Test
    void markerCutByTheNameLimitIsRefusedBeforeAnyRemoteCall() {
        // given
        DevReceiptProvider provider = new DevReceiptProvider(book, null);
        String name = ReceiptLineNames.normalize("Etui na telefon abc SIM-RECEIPT-UNKNOWN-UNORDERED", 40);

        // when / then
        assertThrows(ReceiptValidationException.class, () -> provider.issue(request("o-1:R1", goods(name))));
        assertEquals(0, book.remoteCalls());
    }

    @Test
    void invalidRequestIsRefusedBeforeAnyRemoteCall() {
        // given
        DevReceiptProvider provider = new DevReceiptProvider(book, null);

        // when / then
        assertThrows(ReceiptValidationException.class, () -> provider.issue(request("o-1:R1", goods("Kabel  HDMI"))));
        assertThrows(ReceiptValidationException.class, () -> provider.issue(null));
        assertEquals(0, book.remoteCalls());
    }

    @Test
    void findValidatesTheKeyBeforeAnyRemoteCall() {
        // given
        DevReceiptProvider provider = new DevReceiptProvider(book, null);

        // when / then
        assertThrows(ReceiptValidationException.class, () -> provider.find("bad key"));
        assertEquals(0, book.remoteCalls());
    }

    @Test
    void fetchOfUnknownIdThrows() {
        // given
        DevReceiptProvider provider = new DevReceiptProvider(book, null);

        // when / then
        assertThrows(ReceiptException.class, () -> provider.fetch("dev-unknown"));
    }

    @Test
    void providersSharingABookSeeTheSameReceipts() {
        // given
        Receipt issued = new DevReceiptProvider(book, null).issue(request("o-1:R1", goods("Kabel")));

        // when
        Receipt fetched = new DevReceiptProvider(book, null).fetch(issued.providerReceiptId());

        // then
        assertEquals(issued, fetched);
    }

    @Test
    void capabilitiesDeclareEmailAndMedia() {
        // given
        DevReceiptProvider provider = new DevReceiptProvider(book, null);

        // when / then
        assertTrue(provider.requiresBuyerEmail());
        assertEquals(Set.of(ReceiptMedium.ELECTRONIC), provider.supportedMedia());
        assertEquals(40, provider.maxLineNameLength());
    }

    @Test
    void concurrentIssuesWithTheSameKeyCreateOneReceipt() throws Exception {
        // given
        DevReceiptProvider provider = new DevReceiptProvider(book, null);
        ReceiptRequest request = request("o-1:R1", goods("Kabel"));
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> issue = () -> {
            start.await();
            return provider.issue(request).providerReceiptId();
        };
        ExecutorService pool = Executors.newFixedThreadPool(8);

        // when
        List<Future<String>> results = IntStream.range(0, 8).mapToObj(i -> pool.submit(issue)).toList();
        start.countDown();
        Set<String> ids = new java.util.HashSet<>();
        for (Future<String> result : results) {
            ids.add(result.get());
        }
        pool.shutdown();

        // then
        assertEquals(1, ids.size());
        assertEquals(1, book.createCalls());
    }
}
