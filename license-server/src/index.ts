export interface Env {
  DB: D1Database;
  ADMIN_TOKEN: string;
  ALLOWED_ORIGIN?: string;
}

const JSON_HEADERS = { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" };
const FEATURES = [
  "proxy",
  "advanced_search",
  "mirror_selection",
  "favorites",
  "viewing_history",
  "collections",
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
  headers.set("access-control-allow-methods", "GET, POST, DELETE, OPTIONS");
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
    `SELECT d.id, d.license_id, d.token_hash, l.plan, l.status, l.device_limit
     FROM devices d JOIN licenses l ON l.id=d.license_id
     JOIN activation_codes c ON c.license_id=l.id
     WHERE c.code_hash=? AND d.installation_id=? AND d.revoked_at IS NULL
       AND c.revoked_at IS NULL AND l.status='active'`
  ).bind(codeHash, installationId).first<any>();

  if (existing) {
    // The device token is intentionally never recoverable from its stored hash.
    // A repeat claim must not silently replace credentials; ask the user to contact support.
    return response(request, env, { error: "device_already_activated", message: "Это устройство уже активировано. Обратитесь в поддержку, если нужно восстановить доступ." }, 409);
  }

  const codeRow = await env.DB.prepare(
    `SELECT c.id AS code_id, c.license_id, c.expires_at, l.plan, l.status, l.device_limit
     FROM activation_codes c JOIN licenses l ON l.id=c.license_id
     WHERE c.code_hash=? AND c.revoked_at IS NULL AND l.status='active'
       AND (c.expires_at IS NULL OR c.expires_at > strftime('%Y-%m-%dT%H:%M:%fZ','now'))`
  ).bind(codeHash).first<any>();

  if (!codeRow) return response(request, env, { error: "invalid_or_expired_code" }, 404);

  const deviceId = randomHex(16);
  const deviceToken = randomHex(32);
  const tokenHash = await sha256(deviceToken);

  // D1 batch is transactional. The INSERT re-checks the active-device count
  // inside the write transaction, so concurrent claims cannot exceed the limit.
  const inserted = await env.DB.batch([
    env.DB.prepare(
      `INSERT INTO devices (id, license_id, installation_id, device_label, token_hash)
       SELECT ?, l.id, ?, ?, ?
       FROM licenses l
       JOIN activation_codes c ON c.license_id=l.id
       WHERE l.id=? AND l.status='active' AND c.code_hash=? AND c.revoked_at IS NULL
         AND (c.expires_at IS NULL OR c.expires_at > strftime('%Y-%m-%dT%H:%M:%fZ','now'))
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
    `SELECT d.id AS device_id, d.license_id, d.device_label, l.plan, l.status,
       l.device_limit, l.created_at,
       (SELECT COUNT(*) FROM devices x WHERE x.license_id=l.id AND x.revoked_at IS NULL) AS active_devices
     FROM devices d JOIN licenses l ON l.id=d.license_id
     WHERE d.token_hash=? AND d.revoked_at IS NULL AND l.status='active'`
  ).bind(tokenHash).first<any>();
  if (!row) return response(request, env, { error: "license_not_found" }, 404);

  await env.DB.prepare("UPDATE devices SET last_seen_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?")
    .bind(row.device_id).run();

  return response(request, env, {
    plan: row.plan,
    status: row.status,
    active_devices: row.active_devices,
    device_limit: row.device_limit,
    features: FEATURES,
  });
}

async function adminCreateLicense(request: Request, env: Env): Promise<Response> {
  if (!await requireAdmin(request, env)) return response(request, env, { error: "unauthorized" }, 401);
  const body = await readJson(request);
  const label = typeof body?.customer_label === "string" ? body.customer_label.trim().slice(0, 160) : "";
  const expiryDays = Number.isInteger(body?.code_valid_days) ? Number(body?.code_valid_days) : 90;
  if (expiryDays < 1 || expiryDays > 365) {
    return response(request, env, { error: "code_valid_days_must_be_1_to_365" }, 400);
  }

  const licenseId = randomHex(16);
  const activationId = randomHex(16);
  const code = randomCode();
  const codeHash = await sha256(normalizeCode(code));
  const expiresAt = new Date(Date.now() + expiryDays * 86400000).toISOString();

  await env.DB.batch([
    env.DB.prepare(
      "INSERT INTO licenses (id, customer_label, plan, status, device_limit) VALUES (?, ?, 'plus_lifetime', 'active', 3)"
    ).bind(licenseId, label || null),
    env.DB.prepare(
      "INSERT INTO activation_codes (id, license_id, code_hash, expires_at) VALUES (?, ?, ?, ?)"
    ).bind(activationId, licenseId, codeHash, expiresAt),
  ]);

  return response(request, env, {
    license_id: licenseId,
    activation_code: code,
    code_expires_at: expiresAt,
    device_limit: 3,
    plan: "plus_lifetime",
    note: "Код показывается только один раз. Передайте его покупателю безопасным способом.",
  }, 201);
}

async function adminListDevices(request: Request, env: Env, licenseId: string): Promise<Response> {
  if (!await requireAdmin(request, env)) return response(request, env, { error: "unauthorized" }, 401);
  const license = await env.DB.prepare(
    "SELECT id, customer_label, plan, status, device_limit, created_at FROM licenses WHERE id=?"
  ).bind(licenseId).first<any>();
  if (!license) return response(request, env, { error: "license_not_found" }, 404);
  const devices = await env.DB.prepare(
    `SELECT id, installation_id, device_label, created_at, last_seen_at, revoked_at
     FROM devices WHERE license_id=? ORDER BY created_at DESC`
  ).bind(licenseId).all();
  return response(request, env, { license, devices: devices.results });
}

async function adminRevokeDevice(request: Request, env: Env, deviceId: string): Promise<Response> {
  if (!await requireAdmin(request, env)) return response(request, env, { error: "unauthorized" }, 401);
  const result = await env.DB.prepare(
    "UPDATE devices SET revoked_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=? AND revoked_at IS NULL"
  ).bind(deviceId).run();
  if (!result.meta.changes) return response(request, env, { error: "device_not_found_or_already_revoked" }, 404);
  return response(request, env, { success: true, device_id: deviceId });
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
      if (request.method === "GET" && url.pathname === "/v1/license") {
        return await getLicense(request, env);
      }
      if (request.method === "POST" && url.pathname === "/admin/licenses") {
        return await adminCreateLicense(request, env);
      }
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
