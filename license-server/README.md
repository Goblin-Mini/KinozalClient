# Kinozal TV Plus — manual license service (MVP)

This service supports manual payment verification and manual license issuance. It does **not** connect to GoDonate or any other payment provider.

## Product rules

- One-time license, lifetime entitlement.
- One activation code can enroll up to 3 active installations.
- The same code is entered on each of the customer's devices.
- Revoking an old device frees a slot.
- The app receives a per-installation device token; only its SHA-256 hash is stored.
- Payment is checked by the project owner outside this service. Creating a license is an explicit admin action.

## Deploy to Cloudflare

1. Install Wrangler locally: `npm install --save-dev wrangler`.
2. Create a D1 database: `npx wrangler d1 create kinozal-tv-license`.
3. Copy the returned database ID into `wrangler.toml` (start from `wrangler.toml.example`).
4. Apply the schema: `npx wrangler d1 execute kinozal-tv-license --file=./schema.sql`.
5. Set a long random admin secret: `npx wrangler secret put ADMIN_TOKEN`.
6. Deploy: `npx wrangler deploy`.
7. Verify: `curl https://YOUR-WORKER.workers.dev/health`.

Keep `ADMIN_TOKEN` in a password manager. Never put it in the APK, GitHub, a public website, or a message to a customer. The real `wrangler.toml` should not be committed if it contains account-specific values; the checked-in example contains only a placeholder.

## Manual sale and issuance

After independently confirming the customer's payment, create a license:

```sh
curl -X POST "https://YOUR-WORKER.workers.dev/admin/licenses" \
  -H "Authorization: Bearer YOUR_ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"customer_label":"Order 001","code_valid_days":90}'
```

The response contains an activation code. Codes are stored only as AES-GCM ciphertext; plaintext is returned only to the authenticated administrator. Keep `ADMIN_TOKEN` stable because it is used to derive the encryption key. If a code is lost, use the admin panel to view it or issue a replacement.

The default code validity is 90 days (configurable from 1 to 365 days). License lifetime does not expire after activation.

## Web admin panel

The Worker serves a browser-based admin dashboard at:

`https://YOUR-WORKER.workers.dev/admin`

Open this URL, enter the current `ADMIN_TOKEN` secret, and use the dashboard to:
- issue a lifetime Plus license after manually confirming payment;
- view licenses and active-device counts;
- inspect installations attached to a license;
- revoke an individual device (frees one of the three slots);
- revoke an entire license.

The dashboard talks to the same-origin Worker API. The admin token is held only in the current browser tab's memory and is not saved to local storage. Activation codes can be viewed and copied later from each license's **Коды активации** section. Older codes created before encrypted storage cannot be recovered; issue a replacement for those licenses. The dashboard does not process payments or verify receipts.

The Worker source is `license-server/src/index.ts`. Deploy the updated Worker before opening `/admin`; the dashboard requires the `GET /admin/licenses` endpoint added with this version. Keep the existing D1 binding named `DB` and the secret `ADMIN_TOKEN`.

## Android API

### Claim an installation

`POST /v1/devices/claim`

```json
{
  "code": "ABCDE-FGHIJ-KLMNP-QRSTU",
  "installation_id": "app-generated-stable-installation-uuid",
  "device_label": "Living room TV"
}
```

On success the API returns a per-device `device_token`. Store it in app-private storage and send it as a Bearer token on subsequent license checks. Do not log it or include it in diagnostics. If the same installation has already been activated but its token is lost, contact support; do not generate a replacement automatically.

### Check entitlement

`GET /v1/license` with `Authorization: Bearer DEVICE_TOKEN`.

The response includes `plan`, active device count, limit, and premium feature keys. The client must fail closed for premium features when the server says the license is invalid, while allowing the free catalog and playback to continue.

## Favorites and viewing-history sync

The Android client uses the same per-device token issued by `POST /v1/devices/claim`.

- `GET /v1/sync` returns all favorite/history records for the license, including deletion tombstones.
- `PUT /v1/sync` accepts `{"items":[...]}` and returns the merged snapshot.
- Each item has `type` (`favorite` or `history`), `key` (the torrent href), `data`, `deleted`, and `updated_at` (Unix milliseconds).
- The server applies per-item last-write-wins ordering and keeps tombstones so offline devices receive deletions.
- The client keeps its local SharedPreferences data and defers synchronization when offline or when the license is unavailable.

For an existing D1 database, run the one-time migration `license-server/migrations/0002_encrypted_activation_codes.sql` in the D1 SQL console. For a new database, the main schema includes the encrypted-code columns. Do not rotate `ADMIN_TOKEN` without planning an encryption-key migration.

```sh
npx wrangler d1 execute kinozal-tv-license --file=./schema.sql
```

In the app open **Настройки → Синхронизация**, enter the deployed HTTPS Worker URL, save it, and activate each device with the same Plus activation code. Each installation consumes one of the license's three device slots.

## Admin device management

List devices for a license:

```sh
curl "https://YOUR-WORKER.workers.dev/admin/licenses/LICENSE_ID/devices" \
  -H "Authorization: Bearer YOUR_ADMIN_TOKEN"
```

Revoke one installation (frees a slot):

```sh
curl -X DELETE "https://YOUR-WORKER.workers.dev/admin/devices/DEVICE_ID" \
  -H "Authorization: Bearer YOUR_ADMIN_TOKEN"
```

Revoke the entire license:

```sh
curl -X DELETE "https://YOUR-WORKER.workers.dev/admin/licenses/LICENSE_ID" \
  -H "Authorization: Bearer YOUR_ADMIN_TOKEN"
```

## Important current limitations

- No customer login/account recovery yet.
- No web-based admin panel; admin operations use authenticated API calls.
- No payment integration, receipts, refunds or automatic issuance.
- The Android sync client is integrated, but the Worker must be deployed and its D1 schema applied before cloud sync can be used.
- No OWNER provisioning endpoint yet; owner entitlement remains a separate follow-up.
- Before production use, test simultaneous activation attempts, expired/revoked codes, device revocation, token loss, and D1 behavior in a Cloudflare test database.


## Existing database migration

In Cloudflare Dashboard, open the existing D1 database, open its SQL console, paste the contents of `migrations/0002_encrypted_activation_codes.sql`, and execute once. Do not delete or recreate the database. The migration only adds two nullable columns to `activation_codes`.
