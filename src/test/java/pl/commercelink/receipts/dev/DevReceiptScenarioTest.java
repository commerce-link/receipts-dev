package pl.commercelink.receipts.dev;

import org.junit.jupiter.api.Test;
import pl.commercelink.receipts.api.ReceiptException;
import pl.commercelink.receipts.api.ReceiptValidationException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pl.commercelink.receipts.dev.DevRequests.goods;

class DevReceiptScenarioTest {

    @Test
    void linesWithoutMarkerGiveDefault() {
        // when
        DevReceiptScenario scenario = DevReceiptScenario.fromLines(List.of(goods("Kabel HDMI 2m", "HDMI-2")));

        // then
        assertEquals(DevReceiptScenario.DEFAULT, scenario);
    }

    @Test
    void markerInNameIsFoundAnywhereAndIgnoresCase() {
        // when
        DevReceiptScenario scenario = DevReceiptScenario.fromLines(List.of(goods("Kabel sim-receipt-pending 2m")));

        // then
        assertEquals(DevReceiptScenario.PENDING, scenario);
    }

    @Test
    void markerInSkuIsFound() {
        // when
        DevReceiptScenario scenario = DevReceiptScenario.fromLines(List.of(goods("Kabel HDMI 2m", "SIM-RECEIPT-STUCK")));

        // then
        assertEquals(DevReceiptScenario.STUCK, scenario);
    }

    @Test
    void firstMarkedLineWins() {
        // when
        DevReceiptScenario scenario = DevReceiptScenario.fromLines(List.of(
                goods("Kabel HDMI 2m"),
                goods("SIM-RECEIPT-FAIL"),
                goods("SIM-RECEIPT-STUCK")));

        // then
        assertEquals(DevReceiptScenario.FAIL, scenario);
    }

    @Test
    void nameMarkerWinsOverSkuMarkerOfTheSameLine() {
        // when
        DevReceiptScenario scenario = DevReceiptScenario.fromLines(List.of(goods("SIM-RECEIPT-NOLINK", "SIM-RECEIPT-STUCK")));

        // then
        assertEquals(DevReceiptScenario.NOLINK, scenario);
    }

    @Test
    void longerMarkerIsMatchedWhole() {
        // when
        DevReceiptScenario unordered = DevReceiptScenario.fromLines(List.of(goods("SIM-RECEIPT-UNKNOWN-UNORDERED")));
        DevReceiptScenario never = DevReceiptScenario.fromLines(List.of(goods("Etui SIM-RECEIPT-NOLINK-NEVER")));

        // then
        assertEquals(DevReceiptScenario.UNKNOWN_UNORDERED, unordered);
        assertEquals(DevReceiptScenario.NOLINK_NEVER, never);
    }

    @Test
    void everyScenarioButDefaultIsReachableByItsMarker() {
        for (DevReceiptScenario expected : DevReceiptScenario.values()) {
            if (expected == DevReceiptScenario.DEFAULT) {
                continue;
            }
            // when
            DevReceiptScenario scenario = DevReceiptScenario.fromLines(List.of(goods("Produkt " + expected.marker())));

            // then
            assertEquals(expected, scenario);
        }
    }

    @Test
    void unknownMarkerIsRefused() {
        // when / then
        ReceiptValidationException e = assertThrows(ReceiptValidationException.class,
                () -> DevReceiptScenario.fromLines(List.of(goods("SIM-RECEIPT-PENDNG"))));
        assertTrue(e.getMessage().contains("SIM-RECEIPT-PENDNG"));
    }

    @Test
    void markerGluedToFollowingWordIsAnUnknownMarker() {
        // when / then
        ReceiptValidationException e = assertThrows(ReceiptValidationException.class,
                () -> DevReceiptScenario.fromLines(List.of(goods("SIM-RECEIPT-PENDING-KABEL"))));
        assertTrue(e.getMessage().contains("SIM-RECEIPT-PENDING-KABEL"));
    }

    @Test
    void blankOverrideIsAbsent() {
        // when / then
        assertEquals(Optional.empty(), DevReceiptScenario.fromOverride(null));
        assertEquals(Optional.empty(), DevReceiptScenario.fromOverride("  "));
    }

    @Test
    void overrideAcceptsConstantNameInAnyCaseAndHyphens() {
        // when / then
        assertEquals(Optional.of(DevReceiptScenario.UNKNOWN_UNORDERED), DevReceiptScenario.fromOverride(" unknown_unordered "));
        assertEquals(Optional.of(DevReceiptScenario.NOLINK_NEVER), DevReceiptScenario.fromOverride("nolink-never"));
        assertEquals(Optional.of(DevReceiptScenario.DEFAULT), DevReceiptScenario.fromOverride("DEFAULT"));
    }

    @Test
    void unknownOverrideIsAConfigurationError() {
        // when / then
        ReceiptException e = assertThrows(ReceiptException.class, () -> DevReceiptScenario.fromOverride("PENDNG"));
        assertEquals(ReceiptException.class, e.getClass());
        assertTrue(e.getMessage().contains("PENDNG"));
    }

    @Test
    void unknownOverrideIsNotAValidationError() {
        // when
        ReceiptException e = assertThrows(ReceiptException.class, () -> DevReceiptScenario.fromOverride("x"));

        // then
        assertInstanceOf(ReceiptException.class, e);
        assertTrue(!(e instanceof ReceiptValidationException));
    }
}
