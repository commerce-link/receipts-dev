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
     *
     * @param maxLineNameLength the device's line name limit ({@code ReceiptProvider.maxLineNameLength()}); the app
     *                          cuts every name to it via {@code ReceiptLineNames.normalize}, so a name landing
     *                          exactly on the limit is checked for a marker truncated by that cut (see
     *                          {@link #checkNameNotCutByLimit}) before its own marker search.
     */
    static DevReceiptScenario fromLines(List<ReceiptLine> lines, int maxLineNameLength) {
        for (ReceiptLine line : lines) {
            checkNameNotCutByLimit(line.name(), maxLineNameLength);
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

    /**
     * The app passes every line name through {@code ReceiptLineNames.normalize(name, maxLineNameLength)} before
     * issuing, which silently cuts a name that lands exactly on the limit. A marker at the end of such a name can be
     * cut to a shorter, valid-looking marker (or to a bare {@code SIM-RECEIPT-} prefix), which would otherwise
     * resolve as a different scenario than the one actually requested. Refuse instead of guessing: if the name is
     * exactly {@code maxLineNameLength} characters and its last space-separated word is a strict prefix of some
     * marker (i.e. that marker starts with the word but is not equal to it), the word can only be a cut marker.
     */
    private static void checkNameNotCutByLimit(String name, int maxLineNameLength) {
        if (name == null || name.length() != maxLineNameLength) {
            return;
        }
        int lastSpace = name.lastIndexOf(' ');
        String lastWord = (lastSpace < 0 ? name : name.substring(lastSpace + 1)).toUpperCase(Locale.ROOT);
        if (lastWord.length() < 4) {
            return;
        }
        for (DevReceiptScenario scenario : values()) {
            if (scenario.marker != null && scenario.marker.startsWith(lastWord) && !scenario.marker.equals(lastWord)) {
                throw new ReceiptValidationException("receipts-dev: scenario marker cut by the " + maxLineNameLength
                        + "-character name limit: " + lastWord
                        + "; put the marker at the start of the name or in the SKU");
            }
        }
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
