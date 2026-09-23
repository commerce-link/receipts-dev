package pl.commercelink.receipts.dev;

import org.junit.jupiter.api.Test;
import pl.commercelink.receipts.api.Receipt;
import pl.commercelink.receipts.api.ReceiptException;
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
    void unknownMarkerIsRefusedEvenWithAnOverride() {
        // given
        DevReceiptProvider provider = new DevReceiptProvider(book, "DEFAULT");

        // when / then
        assertThrows(ReceiptValidationException.class,
                () -> provider.issue(request("o-1:R1", goods("SIM-RECEIPT-PENDNG"))));
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
    void capabilitiesDeclareEmailAndWebhook() {
        // given
        DevReceiptProvider provider = new DevReceiptProvider(book, null);

        // when / then
        assertTrue(provider.requiresBuyerEmail());
        assertTrue(provider.pushesStatusUpdates());
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
