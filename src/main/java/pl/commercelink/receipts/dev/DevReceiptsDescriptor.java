package pl.commercelink.receipts.dev;

import pl.commercelink.provider.api.EventBinding;
import pl.commercelink.provider.api.EventBinding.WebhookBinding;
import pl.commercelink.provider.api.ProviderField;
import pl.commercelink.receipts.api.Receipt;
import pl.commercelink.receipts.api.ReceiptProvider;
import pl.commercelink.receipts.api.ReceiptProviderDescriptor;

import java.util.List;
import java.util.Map;

/**
 * Registers the in-memory receipts adapter for the dev profile. The descriptor owns the receipt book, because
 * {@code ProviderFactory} creates a fresh provider on every call while loading descriptors once — holding state here
 * keeps it alive for the application's lifetime without a static field, so tests stay isolated.
 */
public class DevReceiptsDescriptor implements ReceiptProviderDescriptor {

    static final String NAME = "receipts-dev";
    static final String SCENARIO_OVERRIDE = "scenarioOverride";
    static final String WEBHOOK_SECRET = "webhookSecret";
    static final String DOCUMENT_URL_BASE = "documentUrlBase";

    private final DevReceiptBook book;

    public DevReceiptsDescriptor() {
        this(new DevReceiptBook());
    }

    DevReceiptsDescriptor(DevReceiptBook book) {
        this.book = book;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String displayName() {
        return "Dev Receipts (in-memory)";
    }

    @Override
    public List<ProviderField> configurationFields() {
        return List.of(
                new ProviderField(SCENARIO_OVERRIDE, "Symulacja: wymuś scenariusz paragonu", ProviderField.FieldType.TEXT, false,
                        "puste = wg markera SIM-RECEIPT-* w nazwie/SKU; DEFAULT | PENDING | FAIL | REJECT | UNKNOWN | "
                                + "UNKNOWN_UNORDERED | NOLINK | NOLINK_NEVER | STUCK | UNAVAILABLE — dotyczy każdego paragonu w sklepie"),
                new ProviderField(WEBHOOK_SECRET, "Sekret webhooka (HMAC-SHA256)", ProviderField.FieldType.PASSWORD, false,
                        "puste = każdy webhook odrzucany"),
                new ProviderField(DOCUMENT_URL_BASE, "Adres podglądu e-paragonu", ProviderField.FieldType.TEXT, false,
                        "Opcjonalnie: początek linku do e-paragonu; do końca dopisywane jest id paragonu. Puste = "
                                + "https://receipts-dev.local/r/ (link niedziałający)"));
    }

    @Override
    public ReceiptProvider create(Map<String, String> configuration) {
        return new DevReceiptProvider(book, configuration == null ? null : configuration.get(SCENARIO_OVERRIDE),
                configuration == null ? null : configuration.get(DOCUMENT_URL_BASE));
    }

    @Override
    public Map<String, String> metadata() {
        return Map.of("dev", "true");
    }

    @Override
    public List<EventBinding<?>> bindings() {
        return List.of(new WebhookBinding<Receipt>(NAME, new DevReceiptWebhookExecutor(book)));
    }
}
