# Kinozal TV Plus — manual license service (MVP)

This service supports manual payment verification and manual license issuance. It does **not** connect to GoDonate or any other payment provider.

## Product rules

- Plus entitlement can be lifetime or time-limited (7, 30, 60, 90, 180, or 365 days).
- The selected Plus term starts on the first successful device activation; all devices on the license share the same expiry.
- Activation code itself is valid for 90 days after issuance.
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

The response contains an activation code. It is stored as AES-GCM ciphertext and can be viewed only by an authenticated administrator. Keep ADMIN_TOKEN unchanged because it currently derives the encryption key. If a code is lost, view it in the admin panel or issue a replacement.

The activation code is valid for 90 days after issuance. The Plus entitlement term is selected separately and starts on the first successful activation. Choose 0 for lifetime, or 7, 30, 60, 90, 180, or 365 days.

## Browser admin panel

The administration interface is embedded in the license Worker and served from the same origin at `/admin`. No separate Cloudflare Pages project is required.

The Pages site calls the Worker API at `https://YOUR-WORKER.workers.dev`. It supports:
- live totals for licenses, active licenses, devices and free device slots;
- issuing lifetime or time-limited Plus licenses after manual payment verification;
- selecting the installation limit (1–10 devices) and Plus duration (lifetime, 7, 30, 60, 90, 180, or 365 days);
- searching and filtering licenses;
- viewing attached devices and revoking an individual device or an entire license.

The admin page stores the public API URL in browser local storage, but the `ADMIN_TOKEN` is held only in the current tab's memory. It is never written to local storage, the page source, or the repository. Activation codes can be viewed and copied later from each license's «Коды активации» section. Codes created before encrypted storage cannot be recovered; issue a replacement for those licenses.

### Open the admin panel

Open `https://YOUR-WORKER.workers.dev/admin`. The page uses the same-origin Worker API; no CORS origin or Pages deployment is needed.

### Payment status

Payment processing is not connected yet. At this stage the administrator verifies payment outside the panel and issues Plus manually. The dashboard clearly labels this manual process; it does not invent payment records or claim that receipts have been checked. Payment-provider webhooks, orders, refunds and automatic issuance are a later stage.

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

For an existing D1 database, apply `license-server/migrations/0003_encrypted_activation_codes.sql` exactly once in the Cloudflare D1 SQL console. Do not rerun it. For a new database, `schema.sql` already includes the encrypted-code columns.

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
- The admin panel is served by the Worker at `/admin`; `ALLOWED_ORIGIN` is not needed for the same-origin admin UI.
- No payment integration, receipt handling, refunds or automatic issuance; issuance is manual after external payment verification.
- The Android sync client is integrated, but the Worker must be deployed and its D1 schema applied before cloud sync can be used.
- No OWNER provisioning endpoint yet; owner entitlement remains a separate follow-up.
- Before production use, test simultaneous activation attempts, expired/revoked codes, device revocation, token loss, and D1 behavior in a Cloudflare test database.


## Timed Plus license migration

For an existing D1 database, run `license-server/migrations/0002_license_duration.sql` exactly once in the Cloudflare D1 Console. It adds `duration_days` and `expires_at`; existing rows default to lifetime and are not shortened. Do not run this migration a second time. New timed licenses start their countdown on the first successful activation, not when the admin creates the code. The activation code remains redeemable for 90 days from issue. The Worker rejects license checks and sync calls after the entitlement expiry.


## Protect the admin panel with Cloudflare Access

The admin panel is served by the Worker. Protect the Worker hostname with Cloudflare Access so only
approved administrator identities can open the dashboard.

1. In Cloudflare Zero Trust, open Access → Applications → Add an application.
2. Choose **Self-hosted** and enter the exact admin Pages hostname as the application domain.
3. Add an **Allow** policy for your administrator email address (or a dedicated admin email group).
4. Use a one-time PIN or your preferred identity provider, then save and test in a private browser window.
5. Ensure Access policy permits the Android API routes if you protect the Worker hostname; otherwise do not enable Access on the Worker hostname.
6. Keep the `ADMIN_TOKEN` secret in the Worker. Access protects the dashboard page; the Worker continues to enforce `ADMIN_TOKEN` for every admin API action.

Do not add the Worker hostname to the Pages Access application. If you later add a custom admin domain, update the Worker's `ALLOWED_ORIGIN` variable to that exact HTTPS origin.
