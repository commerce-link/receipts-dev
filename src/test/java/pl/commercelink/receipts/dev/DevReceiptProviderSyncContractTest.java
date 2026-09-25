package pl.commercelink.receipts.dev;

/**
 * The contract with the kit's own sample request, which carries no marker: every receipt is fiscalised at once, so
 * {@code fetchReflectsFailure} is skipped by the kit as for any synchronous provider; the async run covers it.
 */
class DevReceiptProviderSyncContractTest extends DevReceiptProviderContractTestBase {
}
