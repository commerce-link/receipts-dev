package pl.commercelink.receipts.dev;

import org.junit.jupiter.api.Test;
import pl.commercelink.provider.api.EventBinding;
import pl.commercelink.provider.api.EventBinding.WebhookBinding;
import pl.commercelink.provider.api.ProviderField;
import pl.commercelink.receipts.api.Receipt;
import pl.commercelink.receipts.api.ReceiptProvider;
import pl.commercelink.receipts.api.ReceiptProviderDescriptor;
import pl.commercelink.receipts.api.ReceiptState;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static pl.commercelink.receipts.dev.DevRequests.goods;
import static pl.commercelink.receipts.dev.DevRequests.request;

class DevReceiptsDescriptorTest {

    private final DevReceiptsDescriptor descriptor = new DevReceiptsDescriptor();

    @Test
    void identifiesAsTheDevAdapter() {
        // when / then
        assertEquals("receipts-dev", descriptor.name());
        assertEquals("Dev Receipts (in-memory)", descriptor.displayName());
        assertEquals(Map.of("dev", "true"), descriptor.metadata());
    }

    @Test
    void declaresTwoOptionalFields() {
        // when
        List<ProviderField> fields = descriptor.configurationFields();

        // then
        assertEquals(List.of("scenarioOverride", "webhookSecret"), fields.stream().map(ProviderField::key).toList());
        assertEquals(ProviderField.FieldType.TEXT, fields.get(0).type());
        assertEquals(ProviderField.FieldType.PASSWORD, fields.get(1).type());
        assertTrue(fields.stream().noneMatch(ProviderField::required));
    }

    @Test
    void providersCreatedSeparatelyShareTheBook() {
        // given
        Receipt issued = descriptor.create(Map.of()).issue(request("o-1:R1", goods("Kabel")));

        // when
        Receipt fetched = descriptor.create(Map.of()).fetch(issued.providerReceiptId());

        // then
        assertEquals(issued, fetched);
    }

    @Test
    void scenarioOverrideFromConfigurationIsApplied() {
        // given
        ReceiptProvider provider = descriptor.create(Map.of("scenarioOverride", "STUCK"));

        // when
        Receipt receipt = provider.issue(request("o-1:R1", goods("Kabel")));

        // then
        assertEquals(ReceiptState.PENDING, receipt.state());
    }

    @Test
    void nullConfigurationMeansNoOverride() {
        // when
        Receipt receipt = descriptor.create(null).issue(request("o-1:R1", goods("Kabel")));

        // then
        assertEquals(ReceiptState.FISCALISED, receipt.state());
    }

    @Test
    void declaresOneWebhookBindingNamedAfterTheAdapter() {
        // when
        List<EventBinding<?>> bindings = descriptor.bindings();

        // then
        assertEquals(1, bindings.size());
        WebhookBinding<?> webhook = assertInstanceOf(WebhookBinding.class, bindings.get(0));
        assertEquals("receipts-dev", webhook.path());
    }

    @Test
    void separateDescriptorsDoNotShareReceipts() {
        // given
        Receipt issued = descriptor.create(Map.of()).issue(request("o-1:R1", goods("Kabel")));

        // when / then
        assertFalse(new DevReceiptsDescriptor().create(Map.of()).find(issued.receiptKey()).isPresent());
    }

    @Test
    void isDiscoverableThroughServiceLoader() {
        // when
        List<String> names = ServiceLoader.load(ReceiptProviderDescriptor.class).stream()
                .map(provider -> provider.get().name())
                .toList();

        // then
        assertTrue(names.contains("receipts-dev"));
    }
}
