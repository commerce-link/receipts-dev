# receipts-dev

In-memory implementation of `receipts-api` for the app's `dev` profile. It lets the app issue fiscal e-receipts
without a fiscal printer or a provider account, and drive every outcome a real provider can produce — including the
failure paths — on demand. The counterpart of `receipts-fakturownia`, which talks to a live service; the receipts
sibling of `invoicing-dev`.

## What it serves

- **`issue`**: validates the request the way real providers do (see [Refusals](#refusals)), picks a scenario and
  plays it. Idempotent by receipt key: a retry never creates a second receipt.
- **`find`**: read-only lookup by receipt key; never moves a receipt forward.
- **`fetch`**: the receipt's current state; this is where PENDING receipts progress and late links appear.
- **Status webhook**: whoever sends it plays the fiscal device (see [Webhook](#webhook)).

Every fiscalised receipt carries `FiscalData` with `cashRegisterUniqueNumber` and `receiptNumber` both `null`, as
Fakturownia's own response leaves them; the app falls back to the receipt key to identify the receipt (e.g. for an
invoice issued for it in KSeF). Ids look like `dev-20260923T101500123Z-4f2a-000042`: the first part is the moment the
JVM loaded the adapter plus a random suffix, so two adapters booted within the same millisecond never mint the same
id, and ids never repeat across a restart. `documentUrl` is a placeholder under `https://receipts-dev.local/r/` and
does not open anything.

Capabilities: electronic receipts only, 40-character line names, **buyer e-mail required** (as with Fakturownia),
**one payment form per receipt** (as with Fakturownia), pushes status updates.

## Scenario markers

Put a marker in the **name or SKU of any line** (case-insensitive; separate it from the following word with a
space). Lines are searched in order, the name before the SKU, and the first marker wins. The scenario is stored with
the receipt, so retries, `find` and `fetch` follow it whatever later requests contain. "First attempt" is counted per
receipt key: a new key always gets a fresh first attempt.

| Marker | `issue` | `find` | `fetch` | What the app should do |
|---|---|---|---|---|
| none | `FISCALISED` with link | same | same | store the document, mail the buyer |
| `SIM-RECEIPT-PENDING` | `PENDING` | `PENDING` | 1st `PENDING`, 2nd `FISCALISED` with link | keep polling with growing delays until fiscalised |
| `SIM-RECEIPT-FAIL` | `PENDING`; a retry after the failure throws `ReceiptRejectedException` (`fiscal_error`) | `PENDING`, `FAILED` after the fetch | 1st `FAILED` (`fiscal_error`, "Niepoprawna wartość brutto na pozycji 1") | mark the attempt `FAILED` with the printer's message; the operator issues again under a new key |
| `SIM-RECEIPT-REJECT` | `ReceiptRejectedException` (`rejected`) on every attempt and every key; nothing stored | empty | — | mark the attempt `FAILED`; a new key is refused too while the marker stays in the data |
| `SIM-RECEIPT-UNKNOWN` | 1st: stores a fiscalised receipt and throws `ReceiptOutcomeUnknownException`; retry returns it | `FISCALISED` | `FISCALISED` | retry `issue` with the same key; no second receipt appears |
| `SIM-RECEIPT-UNKNOWN-UNORDERED` | 1st: stores a PENDING receipt **not sent for fiscalisation** and throws `ReceiptOutcomeUnknownException`; retry orders it and returns `PENDING` | `PENDING` | `PENDING` forever until the retry; then the next fetch gives `FISCALISED` with link | retry `issue` with the same key even though `find` sees the receipt — polling alone never finishes it |
| `SIM-RECEIPT-NOLINK` | `FISCALISED`, no link | no link until a fetch | 1st: same fiscal data, link added | record the fiscalisation, fetch again for the link, then store the document and mail |
| `SIM-RECEIPT-NOLINK-NEVER` | `FISCALISED`, no link | no link | never a link | treat the sale as fiscalised (printed on paper); stop asking for the link at some point, never re-issue |
| `SIM-RECEIPT-STUCK` | `PENDING` (retries too) | `PENDING` | `PENDING` forever | raise the "PENDING too long" alert (printer switched off) |
| `SIM-RECEIPT-UNAVAILABLE` | 1st: `ReceiptException` (provider unreachable, nothing sent, nothing stored); retry behaves as no marker | empty before the retry | — | retry with the same key |

An unknown marker (`SIM-RECEIPT-PENDNG`, or a marker glued to the next word as in `SIM-RECEIPT-PENDING-KABEL`) is
refused with `ReceiptValidationException` instead of silently giving the default.

The app cuts line names to 40 characters (`ReceiptLineNames.normalize`) before issuing — put the marker at the
start of the name or in the SKU. A marker cut by that limit (`SIM-RECEIPT-UNKNOWN-UNORDERED` shortened to
`SIM-RECEIPT-UNKNOWN`, for example) is refused with `ReceiptValidationException` rather than silently resolving as
a different, shorter scenario.

This marker search only runs when the store has no `scenarioOverride` (see below): with an override set, the lines
are not parsed for a marker at all, so an unrelated `SIM-RECEIPT-*`-looking product name never breaks a marketplace
order whose names cannot be controlled.

### Store override

The optional configuration field `scenarioOverride` forces one scenario for **every** receipt of the store, whatever
the lines say and **without even looking at them** — useful for marketplace orders whose names cannot be controlled.
Values are the scenario names: `DEFAULT`, `PENDING`, `FAIL`, `REJECT`, `UNKNOWN`, `UNKNOWN_UNORDERED`, `NOLINK`,
`NOLINK_NEVER`, `STUCK`, `UNAVAILABLE` (case and hyphens ignored). An unknown value makes every `issue` throw a plain
`ReceiptException`, like a real provider with a broken configuration.

## Refusals

Before anything is stored, `issue` refuses with `ReceiptValidationException`, as real providers do:

- a missing buyer e-mail;
- more than one payment form on a receipt — Fakturownia allows only one;
- a line name that `ReceiptLineNames.normalize(name, 40)` would change (too long, characters outside Windows-1250,
  repeated spaces) — the app must normalise names before issuing;
- an enum constant added to `receipts-api` after 0.1.0;
- an unknown or cut `SIM-RECEIPT-*` marker, when no `scenarioOverride` is set (see above).

A line worth 0 PLN is refused earlier still, by `ReceiptRequest.Builder.build()` in `receipts-api` itself — such a
request cannot even be constructed, so `receipts-dev` no longer needs its own check for it.

## Webhook

The descriptor declares one webhook binding, `receipts-dev`. Once the app routes receipts webhooks, it is served at
`/Store/{storeId}/Webhooks/Receipts/receipts-dev`.

- Body: `<providerReceiptId> <EVENT>`, where `EVENT` is `FISCALISED`, `FAILED` or `LINK`.
- Header `X-Receipts-Dev-Signature`: hex HMAC-SHA256 of the body, keyed with the store's `webhookSecret`.

| Event | Effect |
|---|---|
| `FISCALISED` | a PENDING receipt that was sent for fiscalisation becomes `FISCALISED` with link (works on `STUCK`) |
| `FAILED` | such a receipt becomes `FAILED` (`fiscal_error`) |
| `LINK` | a `FISCALISED` receipt without link gets one (works on `NOLINK-NEVER`) |

Events never move a receipt backwards, and a receipt from `UNKNOWN-UNORDERED` that was not retried yet stays
`PENDING` — the printer never received it. The response carries the receipt's current state. No `webhookSecret`, a
missing or wrong signature: `REJECTED`. A signed call naming an unknown id or an unknown event reports nothing.

```bash
SECRET='the store webhookSecret'
BODY='dev-20260923T101500Z-000042 FISCALISED'
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$SECRET" | awk '{print $NF}')
curl -X POST "http://localhost:8080/Store/<storeId>/Webhooks/Receipts/receipts-dev" \
     -H 'Content-Type: text/plain' \
     -H "X-Receipts-Dev-Signature: $SIG" --data-binary "$BODY"
```

Adjust the host and port to where the app runs locally. The app polls PENDING receipts anyway; the webhook only
exercises the push path.

## Known limits

Receipts live in memory for the JVM's lifetime. After a restart, `fetch` of an earlier id throws `ReceiptException`
("unknown receipt") and `find` of an earlier key is empty; the app's polling of those attempts will keep failing until
the operator issues again. Ids never collide with the earlier run.

## Fakturownia outcomes not simulated

This adapter is a development aid, not a full simulator of `receipts-fakturownia`. It never reproduces:

- several receipts filed under one `receiptKey` (a rare Fakturownia race can leave more than one document behind
  an order; the dev book is idempotent by key and always keeps exactly one);
- a transport failure inside `fetch` or `find` themselves — only `issue` can simulate one (`SIM-RECEIPT-UNAVAILABLE`);
  a network blip while polling or looking up an already-issued receipt is not reproduced;
- a `cancelled` receipt state (Fakturownia reports it for a manually voided invoice; `ReceiptState` here only ever
  moves `PENDING → FISCALISED | FAILED`);
- a configurable maximum line-name length — `maxLineNameLength()` is fixed at 40, while a real fiscal printer's
  limit depends on the device and cannot be dialled in for testing.

## Discovery

Registered via `META-INF/services/pl.commercelink.receipts.api.ReceiptProviderDescriptor` as descriptor
`receipts-dev` with metadata `dev=true`. The app resolves a receipts provider by the name stored on the store, so the
adapter stays dormant until a store selects it.

## Enabling it

Add it to the app's `dev` profile:

```xml
<dependency>
    <groupId>pl.commercelink</groupId>
    <artifactId>receipts-dev</artifactId>
    <version>0.1.0</version>
</dependency>
```

and select it for the demo store in the app's seeder, with a `webhookSecret` if the webhook should be tried. The
app's receipts integration (provider factory, settings screen, queues) is a prerequisite.

**Never ship this adapter to production.** It belongs to the `dev` profile only and must not appear in the
deployment version manifest (`tools/deployment/app-versions.properties`).

## Tests

`mvn verify` runs the `receipts-api` contract kits: `ReceiptProviderContractTest` twice (without a marker, where
the kit skips `fetchReflectsFailure` as for any synchronous provider, and with `SIM-RECEIPT-PENDING`, where
fiscalisation and failure arrive later) and `ReceiptWebhookContractTest`.

## License

MIT
