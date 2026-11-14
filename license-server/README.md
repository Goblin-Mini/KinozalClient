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

The response contains a one-time activation code. Copy it and send it privately to the customer. The plaintext code is not stored and cannot be retrieved later. If it is lost before activation, revoke the old license and issue a new one.

The activation code is valid for 90 days after issuance. The Plus entitlement term is selected separately and starts on the first successful activation. Choose 0 for lifetime, or 7, 30, 60, 90, 180, or 365 days.

## Separate browser admin site

The administration interface is a standalone static site in `admin-panel/`. It is intended to be deployed as its own Cloudflare Pages project, separate from the license Worker and the public landing page.

The Pages site calls the Worker API at `https://YOUR-WORKER.workers.dev`. It supports:
- live totals for licenses, active licenses, devices and free device slots;
- issuing lifetime or time-limited Plus licenses after manual payment verification;
- selecting the installation limit (1–10 devices) and Plus duration (lifetime, 7, 30, 60, 90, 180, or 365 days);
- searching and filtering licenses;
- viewing attached devices and revoking an individual device or an entire license.

The admin page stores the public API URL in browser local storage, but the `ADMIN_TOKEN` is held only in the current tab's memory. It is never written to local storage, the page source, or the repository. The activation code is shown only once after creation; copy it and send it privately to the customer.

### Deploy the separate Pages site from the Cloudflare dashboard

1. In **Workers & Pages**, choose **Create application → Pages → Connect to Git**.
2. Select `Goblin-Mini/KinozalClient` and the `main` branch.
3. Leave the build command empty and set the build output directory to `admin-panel`. The repository root can remain the default.
4. Name the Pages project `kinozal-tv-plus-admin` (or another available name), then deploy it. Cloudflare will show the exact `https://PROJECT.pages.dev` address.
5. In the existing license Worker, open **Settings → Variables and Secrets** and add a plain-text variable named `ALLOWED_ORIGIN` with the exact Pages origin, for example `https://kinozal-tv-plus-admin.pages.dev`. Do not include a path or trailing slash.
6. Deploy the Worker version containing `license-server/src/index.ts`. Keep the existing D1 binding named `DB` and secret `ADMIN_TOKEN`; neither should be recreated.
7. Open the Pages URL, enter the Worker API URL and the existing `ADMIN_TOKEN`, then connect.

The Worker only adds CORS permission for the exact value in `ALLOWED_ORIGIN`. Do not use a wildcard. If a custom domain is later attached to Pages, update this variable to that exact origin.

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

For an existing D1 database, apply the updated schema again (all tables/indexes use `IF NOT EXISTS`):

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
- The standalone Pages admin site must be deployed and its exact origin configured as Worker variable `ALLOWED_ORIGIN`.
- No payment integration, receipt handling, refunds or automatic issuance; issuance is manual after external payment verification.
- The Android sync client is integrated, but the Worker must be deployed and its D1 schema applied before cloud sync can be used.
- No OWNER provisioning endpoint yet; owner entitlement remains a separate follow-up.
- Before production use, test simultaneous activation attempts, expired/revoked codes, device revocation, token loss, and D1 behavior in a Cloudflare test database.


## Timed Plus license migration

For an existing D1 database, run `license-server/migrations/0002_license_duration.sql` exactly once in the Cloudflare D1 Console. It adds `duration_days` and `expires_at`; existing rows default to lifetime and are not shortened. Do not run this migration a second time. New timed licenses start their countdown on the first successful activation, not when the admin creates the code. The activation code remains redeemable for 90 days from issue. The Worker rejects license checks and sync calls after the entitlement expiry.


## Protect the separate admin website with Cloudflare Access

The admin website is hosted separately from the Worker API. Protect the Pages hostname
(for example, `kinozal-tv-plus-admin.pages.dev`) with Cloudflare Access so only
approved administrator identities can open the dashboard.

1. In Cloudflare Zero Trust, open Access → Applications → Add an application.
2. Choose **Self-hosted** and enter the exact admin Pages hostname as the application domain.
3. Add an **Allow** policy for your administrator email address (or a dedicated admin email group).
4. Use a one-time PIN or your preferred identity provider, then save and test in a private browser window.
5. Do not protect the Worker API hostname with this Pages-only Access application: the Android app must continue reaching the Worker directly.
6. Keep the `ADMIN_TOKEN` secret in the Worker. Access protects the dashboard page; the Worker continues to enforce `ADMIN_TOKEN` for every admin API action.

Do not add the Worker hostname to the Pages Access application. If you later add a custom admin domain, update the Worker's `ALLOWED_ORIGIN` variable to that exact HTTPS origin.
