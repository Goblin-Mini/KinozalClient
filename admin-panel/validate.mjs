import { readFileSync } from "node:fs";
import { Script } from "node:vm";

const html = readFileSync(new URL("./index.html", import.meta.url), "utf8");
const scripts = [...html.matchAll(/<script>([\s\S]*?)<\/script>/gi)];
if (scripts.length !== 1) {
  throw new Error("Expected exactly one inline admin script");
}
new Script(scripts[0][1], { filename: "admin-panel/index.html:inline-script" });
if (!html.includes("https://kinozal-tv-license.goblinmini-4pda.workers.dev")) {
  throw new Error("Default Worker API URL is missing");
}
if (!html.includes("ADMIN_TOKEN") || !html.includes("Платежи: ручное подтверждение")) {
  throw new Error("Expected admin login or manual-payment notice is missing");
}
if (/localStorage\.(?:setItem|getItem)\s*\(\s*["'][^"']*token/i.test(html)) {
  throw new Error("Admin token must never be persisted in localStorage");
}
process.stdout.write("Admin panel syntax and safety checks passed.\\n");
