package pl.commercelink.receipts.dev;

import pl.commercelink.receipts.api.FiscalData;
import pl.commercelink.receipts.api.Receipt;
import pl.commercelink.receipts.api.ReceiptException;
import pl.commercelink.receipts.api.ReceiptFailure;
import pl.commercelink.receipts.api.ReceiptOutcomeUnknownException;
import pl.commercelink.receipts.api.ReceiptRejectedException;
import pl.commercelink.receipts.api.ReceiptState;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The simulated provider backend: every receipt created during this JVM's lifetime, the keys that already had their
 * first attempt, and the numbering. Owned by the descriptor, because {@code ProviderFactory} creates a fresh provider
 * on every call while loading descriptors once. Every operation runs under this object's monitor, so concurrent calls
 * with the same key — SQS listeners — create one receipt.
 *
 * <p>Ids and receipt numbers carry the moment this book was created, so they never repeat across a restart (unlike
 * a counter seeded from the wall clock). The receipts themselves are lost on restart: a fetch of an id from an
 * earlier run fails as "unknown receipt".
 */
final class DevReceiptBook {

    static final String CASH_REGISTER_UNIQUE_NUMBER = "DEV00000001";
    static final String DOCUMENT_URL_PREFIX = "https://receipts-dev.local/r/";
    static final ReceiptFailure PRINTER_ERROR = new ReceiptFailure("fiscal_error", "Niepoprawna wartość brutto na pozycji 1");

    private static final DateTimeFormatter BOOT_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    /** What the fiscal device, or an operator through the webhook, reports about a receipt. */
    enum Event {
        FISCALISED,
        FAILED,
        LINK
    }

    private final Clock clock;
    private final String bootStamp;
    private final Map<String, Entry> byKey = new HashMap<>();
    private final Map<String, Entry> byProviderId = new HashMap<>();
    private final Set<String> attemptedKeys = new HashSet<>();
    private int sequence;
    private int createCalls;
    private int remoteCalls;

    DevReceiptBook() {
        this(Clock.systemUTC());
    }

    DevReceiptBook(Clock clock) {
        this.clock = clock;
        this.bootStamp = BOOT_STAMP.format(clock.instant());
    }

    synchronized Receipt issue(String receiptKey, DevReceiptScenario scenario) {
        remoteCalls++;
        Entry existing = byKey.get(receiptKey);
        if (existing != null) {
            return existing.retry();
        }
        boolean firstAttempt = attemptedKeys.add(receiptKey);
        if (scenario == DevReceiptScenario.REJECT) {
            throw new ReceiptRejectedException("rejected", "receipts-dev: " + scenario.marker() + " refuses every receipt");
        }
        if (scenario == DevReceiptScenario.UNAVAILABLE && firstAttempt) {
            throw new ReceiptException("receipts-dev: " + scenario.marker()
                    + " - provider unreachable, nothing was sent; retry with the same key");
        }
        Entry entry = create(receiptKey, scenario);
        if (scenario == DevReceiptScenario.UNKNOWN || scenario == DevReceiptScenario.UNKNOWN_UNORDERED) {
            throw new ReceiptOutcomeUnknownException("receipts-dev: " + scenario.marker()
                    + " - response lost after the receipt was stored; retry issue with the same key");
        }
        return entry.snapshot();
    }

    synchronized Optional<Receipt> find(String receiptKey) {
        remoteCalls++;
        return Optional.ofNullable(byKey.get(receiptKey)).map(Entry::snapshot);
    }

    synchronized Receipt fetch(String providerReceiptId) {
        remoteCalls++;
        Entry entry = providerReceiptId == null ? null : byProviderId.get(providerReceiptId);
        if (entry == null) {
            throw new ReceiptException("receipts-dev: unknown receipt " + providerReceiptId
                    + " (receipts are kept in memory and lost on restart)");
        }
        entry.onFetch();
        return entry.snapshot();
    }

    /** Moves a receipt as the fiscal device would; the current state when the event does not apply. */
    synchronized Optional<Receipt> settle(String providerReceiptId, Event event) {
        Entry entry = providerReceiptId == null ? null : byProviderId.get(providerReceiptId);
        if (entry == null) {
            return Optional.empty();
        }
        entry.settle(event);
        return Optional.of(entry.snapshot());
    }

    /** Test seam for the webhook contract kit: an ordered PENDING receipt under a caller-chosen id. */
    synchronized void seedPending(String receiptKey, String providerReceiptId) {
        if (byProviderId.containsKey(providerReceiptId)) {
            return;
        }
        // Contract-kit seam only: uses the caller-chosen providerReceiptId as the receipt number too; a real
        // receipt's number is always <bootStamp>-<seq>, as create() gives it.
        Entry entry = new Entry(receiptKey, providerReceiptId, providerReceiptId, DevReceiptScenario.STUCK);
        entry.ordered = true;
        register(entry);
    }

    synchronized int createCalls() {
        return createCalls;
    }

    synchronized int remoteCalls() {
        return remoteCalls;
    }

    String bootStamp() {
        return bootStamp;
    }

    private Entry create(String receiptKey, DevReceiptScenario scenario) {
        createCalls++;
        sequence++;
        String number = bootStamp + "-" + String.format("%06d", sequence);
        Entry entry = new Entry(receiptKey, "dev-" + number, number, scenario);
        switch (scenario) {
            case DEFAULT, UNKNOWN, UNAVAILABLE -> entry.fiscalise(true);
            case NOLINK, NOLINK_NEVER -> entry.fiscalise(false);
            case PENDING, FAIL, STUCK -> entry.ordered = true;
            case UNKNOWN_UNORDERED -> entry.ordered = false;
            case REJECT -> throw new IllegalStateException("REJECT never stores a receipt");
        }
        register(entry);
        return entry;
    }

    private void register(Entry entry) {
        byKey.put(entry.receiptKey, entry);
        byProviderId.put(entry.providerReceiptId, entry);
    }

    /** One receipt; mutated only under the book's monitor. */
    private final class Entry {

        private final String receiptKey;
        private final String providerReceiptId;
        private final String receiptNumber;
        private final DevReceiptScenario scenario;
        private ReceiptState state = ReceiptState.PENDING;
        // Whether the receipt was sent for fiscalisation; only UNKNOWN_UNORDERED starts without it.
        private boolean ordered;
        private int fetchesWhilePending;
        private FiscalData fiscal;
        private String documentUrl;
        private ReceiptFailure failure;

        private Entry(String receiptKey, String providerReceiptId, String receiptNumber, DevReceiptScenario scenario) {
            this.receiptKey = receiptKey;
            this.providerReceiptId = providerReceiptId;
            this.receiptNumber = receiptNumber;
            this.scenario = scenario;
        }

        private Receipt retry() {
            if (state == ReceiptState.FAILED) {
                // issue may only return PENDING or FISCALISED; a failed attempt is certainly dead.
                throw new ReceiptRejectedException(failure.code(), "receipts-dev: receipt " + providerReceiptId
                        + " failed (" + failure.message() + "); issue with a new key");
            }
            if (state == ReceiptState.PENDING && !ordered) {
                ordered = true;
            }
            return snapshot();
        }

        private void onFetch() {
            if (state == ReceiptState.PENDING && ordered) {
                fetchesWhilePending++;
                switch (scenario) {
                    case PENDING -> {
                        if (fetchesWhilePending >= 2) {
                            fiscalise(true);
                        }
                    }
                    case FAIL -> fail();
                    case UNKNOWN_UNORDERED -> fiscalise(true);
                    default -> {
                    }
                }
            } else if (state == ReceiptState.FISCALISED && documentUrl == null && scenario == DevReceiptScenario.NOLINK) {
                documentUrl = DOCUMENT_URL_PREFIX + providerReceiptId;
            }
        }

        private void settle(Event event) {
            switch (event) {
                case FISCALISED -> {
                    if (state == ReceiptState.PENDING && ordered) {
                        fiscalise(true);
                    }
                }
                case FAILED -> {
                    if (state == ReceiptState.PENDING && ordered) {
                        fail();
                    }
                }
                case LINK -> {
                    if (state == ReceiptState.FISCALISED && documentUrl == null) {
                        documentUrl = DOCUMENT_URL_PREFIX + providerReceiptId;
                    }
                }
            }
        }

        private void fiscalise(boolean withLink) {
            state = ReceiptState.FISCALISED;
            ordered = true;
            fiscal = new FiscalData(CASH_REGISTER_UNIQUE_NUMBER, receiptNumber, clock.instant());
            documentUrl = withLink ? DOCUMENT_URL_PREFIX + providerReceiptId : null;
        }

        private void fail() {
            state = ReceiptState.FAILED;
            failure = PRINTER_ERROR;
        }

        private Receipt snapshot() {
            return switch (state) {
                case PENDING -> Receipt.pending(receiptKey, providerReceiptId);
                case FISCALISED -> Receipt.fiscalised(receiptKey, providerReceiptId, fiscal, documentUrl);
                case FAILED -> Receipt.failed(receiptKey, providerReceiptId, failure);
            };
        }
    }
}
