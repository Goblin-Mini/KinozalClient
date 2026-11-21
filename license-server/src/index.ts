export interface Env {
  DB: D1Database;
  ADMIN_TOKEN: string;
  PLUS_SIGNING_PRIVATE_KEY?: string;
  ALLOWED_ORIGIN?: string;
}

const JSON_HEADERS = { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" };

const FEATURES = [
  "advanced_search",
  "favorites",
  "viewing_history",
  "backup",
  "screenshots",
  "comments",
  "voice_search",
  "proxy",
  "android_tv_channels",
  "similar",
  "torrent_download",

];

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), { status, headers: JSON_HEADERS });
}

function randomHex(bytes = 16): string {
  const value = new Uint8Array(bytes);
  crypto.getRandomValues(value);
  return [...value].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function randomCode(): string {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  const bytes = new Uint8Array(20);
  crypto.getRandomValues(bytes);
  const chars = [...bytes].map((b) => alphabet[b % alphabet.length]);
  return [chars.slice(0, 5), chars.slice(5, 10), chars.slice(10, 15), chars.slice(15, 20)]
    .map((part) => part.join("")).join("-");
}

function normalizeCode(code: string): string {
  return code.toUpperCase().replace(/[^A-Z0-9]/g, "");
}

async function sha256(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}




function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}

async function signPlusGrant(device: any, env: Env): Promise<string> {
  const pem = env.PLUS_SIGNING_PRIVATE_KEY || "";
  if (!pem.includes("BEGIN PRIVATE KEY")) throw new Error("plus_signing_key_not_configured");
  const encoded = pem.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, "");
  const binary = atob(encoded);
  const der = Uint8Array.from(binary, (character) => character.charCodeAt(0));
  const key = await crypto.subtle.importKey(
    "pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"],
  );
  const header = { alg: "RS256", typ: "JWT", kid: "plus-rsa-2026-01" };
  const issuedAt = Math.floor(Date.now() / 1000);
  const payload = {
    iss: "kinozal-tv-license",
    aud: "ru.gm.ktv",
    sub: device.device_id,
    iid: await sha256(device.installation_id),
    plan: device.plan,
    status: device.status,
    features: FEATURES,
    iat: issuedAt,
    exp: issuedAt + 6 * 60 * 60,
    jti: randomHex(16),
  };
  const encoder = new TextEncoder();
  const signingInput = base64Url(encoder.encode(JSON.stringify(header))) + "." +
    base64Url(encoder.encode(JSON.stringify(payload)));
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5", key, encoder.encode(signingInput),
  );
  return signingInput + "." + base64Url(new Uint8Array(signature));
}

async function codeCryptoKey(env: Env): Promise<CryptoKey> {
  const material = "kinozal-plus-code-encryption:" + env.ADMIN_TOKEN;
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(material));
  return crypto.subtle.importKey("raw", digest, { name: "AES-GCM" }, false, ["encrypt", "decrypt"]);
}
function toHex(bytes: Uint8Array): string {
  return [...bytes].map((value) => value.toString(16).padStart(2, "0")).join("");
}
function fromHex(value: string): Uint8Array {
  if (!/^(?:[a-f0-9]{2})+$/i.test(value)) throw new Error("invalid_encrypted_code");
  return new Uint8Array(value.match(/.{2}/g)!.map((part) => parseInt(part, 16)));
}
async function encryptActivationCode(code: string, env: Env): Promise<{ encryptedCode: string; iv: string }> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ciphertext = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv },
    await codeCryptoKey(env),
    new TextEncoder().encode(code),
  );
  return { encryptedCode: toHex(new Uint8Array(ciphertext)), iv: toHex(iv) };
}
async function decryptActivationCode(ciphertext: string, iv: string, env: Env): Promise<string> {
  const plaintext = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: fromHex(iv) },
    await codeCryptoKey(env),
    fromHex(ciphertext),
  );
  return new TextDecoder().decode(plaintext);
}

function equalSecret(a: string, b: string): boolean {
  const left = new TextEncoder().encode(a);
  const right = new TextEncoder().encode(b);
  if (left.length !== right.length) return false;
  let diff = 0;
  for (let i = 0; i < left.length; i++) diff |= left[i] ^ right[i];
  return diff === 0;
}

function bearer(request: Request): string {
  const value = request.headers.get("authorization") || "";
  return value.startsWith("Bearer ") ? value.slice(7) : "";
}

function cors(request: Request, env: Env): Headers {
  const headers = new Headers(JSON_HEADERS);
  const origin = request.headers.get("origin");
  if (origin && env.ALLOWED_ORIGIN && origin === env.ALLOWED_ORIGIN) {
    headers.set("access-control-allow-origin", origin);
    headers.set("vary", "Origin");
  }
  headers.set("access-control-allow-methods", "GET, POST, PUT, DELETE, OPTIONS");
  headers.set("access-control-allow-headers", "authorization, content-type");
  return headers;
}

function response(request: Request, env: Env, body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: cors(request, env) });
}

async function readJson(request: Request): Promise<Record<string, unknown> | null> {
  try {
    const value = await request.json();
    return value && typeof value === "object" && !Array.isArray(value)
      ? value as Record<string, unknown> : null;
  } catch {
    return null;
  }
}

async function requireAdmin(request: Request, env: Env): Promise<boolean> {
  const supplied = bearer(request);
  return !!env.ADMIN_TOKEN && !!supplied && equalSecret(supplied, env.ADMIN_TOKEN);
}

async function claimDevice(request: Request, env: Env): Promise<Response> {
  const body = await readJson(request);
  const code = typeof body?.code === "string" ? normalizeCode(body.code) : "";
  const installationId = typeof body?.installation_id === "string" ? body.installation_id.trim() : "";
  const label = typeof body?.device_label === "string" ? body.device_label.trim().slice(0, 100) : "";
  if (code.length !== 20 || !/^[A-Z0-9]+$/.test(code) ||
      installationId.length < 16 || installationId.length > 128) {
    return response(request, env, { error: "invalid_request" }, 400);
  }

  const codeHash = await sha256(code);
  const existing = await env.DB.prepare(
    `SELECT d.id, d.license_id, d.token_hash, l.plan, l.status, l.device_limit, l.expires_at
     FROM devices d JOIN licenses l ON l.id=d.license_id
     JOIN activation_codes c ON c.license_id=l.id
     WHERE c.code_hash=? AND d.installation_id=? AND d.revoked_at IS NULL
       AND c.revoked_at IS NULL AND l.status='active'
       AND (l.expires_at IS NULL OR l.expires_at > strftime('%Y-%m-%dT%H:%M:%fZ','now'))`
  ).bind(codeHash, installationId).first<any>();

  if (existing) {
    // The raw token cannot be recovered from its hash. If the same installation
    // presents the valid activation code again, rotate the token so a device can
    // recover after its local token is lost or the server database is restored.
    const deviceToken = randomHex(32);
    const tokenHash = await sha256(deviceToken);
    await env.DB.prepare(
      "UPDATE devices SET token_hash=?, last_seen_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=? AND revoked_at IS NULL"
    ).bind(tokenHash, existing.id).run();
    const count = await env.DB.prepare(
      "SELECT COUNT(*) AS n FROM devices WHERE license_id=? AND revoked_at IS NULL"
    ).bind(existing.license_id).first<any>();
    return response(request, env, {
      device_token: deviceToken,
      plan: existing.plan,
      active_devices: Number(count?.n || 1),
      device_limit: existing.device_limit,
      features: FEATURES,
      recovered: true,
    }, 200);
  }

  const codeRow = await env.DB.prepare(
    `SELECT c.id AS code_id, c.license_id, c.expires_at, l.plan, l.status, l.device_limit, l.expires_at AS license_expires_at, l.duration_days
     FROM activation_codes c JOIN licenses l ON l.id=c.license_id
     WHERE c.code_hash=? AND c.revoked_at IS NULL AND l.status='active'
       AND (c.expires_at IS NULL OR c.expires_at > strftime('%Y-%m-%dT%H:%M:%fZ','now'))
       AND (l.expires_at IS NULL OR l.expires_at > strftime('%Y-%m-%dT%H:%M:%fZ','now'))`
  ).bind(codeHash).first<any>();

  if (!codeRow) return response(request, env, { error: "invalid_or_expired_code" }, 404);

  const deviceId = randomHex(16);
  const deviceToken = randomHex(32);
  const tokenHash = await sha256(deviceToken);

  // D1 batch is transactional. The INSERT re-checks the active-device count
  // inside the write transaction, so concurrent claims cannot exceed the limit.
  await env.DB.batch([
    env.DB.prepare(
      `UPDATE licenses SET expires_at=strftime('%Y-%m-%dT%H:%M:%fZ','now', '+' || duration_days || ' days')
       WHERE id=? AND status='active' AND duration_days>0 AND expires_at IS NULL
         AND (SELECT COUNT(*) FROM devices d WHERE d.license_id=licenses.id AND d.revoked_at IS NULL) < device_limit
         AND EXISTS (SELECT 1 FROM activation_codes c WHERE c.license_id=licenses.id AND c.code_hash=?
           AND c.revoked_at IS NULL AND (c.expires_at IS NULL OR c.expires_at > strftime('%Y-%m-%dT%H:%M:%fZ','now')))`
    ).bind(codeRow.license_id, codeHash),
    env.DB.prepare(
      `INSERT INTO devices (id, license_id, installation_id, device_label, token_hash)
       SELECT ?, l.id, ?, ?, ?
       FROM licenses l
       JOIN activation_codes c ON c.license_id=l.id
       WHERE l.id=? AND l.status='active' AND c.code_hash=? AND c.revoked_at IS NULL
         AND (c.expires_at IS NULL OR c.expires_at > strftime('%Y-%m-%dT%H:%M:%fZ','now'))
         AND (l.expires_at IS NULL OR l.expires_at > strftime('%Y-%m-%dT%H:%M:%fZ','now'))
         AND (SELECT COUNT(*) FROM devices d WHERE d.license_id=l.id AND d.revoked_at IS NULL) < l.device_limit
       ON CONFLICT(license_id, installation_id) DO NOTHING`
    ).bind(deviceId, installationId, label || null, tokenHash, codeRow.license_id, codeHash),
  ]);

  const check = await env.DB.prepare(
    `SELECT d.id, d.license_id, l.plan, l.device_limit,
       (SELECT COUNT(*) FROM devices x WHERE x.license_id=l.id AND x.revoked_at IS NULL) AS active_devices
     FROM devices d JOIN licenses l ON l.id=d.license_id
     WHERE d.id=? AND d.token_hash=? AND d.revoked_at IS NULL AND l.status='active'`
  ).bind(deviceId, tokenHash).first<any>();

  if (!check) {
    const count = await env.DB.prepare(
      "SELECT COUNT(*) AS n FROM devices WHERE license_id=? AND revoked_at IS NULL"
    ).bind(codeRow.license_id).first<any>();
    return response(request, env, { error: "device_limit_reached", device_limit: codeRow.device_limit, active_devices: count?.n ?? 0 }, 409);
  }

  return response(request, env, {
    device_token: deviceToken,
    plan: check.plan,
    active_devices: check.active_devices,
    device_limit: check.device_limit,
    features: FEATURES,
  }, 201);
}

async function getLicense(request: Request, env: Env): Promise<Response> {
  const token = bearer(request);
  if (!token) return response(request, env, { error: "unauthorized" }, 401);
  const tokenHash = await sha256(token);
  const row = await env.DB.prepare(
    `SELECT d.id AS device_id, d.installation_id, d.license_id, d.device_label, l.plan, l.status,
       l.device_limit, l.created_at, l.expires_at,
       (SELECT COUNT(*) FROM devices x WHERE x.license_id=l.id AND x.revoked_at IS NULL) AS active_devices
     FROM devices d JOIN licenses l ON l.id=d.license_id
     WHERE d.token_hash=? AND d.revoked_at IS NULL AND l.status='active'
       AND (l.expires_at IS NULL OR l.expires_at > strftime('%Y-%m-%dT%H:%M:%fZ','now'))`
  ).bind(tokenHash).first<any>();
  if (!row) return response(request, env, { error: "license_not_found" }, 404);

  await env.DB.prepare("UPDATE devices SET last_seen_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?")
    .bind(row.device_id).run();

  if (!env.PLUS_SIGNING_PRIVATE_KEY) {
    return response(request, env, { error: "plus_signing_not_configured" }, 503);
  }
  const grant = await signPlusGrant(row, env);
  return response(request, env, {
    grant,
    device_id: row.device_id,
    plan: row.plan,
    status: row.status,
    active_devices: row.active_devices,
    device_limit: row.device_limit,
    expires_at: row.expires_at,
    features: FEATURES,
  });
}


async function authenticatedDevice(request: Request, env: Env): Promise<any | null> {
  const token = bearer(request);
  if (!token) return null;
  const tokenHash = await sha256(token);
  return await env.DB.prepare(
    `SELECT d.id AS device_id, d.license_id, l.plan, l.status
     FROM devices d JOIN licenses l ON l.id=d.license_id
     WHERE d.token_hash=? AND d.revoked_at IS NULL AND l.status='active'
       AND (l.expires_at IS NULL OR l.expires_at > strftime('%Y-%m-%dT%H:%M:%fZ','now'))`
  ).bind(tokenHash).first<any>();
}

async function getDevices(request: Request, env: Env): Promise<Response> {
  const device = await authenticatedDevice(request, env);
  if (!device) return response(request, env, { error: "license_required" }, 401);
  const rows = await env.DB.prepare(
    `SELECT id, device_label, created_at, last_seen_at, revoked_at
     FROM devices WHERE license_id=? AND revoked_at IS NULL ORDER BY created_at DESC`
  ).bind(device.license_id).all<any>();
  const devices = (rows.results || []).map((row: any) => ({
    ...row, current: row.id === device.device_id,
  }));
  const license = await env.DB.prepare(
    `SELECT device_limit,
       (SELECT COUNT(*) FROM devices d WHERE d.license_id=licenses.id AND d.revoked_at IS NULL) AS active_devices
     FROM licenses WHERE id=?`
  ).bind(device.license_id).first<any>();
  return response(request, env, {
    devices,
    active_devices: Number(license?.active_devices || 0),
    device_limit: Number(license?.device_limit || 0),
  });
}

async function revokeOwnDevice(request: Request, env: Env, deviceId: string): Promise<Response> {
  const device = await authenticatedDevice(request, env);
  if (!device) return response(request, env, { error: "license_required" }, 401);
  // A device may revoke itself. Free its installation_id so the same app
  // installation can be enrolled again later with a valid activation code.
  const result = await env.DB.prepare(
    `UPDATE devices
     SET revoked_at=strftime('%Y-%m-%dT%H:%M:%fZ','now'),
         installation_id=installation_id || ':revoked:' || id
     WHERE id=? AND license_id=? AND revoked_at IS NULL`
  ).bind(deviceId, device.license_id).run();
  if (!result.meta.changes) return response(request, env, { error: "device_not_found_or_already_revoked" }, 404);
  return response(request, env, { success: true, device_id: deviceId });
}

async function getSyncItems(request: Request, env: Env): Promise<Response> {
  const device = await authenticatedDevice(request, env);
  if (!device) return response(request, env, { error: "license_required" }, 401);
  const rows = await env.DB.prepare(
    `SELECT item_type, item_key, payload, is_deleted, updated_at, updated_by
     FROM sync_items WHERE license_id=? ORDER BY updated_at ASC`
  ).bind(device.license_id).all<any>();
  const items = (rows.results || []).map((row: any) => ({
    type: row.item_type, key: row.item_key,
    data: JSON.parse(row.payload || "{}"),
    deleted: !!row.is_deleted, updated_at: row.updated_at,
  }));
  await env.DB.prepare("UPDATE devices SET last_seen_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?")
    .bind(device.device_id).run();
  return response(request, env, { items, server_time: Date.now(), plan: device.plan });
}

async function putSyncItems(request: Request, env: Env): Promise<Response> {
  const device = await authenticatedDevice(request, env);
  if (!device) return response(request, env, { error: "license_required" }, 401);
  const body = await readJson(request);
  const items = Array.isArray(body?.items) ? body.items : null;
  if (!items || items.length > 500) {
    return response(request, env, { error: "invalid_items" }, 400);
  }
  const statements: D1PreparedStatement[] = [];
  for (const item of items) {
    if (!item || (item.type !== "favorite" && item.type !== "history") ||
        typeof item.key !== "string" || item.key.length < 1 || item.key.length > 2048 ||
        typeof item.deleted !== "boolean") {
      return response(request, env, { error: "invalid_sync_item" }, 400);
    }
    const updatedAt = Number.isSafeInteger(item.updated_at) && item.updated_at > 0
      ? item.updated_at : Date.now();
    const payload = item.data && typeof item.data === "object" && !Array.isArray(item.data)
      ? JSON.stringify(item.data) : "{}";
    if (payload.length > 12000) return response(request, env, { error: "sync_item_too_large" }, 413);
    statements.push(env.DB.prepare(
      `INSERT INTO sync_items (license_id, item_type, item_key, payload, is_deleted, updated_at, updated_by)
       VALUES (?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(license_id, item_type, item_key) DO UPDATE SET
         payload=excluded.payload, is_deleted=excluded.is_deleted,
         updated_at=excluded.updated_at, updated_by=excluded.updated_by
       WHERE excluded.updated_at > sync_items.updated_at
          OR (excluded.updated_at = sync_items.updated_at AND excluded.updated_by > sync_items.updated_by)`
    ).bind(device.license_id, item.type, item.key, payload, item.deleted ? 1 : 0,
      updatedAt, device.device_id));
  }
  if (statements.length) await env.DB.batch(statements);
  // getSyncItems updates last_seen_at while returning the merged snapshot.
  // Avoid a second identical D1 write on every sync request.
  return await getSyncItems(request, env);
}

async function adminListLicenses(request: Request, env: Env): Promise<Response> {
  if (!await requireAdmin(request, env)) return response(request, env, { error: "unauthorized" }, 401);
  const rows = await env.DB.prepare(
    "SELECT l.id, l.customer_label, l.plan, l.status, l.device_limit, l.created_at, l.revoked_at, " +
    "(SELECT COUNT(*) FROM devices d WHERE d.license_id=l.id AND d.revoked_at IS NULL) AS active_devices, " +
    "(SELECT COUNT(*) FROM devices d WHERE d.license_id=l.id) AS total_devices, " +
    "(SELECT MAX(d.last_seen_at) FROM devices d WHERE d.license_id=l.id AND d.revoked_at IS NULL) AS last_seen_at, " +
    "(SELECT MAX(c.expires_at) FROM activation_codes c WHERE c.license_id=l.id AND c.revoked_at IS NULL) AS code_expires_at, l.duration_days, l.expires_at AS license_expires_at " +
    "FROM licenses l ORDER BY l.created_at DESC LIMIT 500"
  ).all<any>();
  const licenses = (rows.results || []).map((row: any) => ({ ...row, active_devices: Number(row.active_devices || 0), total_devices: Number(row.total_devices || 0) }));
  const counts = await env.DB.prepare(
    "SELECT COUNT(*) AS total_licenses, " +
    "COALESCE(SUM(CASE WHEN status='active' AND (expires_at IS NULL OR expires_at > strftime('%Y-%m-%dT%H:%M:%fZ','now')) THEN 1 ELSE 0 END),0) AS active_licenses, " +
    "COALESCE(SUM(CASE WHEN status='active' AND (expires_at IS NULL OR expires_at > strftime('%Y-%m-%dT%H:%M:%fZ','now')) THEN device_limit ELSE 0 END),0) AS licensed_slots " +
    "FROM licenses"
  ).first<any>();
  const activeDevices = await env.DB.prepare(
    "SELECT COUNT(*) AS active_devices FROM devices d " +
    "JOIN licenses l ON l.id=d.license_id " +
    "WHERE d.revoked_at IS NULL AND l.status='active' AND (l.expires_at IS NULL OR l.expires_at > strftime('%Y-%m-%dT%H:%M:%fZ','now'))"
  ).first<any>();
  const activeDeviceCount = Number(activeDevices?.active_devices || 0);
  const summary = {
    total_licenses: Number(counts?.total_licenses || 0),
    active_licenses: Number(counts?.active_licenses || 0),
    active_devices: activeDeviceCount,
    free_slots: Math.max(0, Number(counts?.licensed_slots || 0) - activeDeviceCount),
  };
  return response(request, env, { licenses, summary, total: summary.total_licenses });
}
async function adminCreateLicense(request: Request, env: Env): Promise<Response> {
  if (!await requireAdmin(request, env)) return response(request, env, { error: "unauthorized" }, 401);
  const body = await readJson(request);
  const label = typeof body?.customer_label === "string" ? body.customer_label.trim().slice(0, 160) : "";
  const licenseDays = body?.license_days === 0 || body?.license_days === null
    ? 0 : Number.isInteger(body?.license_days) ? Number(body?.license_days) : 0;
  const codeDays = 90;
  const deviceLimit = Number.isInteger(body?.device_limit) ? Number(body?.device_limit) : 3;
  if (licenseDays !== 0 && ![7, 30, 60, 90, 180, 365].includes(licenseDays)) {
    return response(request, env, { error: "license_days_must_be_0_7_30_60_90_180_or_365" }, 400);
  }
  if (deviceLimit < 1 || deviceLimit > 10) {
    return response(request, env, { error: "device_limit_must_be_1_to_10" }, 400);
  }

  const licenseId = randomHex(16);
  const activationId = randomHex(16);
  const code = randomCode();
  const codeHash = await sha256(normalizeCode(code));
  const encrypted = await encryptActivationCode(code, env);
  const codeExpiresAt = new Date(Date.now() + codeDays * 86400000).toISOString();

  await env.DB.batch([
    env.DB.prepare(
      "INSERT INTO licenses (id, customer_label, plan, status, device_limit, duration_days, expires_at) VALUES (?, ?, 'plus_lifetime', 'active', ?, ?, NULL)"
    ).bind(licenseId, label || null, deviceLimit, licenseDays),
    env.DB.prepare(
      "INSERT INTO activation_codes (id, license_id, code_hash, expires_at, encrypted_code, encryption_iv) VALUES (?, ?, ?, ?, ?, ?)"
    ).bind(activationId, licenseId, codeHash, codeExpiresAt, encrypted.encryptedCode, encrypted.iv),
  ]);

  return response(request, env, {
    license_id: licenseId,
    activation_code: code,
    code_expires_at: codeExpiresAt,
    code_valid_days: codeDays,
    license_days: licenseDays,
    license_expires_at: null,
    device_limit: deviceLimit,
    plan: "plus_lifetime",
    note: "Код хранится в зашифрованном виде и доступен для просмотра в защищённой панели администратора.",
  }, 201);
}


async function adminListCodes(request: Request, env: Env, licenseId: string): Promise<Response> {
  if (!await requireAdmin(request, env)) return response(request, env, { error: "unauthorized" }, 401);
  const license = await env.DB.prepare(
    "SELECT id, customer_label, plan, status, duration_days, expires_at FROM licenses WHERE id=?"
  ).bind(licenseId).first<any>();
  if (!license) return response(request, env, { error: "license_not_found" }, 404);
  const rows = await env.DB.prepare(
    "SELECT id, expires_at, created_at, revoked_at, encrypted_code, encryption_iv FROM activation_codes WHERE license_id=? ORDER BY created_at DESC"
  ).bind(licenseId).all<any>();
  const now = Date.now();
  const codes = await Promise.all((rows.results || []).map(async (row: any) => ({
    id: row.id,
    expires_at: row.expires_at,
    created_at: row.created_at,
    revoked_at: row.revoked_at,
    status: row.revoked_at ? "revoked" : (row.expires_at && new Date(row.expires_at).getTime() <= now ? "expired" : "active"),
    activation_code: row.encrypted_code && row.encryption_iv
      ? await decryptActivationCode(row.encrypted_code, row.encryption_iv, env) : null,
    recoverable: !!(row.encrypted_code && row.encryption_iv),
  })));
  return response(request, env, { license, codes });
}

async function adminReissueCode(request: Request, env: Env, licenseId: string): Promise<Response> {
  if (!await requireAdmin(request, env)) return response(request, env, { error: "unauthorized" }, 401);
  const license = await env.DB.prepare("SELECT id, status FROM licenses WHERE id=?").bind(licenseId).first<any>();
  if (!license) return response(request, env, { error: "license_not_found" }, 404);
  if (license.status !== "active") return response(request, env, { error: "license_revoked" }, 409);
  const body = await readJson(request);
  const days = body?.code_valid_days === undefined ? 90 : Number(body.code_valid_days);
  if (!Number.isInteger(days) || days < 1 || days > 365) {
    return response(request, env, { error: "code_valid_days_must_be_1_to_365" }, 400);
  }
  const code = randomCode();
  const codeHash = await sha256(normalizeCode(code));
  const encrypted = await encryptActivationCode(code, env);
  const expiresAt = new Date(Date.now() + days * 86400000).toISOString();
  const id = randomHex(16);
  await env.DB.batch([
    env.DB.prepare(
      "UPDATE activation_codes SET revoked_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE license_id=? AND revoked_at IS NULL"
    ).bind(licenseId),
    env.DB.prepare(
      "INSERT INTO activation_codes (id, license_id, code_hash, expires_at, encrypted_code, encryption_iv) VALUES (?, ?, ?, ?, ?, ?)"
    ).bind(id, licenseId, codeHash, expiresAt, encrypted.encryptedCode, encrypted.iv),
  ]);
  return response(request, env, { success: true, code_id: id, activation_code: code, code_expires_at: expiresAt }, 201);
}

async function adminListDevices(request: Request, env: Env, licenseId: string): Promise<Response> {
  if (!await requireAdmin(request, env)) return response(request, env, { error: "unauthorized" }, 401);
  const license = await env.DB.prepare(
    "SELECT id, customer_label, plan, status, device_limit, duration_days, expires_at, created_at FROM licenses WHERE id=?"
  ).bind(licenseId).first<any>();
  if (!license) return response(request, env, { error: "license_not_found" }, 404);
  const devices = await env.DB.prepare(
    `SELECT id, device_label, created_at, last_seen_at, revoked_at
     FROM devices WHERE license_id=? ORDER BY created_at DESC`
  ).bind(licenseId).all<any>();
  const codes = await env.DB.prepare(
    `SELECT id, created_at, expires_at, revoked_at
     FROM activation_codes WHERE license_id=? ORDER BY created_at DESC`
  ).bind(licenseId).all<any>();
  const now = Date.now();
  const activationCodes = (codes.results || []).map((code: any) => ({
    id: code.id,
    created_at: code.created_at,
    expires_at: code.expires_at,
    revoked_at: code.revoked_at,
    status: code.revoked_at ? "revoked" :
      (code.expires_at && new Date(code.expires_at).getTime() <= now ? "expired" : "active"),
    code_display: "Полный код показывался только при выдаче",
  }));
  return response(request, env, { license, devices: devices.results, activation_codes: activationCodes });
}

async function adminRevokeDevice(request: Request, env: Env, deviceId: string): Promise<Response> {
  if (!await requireAdmin(request, env)) return response(request, env, { error: "unauthorized" }, 401);
  const result = await env.DB.prepare(
    "UPDATE devices SET revoked_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=? AND revoked_at IS NULL"
  ).bind(deviceId).run();
  if (!result.meta.changes) return response(request, env, { error: "device_not_found_or_already_revoked" }, 404);
  return response(request, env, { success: true, device_id: deviceId });
}

async function adminDeleteLicense(request: Request, env: Env, licenseId: string): Promise<Response> {
  if (!await requireAdmin(request, env)) return response(request, env, { error: "unauthorized" }, 401);
  const exists = await env.DB.prepare("SELECT id FROM licenses WHERE id=?").bind(licenseId).first<any>();
  if (!exists) return response(request, env, { error: "license_not_found" }, 404);
  await env.DB.batch([
    env.DB.prepare("DELETE FROM sync_items WHERE license_id=?").bind(licenseId),
    env.DB.prepare("DELETE FROM activation_codes WHERE license_id=?").bind(licenseId),
    env.DB.prepare("DELETE FROM devices WHERE license_id=?").bind(licenseId),
    env.DB.prepare("DELETE FROM licenses WHERE id=?").bind(licenseId),
  ]);
  return response(request, env, { success: true, deleted_license_id: licenseId });
}

async function adminDeleteAllData(request: Request, env: Env): Promise<Response> {
  if (!await requireAdmin(request, env)) return response(request, env, { error: "unauthorized" }, 401);
  const counts = await env.DB.prepare(
    "SELECT (SELECT COUNT(*) FROM licenses) AS licenses, " +
    "(SELECT COUNT(*) FROM activation_codes) AS activation_codes, " +
    "(SELECT COUNT(*) FROM devices) AS devices, " +
    "(SELECT COUNT(*) FROM sync_items) AS sync_items"
  ).first<any>();
  await env.DB.batch([
    env.DB.prepare("DELETE FROM sync_items"),
    env.DB.prepare("DELETE FROM activation_codes"),
    env.DB.prepare("DELETE FROM devices"),
    env.DB.prepare("DELETE FROM licenses"),
  ]);
  return response(request, env, { success: true, deleted: {
    licenses: Number(counts?.licenses || 0),
    activation_codes: Number(counts?.activation_codes || 0),
    devices: Number(counts?.devices || 0),
    sync_items: Number(counts?.sync_items || 0),
  } });
}

async function adminRevokeLicense(request: Request, env: Env, licenseId: string): Promise<Response> {
  if (!await requireAdmin(request, env)) return response(request, env, { error: "unauthorized" }, 401);
  const result = await env.DB.prepare(
    "UPDATE licenses SET status='revoked', revoked_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=? AND status='active'"
  ).bind(licenseId).run();
  if (!result.meta.changes) return response(request, env, { error: "license_not_found_or_already_revoked" }, 404);
  return response(request, env, { success: true, license_id: licenseId });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors(request, env) });
    if (!env.DB || !env.ADMIN_TOKEN) return response(request, env, { error: "server_not_configured" }, 503);

    try {
      if (request.method === "GET" && url.pathname === "/health") {
        return response(request, env, { ok: true, service: "kinozal-tv-license" });
      }
      if (request.method === "POST" && url.pathname === "/v1/devices/claim") {
        return await claimDevice(request, env);
      }
      if (request.method === "GET" && url.pathname === "/v1/sync") {
        return await getSyncItems(request, env);
      }
      if (request.method === "PUT" && url.pathname === "/v1/sync") {
        return await putSyncItems(request, env);
      }
      if (request.method === "GET" && url.pathname === "/v1/license") {
        return await getLicense(request, env);
      }
      if (request.method === "GET" && url.pathname === "/v1/devices") {
        return await getDevices(request, env);
      }
      const ownDeviceMatch = url.pathname.match(/^\/v1\/devices\/([a-f0-9]{32})$/);
      if (request.method === "DELETE" && ownDeviceMatch) {
        return await revokeOwnDevice(request, env, ownDeviceMatch[1]);
      }
      if (request.method === "GET" && url.pathname === "/admin/licenses") {
        return await adminListLicenses(request, env);
      }
      if (request.method === "DELETE" && url.pathname === "/admin/data") {
        return await adminDeleteAllData(request, env);
      }
      if (request.method === "POST" && url.pathname === "/admin/licenses") {
        return await adminCreateLicense(request, env);
      }
      const deleteLicenseMatch = url.pathname.match(/^\/admin\/licenses\/([a-f0-9]{32})\/delete$/);
      if (request.method === "DELETE" && deleteLicenseMatch) {
        return await adminDeleteLicense(request, env, deleteLicenseMatch[1]);
      }
      const codesMatch = url.pathname.match(/^\/admin\/licenses\/([a-f0-9]{32})\/codes$/);
      if (request.method === "GET" && codesMatch) return await adminListCodes(request, env, codesMatch[1]);
      const reissueMatch = url.pathname.match(/^\/admin\/licenses\/([a-f0-9]{32})\/activation-code$/);
      if (request.method === "POST" && reissueMatch) return await adminReissueCode(request, env, reissueMatch[1]);
      const devicesMatch = url.pathname.match(/^\/admin\/licenses\/([a-f0-9]{32})\/devices$/);
      if (request.method === "GET" && devicesMatch) {
        return await adminListDevices(request, env, devicesMatch[1]);
      }
      const revokeDeviceMatch = url.pathname.match(/^\/admin\/devices\/([a-f0-9]{32})$/);
      if (request.method === "DELETE" && revokeDeviceMatch) {
        return await adminRevokeDevice(request, env, revokeDeviceMatch[1]);
      }
      const revokeLicenseMatch = url.pathname.match(/^\/admin\/licenses\/([a-f0-9]{32})$/);
      if (request.method === "DELETE" && revokeLicenseMatch) {
        return await adminRevokeLicense(request, env, revokeLicenseMatch[1]);
      }
      return response(request, env, { error: "not_found" }, 404);
    } catch (error) {
      // Never log credentials, activation codes, request bodies or device tokens.
      console.error("license_api_error", error instanceof Error ? error.message : "unknown");
      return response(request, env, { error: "internal_error" }, 500);
    }
  },
};
