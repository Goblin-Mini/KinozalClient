export interface Env {
  DB: D1Database;
  ADMIN_TOKEN: string;
  ALLOWED_ORIGIN?: string;
}

const JSON_HEADERS = { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" };
const ADMIN_HTML = String.raw`<!doctype html>
<html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Кинозал ТВ — Plus Admin</title>
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self';">
<style>
:root{color-scheme:dark;--bg:#101114;--panel:#191b20;--line:#30343d;--muted:#a2a7b2;--accent:#AE3712;--gold:#FCBD2B;--green:#58c58a;--red:#ef6969}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:#f4f4f5;font:14px/1.45 system-ui,Segoe UI,Arial}header{padding:20px max(20px,calc((100% - 1180px)/2));border-bottom:1px solid var(--line);display:flex;justify-content:space-between;align-items:center;gap:16px}h1{font-size:20px;margin:0}main{max-width:1180px;margin:28px auto;padding:0 20px}.muted{color:var(--muted)}.panel{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:18px;margin-bottom:18px}.row{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.stats{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px}.stat strong{display:block;font-size:27px}.stat span{color:var(--muted)}input,button{font:inherit;border-radius:8px;padding:10px 12px;border:1px solid var(--line);background:#101216;color:#fff}input{min-width:0;flex:1}button{cursor:pointer;background:var(--accent);border-color:var(--accent);font-weight:600}button.secondary{background:#242830;border-color:#3a3f49}button.danger{background:#612525;border-color:#8b3838}.hidden{display:none!important}.status{padding:4px 8px;border-radius:20px;background:#263a30;color:#9be1b7;font-size:12px}.status.off{background:#472929;color:#ffb0b0}table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:12px 9px;border-bottom:1px solid var(--line);vertical-align:middle}th{color:var(--muted);font-weight:500;font-size:12px}.code{font-family:ui-monospace,monospace;word-break:break-all}.notice{padding:12px;border:1px solid #7a5b22;background:#332813;border-radius:8px;color:#ffe0a0;margin:12px 0}.error{color:#ff9d9d}.right{margin-left:auto}@media(max-width:760px){.stats{grid-template-columns:repeat(2,1fr)}table{min-width:760px}.table-wrap{overflow:auto}header{padding:16px 20px}}
</style></head><body>
<header><div><h1>Кинозал ТВ <span style="color:var(--gold)">PLUS</span> · Администрирование</h1><div class="muted">Управление лицензиями и устройствами</div></div><button id="logout" class="secondary hidden">Выйти</button></header>
<main>
<section id="login" class="panel"><h2>Вход администратора</h2><p class="muted">Введи ADMIN_TOKEN из Cloudflare Worker. Токен хранится только в памяти этой вкладки.</p><div class="row"><input id="token" type="password" autocomplete="current-password" placeholder="ADMIN_TOKEN"><button id="connect">Подключиться</button></div><p id="loginError" class="error"></p></section>
<section id="app" class="hidden">
<div class="stats"><div class="panel stat"><strong id="sTotal">—</strong><span>Всего лицензий</span></div><div class="panel stat"><strong id="sActive">—</strong><span>Активных лицензий</span></div><div class="panel stat"><strong id="sDevices">—</strong><span>Активных устройств</span></div><div class="panel stat"><strong id="sSlots">—</strong><span>Свободных слотов</span></div></div>
<section class="panel"><h2 style="margin-top:0">Выдать Plus</h2><div class="muted">Создавай лицензию только после ручной проверки оплаты.</div><div class="row" style="margin-top:14px"><input id="customer" maxlength="160" placeholder="Покупатель / номер заказа"><input id="days" type="number" min="1" max="365" value="90" style="max-width:130px;flex:0 0 130px" title="Срок действия кода, дней"><button id="issue">Создать лицензию</button></div><div class="muted" style="margin-top:8px">Код активации действует 1–365 дней. После активации Plus бессрочный, до 3 устройств.</div><div id="issued" class="notice hidden"></div><p id="issueError" class="error"></p></section>
<section class="panel"><div class="row"><div><h2 style="margin:0">Лицензии</h2><div class="muted">Последние 500 записей</div></div><input id="filter" placeholder="Поиск по покупателю или ID" style="max-width:320px"><button id="refresh" class="secondary">Обновить</button></div><div class="table-wrap" style="margin-top:14px"><table><thead><tr><th>Покупатель</th><th>Статус</th><th>Устройства</th><th>Создана</th><th>Код до</th><th>Действия</th></tr></thead><tbody id="licenses"></tbody></table></div><p id="listError" class="error"></p></section>
<section id="devicePanel" class="panel hidden"><div class="row"><div><h2 style="margin:0">Устройства лицензии</h2><div id="deviceSubtitle" class="muted"></div></div><button id="closeDevices" class="secondary right">Закрыть</button></div><div class="table-wrap"><table><thead><tr><th>Название</th><th>Добавлено</th><th>Последняя активность</th><th>Статус</th><th></th></tr></thead><tbody id="devices"></tbody></table></div><p id="deviceError" class="error"></p></section>
</section></main>
<script>
let adminToken="",licenseRows=[];
const $=id=>document.getElementById(id);
const esc=s=>String(s??"").replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
const date=s=>s?new Date(s).toLocaleString("ru-RU"):"—";
async function api(path,options={}){const r=await fetch(path,{...options,headers:{"Authorization":"Bearer "+adminToken,"Content-Type":"application/json",...(options.headers||{})}});const d=await r.json().catch(()=>({error:"invalid_response"}));if(!r.ok)throw new Error(d.message||d.error||("HTTP "+r.status));return d}
function login(){ $("login").classList.remove("hidden");$("app").classList.add("hidden");$("logout").classList.add("hidden");adminToken="";}
async function load(){ $("listError").textContent="";try{const d=await api("/admin/licenses");licenseRows=d.licenses||[];render();const a=licenseRows.filter(x=>x.status==="active");const n=a.reduce((v,x)=>v+x.active_devices,0);$("sTotal").textContent=licenseRows.length;$("sActive").textContent=a.length;$("sDevices").textContent=n;$("sSlots").textContent=a.reduce((v,x)=>v+Math.max(0,x.device_limit-x.active_devices),0)}catch(e){$("listError").textContent=e.message;if(e.message==="unauthorized")login()}}
function render(){const q=$("filter").value.trim().toLowerCase();$("licenses").innerHTML=licenseRows.filter(x=>(x.customer_label||"").toLowerCase().includes(q)||x.id.toLowerCase().includes(q)).map(x=>'<tr><td><b>'+esc(x.customer_label||"Без подписи")+'</b><div class="muted code">'+esc(x.id)+'</div></td><td><span class="status '+(x.status==="active"?"":"off")+'">'+(x.status==="active"?"Активна":"Отозвана")+'</span></td><td>'+x.active_devices+' / '+x.device_limit+'</td><td>'+date(x.created_at)+'</td><td>'+date(x.code_expires_at)+'</td><td><div class="row"><button class="secondary" data-dev="'+esc(x.id)+'">Устройства</button>'+(x.status==="active"?'<button class="danger" data-revoke="'+esc(x.id)+'">Отозвать</button>':"")+'</div></td></tr>').join("")||'<tr><td colspan="6" class="muted">Ничего не найдено</td></tr>'}
$("connect").onclick=async()=>{adminToken=$("token").value.trim();$("loginError").textContent="";if(!adminToken)return;$("connect").disabled=true;try{await api("/admin/licenses");$("login").classList.add("hidden");$("app").classList.remove("hidden");$("logout").classList.remove("hidden");await load()}catch(e){adminToken="";$("loginError").textContent="Не удалось войти: "+e.message}finally{$("connect").disabled=false}};
$("logout").onclick=login;$("refresh").onclick=load;$("filter").oninput=render;
$("issue").onclick=async()=>{const label=$("customer").value.trim(),days=Number($("days").value);$("issueError").textContent="";$("issued").classList.add("hidden");if(!Number.isInteger(days)||days<1||days>365){$("issueError").textContent="Срок кода должен быть от 1 до 365 дней";return}$("issue").disabled=true;try{const d=await api("/admin/licenses",{method:"POST",body:JSON.stringify({customer_label:label,code_valid_days:days})});$("issued").innerHTML='<b>Лицензия создана. Скопируй код сейчас — повторно получить его нельзя.</b><p class="code" id="newCode">'+esc(d.activation_code)+'</p><div class="row"><button id="copyCode">Копировать код</button><span class="muted">До '+date(d.code_expires_at)+'</span></div>';$("issued").classList.remove("hidden");$("copyCode").onclick=async()=>{await navigator.clipboard.writeText(d.activation_code);$("copyCode").textContent="Скопировано"};$("customer").value="";await load()}catch(e){$("issueError").textContent=e.message}finally{$("issue").disabled=false}};
$("licenses").onclick=async e=>{const dev=e.target.closest("[data-dev]")?.dataset.dev,rev=e.target.closest("[data-revoke]")?.dataset.revoke;if(dev)await showDevices(dev);if(rev&&confirm("Отозвать лицензию целиком? Все устройства потеряют Plus и синхронизацию.")){try{await api("/admin/licenses/"+rev,{method:"DELETE"});await load()}catch(err){$("listError").textContent=err.message}}};
async function showDevices(id){$("devicePanel").classList.remove("hidden");$("deviceSubtitle").textContent=id;$("devices").innerHTML="";$("deviceError").textContent="";try{const d=await api("/admin/licenses/"+id+"/devices");$("deviceSubtitle").textContent=(d.license.customer_label||id)+" · "+id;$("devices").innerHTML=(d.devices||[]).map(x=>'<tr><td>'+esc(x.device_label||"Устройство")+'<div class="muted code">'+esc(x.id)+'</div></td><td>'+date(x.created_at)+'</td><td>'+date(x.last_seen_at)+'</td><td><span class="status '+(x.revoked_at?"off":"")+'">'+(x.revoked_at?"Отключено":"Активно")+'</span></td><td>'+(x.revoked_at?"":'<button class="danger" data-device-revoke="'+esc(x.id)+'">Отключить</button>')+'</td></tr>').join("")||'<tr><td colspan="5">Устройств пока нет</td></tr>'}catch(e){$("deviceError").textContent=e.message}}
$("devices").onclick=async e=>{const id=e.target.closest("[data-device-revoke]")?.dataset.deviceRevoke;if(id&&confirm("Отключить это устройство? Освободится один слот.")){try{await api("/admin/devices/"+id,{method:"DELETE"});const licenseId=$("deviceSubtitle").textContent.split(" · ").pop();await showDevices(licenseId);await load()}catch(err){$("deviceError").textContent=err.message}}};
$("closeDevices").onclick=()=>$("devicePanel").classList.add("hidden");
</script></body></html>`;

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


async function authenticatedDevice(request: Request, env: Env): Promise<any | null> {
  const token = bearer(request);
  if (!token) return null;
  const tokenHash = await sha256(token);
  return await env.DB.prepare(
    `SELECT d.id AS device_id, d.license_id, l.plan, l.status
     FROM devices d JOIN licenses l ON l.id=d.license_id
     WHERE d.token_hash=? AND d.revoked_at IS NULL AND l.status='active'`
  ).bind(tokenHash).first<any>();
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
  await env.DB.prepare("UPDATE devices SET last_seen_at=strftime('%Y-%m-%dT%H:%M:%fZ','now') WHERE id=?")
    .bind(device.device_id).run();
  return await getSyncItems(request, env);
}

async function adminListLicenses(request: Request, env: Env): Promise<Response> {
  if (!await requireAdmin(request, env)) return response(request, env, { error: "unauthorized" }, 401);
  const rows = await env.DB.prepare(
    "SELECT l.id, l.customer_label, l.plan, l.status, l.device_limit, l.created_at, l.revoked_at, " +
    "(SELECT COUNT(*) FROM devices d WHERE d.license_id=l.id AND d.revoked_at IS NULL) AS active_devices, " +
    "(SELECT COUNT(*) FROM devices d WHERE d.license_id=l.id) AS total_devices, " +
    "(SELECT MAX(d.last_seen_at) FROM devices d WHERE d.license_id=l.id AND d.revoked_at IS NULL) AS last_seen_at, " +
    "(SELECT MAX(c.expires_at) FROM activation_codes c WHERE c.license_id=l.id AND c.revoked_at IS NULL) AS code_expires_at " +
    "FROM licenses l ORDER BY l.created_at DESC LIMIT 500"
  ).all<any>();
  const licenses = (rows.results || []).map((row: any) => ({ ...row, active_devices: Number(row.active_devices || 0), total_devices: Number(row.total_devices || 0) }));
  return response(request, env, { licenses, total: licenses.length });
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
      if (request.method === "GET" && url.pathname === "/admin") {
        return new Response(ADMIN_HTML, { headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store", "x-content-type-options": "nosniff", "referrer-policy": "no-referrer" } });
      }
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
      if (request.method === "GET" && url.pathname === "/admin/licenses") {
        return await adminListLicenses(request, env);
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
