# Kinozal TV Plus — architecture and rollout

## Product rules

- One-time price: RUB 300.
- Entitlement: lifetime; no renewal.
- Customer device limit: 3 active installations.
- Device enrollment: one-time activation code, entered on each installation.
- Project owner: separate `OWNER` entitlement, lifetime, unlimited devices, no payment.
- Never store payment secrets or trust payment completion redirects in the Android app.

## Premium feature flags

| Feature key | Free | Plus |
|---|---:|---:|
| proxy settings/use | no | yes |
| advanced search | no | yes |
| mirror selection | no | yes |
| favorites | no | yes |
| viewing history | no | yes |
| collections | no | yes |

Basic catalog browsing, torrent details and ordinary playback remain free.

## Components

1. Android client: asks the licensing API for its entitlement, shows the Plus purchase/activation screen, and gates premium entry points.
2. Cloudflare Worker: creates payment orders, handles the provider callback, issues activation codes, binds devices and returns signed/short-lived entitlement responses.
3. Cloudflare D1: stores orders, licenses, activation codes and device bindings.
4. Payment provider adapter: first provider candidate is Robokassa. The adapter must remain replaceable; provider credentials are Worker secrets only.

## D1 data model

- `accounts`: internal account id, optional email/contact, created timestamp.
- `licenses`: account id, plan (`plus_lifetime` or `owner`), status, paid timestamp, payment id, device limit (3 or NULL for unlimited).
- `devices`: license id, installation id, device label, created/last-seen timestamps, revoked timestamp.
- `orders`: order id, account id, amount_minor (30000), currency (RUB), provider, provider invoice id, status, created/paid timestamps.
- `activation_codes`: code hash only, license id, expiry, used count/limit, revoked timestamp.
- `webhook_events`: provider event id / invoice id with unique constraint for idempotency.

## API contract

- `POST /v1/orders`: authenticated account creates a RUB 300 order; server returns provider checkout URL.
- `POST /v1/activation-codes`: authenticated Plus owner creates a short-lived, single-use device enrollment code.
- `POST /v1/devices/claim`: installation submits activation code and installation id; server atomically checks the device limit and binds it.
- `GET /v1/license`: returns plan, active device count, device limit and feature flags.
- `DELETE /v1/devices/:id`: revoke an installation to free a slot.
- `POST /webhooks/robokassa/result`: verify provider signature, exact invoice and amount, then atomically mark order paid and issue one lifetime Plus license. Duplicate callbacks must be harmless.

The success/return URL is informational only. A license is never issued from a browser redirect or from an APK-supplied "paid" flag.

## Owner provisioning

Set `OWNER_ACCOUNT_ID` as a Cloudflare Worker secret after the owner account has been created. On license lookup, this exact account receives a virtual `owner` entitlement with no expiry and unlimited devices. Do not hardcode an owner email, device id, or bypass switch into the APK. Owner assignment is a server-side administrative operation.

## Security and reliability

- Store only a hash of activation codes.
- Generate codes with a cryptographically secure random generator; expire them and rate-limit attempts.
- Enforce the 3-device limit transactionally in D1.
- Store Robokassa merchant credentials and passwords only as Worker secrets.
- Verify callback signature with constant-time comparison; check invoice id, exact amount, currency/expected product and payment state.
- Unique provider invoice/event constraints prevent duplicate license issuance.
- Keep an audit trail for refunds, chargebacks, manual revocations and owner changes.
- Cache a previously verified entitlement for a short offline grace period (recommended 48 hours), then require a fresh server check. A refund/revocation takes effect on the next successful check.
- Do not use Android ID as the sole device identity; use an app-generated installation UUID and allow users to revoke old devices.

## Implementation sequence

1. Confirm the merchant can legally accept payments for digital access and that Robokassa enables the required payment methods for the seller's country.
2. Deploy D1 schema and Worker in test mode.
3. Test payment creation, valid/invalid callbacks, replayed callbacks, wrong amount, refunds and activation-code replay.
4. Add Android license client and Plus screen; gate only the six premium features.
5. Add owner provisioning and device management.
6. Release a test APK; do not switch on real payments until a live test payment and receipt/tax requirements are verified.

## Open deployment inputs

- Robokassa merchant login and test/live mode.
- Seller country/legal status and receipt/fiscalization requirements.
- Cloudflare account/zone where the Worker will be deployed.
- Owner account id after the first account registration.

No secrets belong in GitHub source, Gradle files, or the APK.
