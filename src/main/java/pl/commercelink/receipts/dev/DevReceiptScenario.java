package pl.commercelink.receipts.dev;

import pl.commercelink.receipts.api.ReceiptException;
import pl.commercelink.receipts.api.ReceiptLine;
import pl.commercelink.receipts.api.ReceiptValidationException;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simulated outcome of one receipt. Chosen once, when the receipt is first issued — from the store's
 * {@code scenarioOverride}, else from the first {@code SIM-RECEIPT-*} marker in a line's name or SKU — and kept
 * with the receipt, so retries, {@code find} and {@code fetch} follow it whatever the next request says.
 */
enum DevReceiptScenario {

    /** Fiscalised at once, with an e-receipt link. */
    DEFAULT(null),
    /** PENDING; the second fetch fiscalises it. */
    PENDING("SIM-RECEIPT-PENDING"),
    /** PENDING; the first fetch reports the printer's refusal as FAILED. */
    FAIL("SIM-RECEIPT-FAIL"),
    /** Every attempt, under any key, is refused and nothing is stored. */
    REJECT("SIM-RECEIPT-REJECT"),
    /** Stored fiscalised, but the first issue loses its response. */
    UNKNOWN("SIM-RECEIPT-UNKNOWN"),
    /** Stored PENDING but not sent for fiscalisation, and the first issue loses its response; only a retry orders it. */
    UNKNOWN_UNORDERED("SIM-RECEIPT-UNKNOWN-UNORDERED"),
    /** Fiscalised without a link; the first fetch adds it. */
    NOLINK("SIM-RECEIPT-NOLINK"),
    /** Fiscalised without a link, which never arrives (printed on paper, or the e-receipt upload gave up). */
    NOLINK_NEVER("SIM-RECEIPT-NOLINK-NEVER"),
    /** PENDING forever: the fiscal printer is switched off. */
    STUCK("SIM-RECEIPT-STUCK"),
    /** The first issue fails before sending and stores nothing; a retry with the same key behaves as DEFAULT. */
    UNAVAILABLE("SIM-RECEIPT-UNAVAILABLE");

    // Matched whole, so SIM-RECEIPT-UNKNOWN never catches SIM-RECEIPT-UNKNOWN-UNORDERED.
    private static final Pattern MARKER = Pattern.compile("SIM-RECEIPT-[A-Z]+(?:-[A-Z]+)*");

    private final String marker;

    DevReceiptScenario(String marker) {
        this.marker = marker;
    }

    /** The marker that selects this scenario; null for {@link #DEFAULT}. */
    String marker() {
        return marker;
    }

    /**
     * The scenario of the first line carrying a marker, looking at each line's name and then its SKU;
     * {@link #DEFAULT} when no line carries one. An unknown marker is refused rather than silently ignored.
     */
    static DevReceiptScenario fromLines(List<ReceiptLine> lines) {
        for (ReceiptLine line : lines) {
            Optional<DevReceiptScenario> scenario = fromText(line.name()).or(() -> fromText(line.sku()));
            if (scenario.isPresent()) {
                return scenario.get();
            }
        }
        return DEFAULT;
    }

    /**
     * The scenario a store forces for every receipt, by constant name (case and hyphens ignored); empty when not set.
     * An unknown value is a configuration error, reported like a provider refusing bad credentials.
     */
    static Optional<DevReceiptScenario> fromOverride(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT).replace('-', '_');
        for (DevReceiptScenario scenario : values()) {
            if (scenario.name().equals(normalized)) {
                return Optional.of(scenario);
            }
        }
        throw new ReceiptException("receipts-dev: unknown scenarioOverride '" + value.strip() + "'; expected one of "
                + Arrays.toString(values()));
    }

    private static Optional<DevReceiptScenario> fromText(String text) {
        if (text == null) {
            return Optional.empty();
        }
        Matcher matcher = MARKER.matcher(text.toUpperCase(Locale.ROOT));
        if (!matcher.find()) {
            return Optional.empty();
        }
        String token = matcher.group();
        for (DevReceiptScenario scenario : values()) {
            if (token.equals(scenario.marker)) {
                return Optional.of(scenario);
            }
        }
        throw new ReceiptValidationException("receipts-dev: unknown scenario marker " + token
                + " (separate a marker from the rest of the name with a space)");
    }
}
