const CONFIG_KEY = "app_config";
const AUTH_FAIL_PREFIX = "auth_fail:";
const AUTH_WINDOW_SECONDS = 30;
const AUTH_FAILURE_TTL_SECONDS = 600;
const AUTH_FAILURE_LIMIT = 10;
const SESSION_SECONDS = 8 * 60 * 60;
const REQUEST_WINDOW_SECONDS = 120;
const DEFAULT_CONFIG = {
  version: 1,
  sub_url: "",
  sub_name: "默认订阅",
  force_replace: true,
  update_interval_minutes: 10,
};

export default {
  async fetch(request, env, ctx) {
    try {
      return await route(request, env, ctx);
    } catch (error) {
      console.error("Unhandled Worker error", error);
      return json({ error: "Internal Server Error", code: "INTERNAL_ERROR" }, 500);
    }
  },
};

async function route(request, env, ctx) {
  const url = new URL(request.url);

  if (request.method === "GET" && url.pathname === "/app-config.json") {
    return json(await readLegacyConfig(env), 200, { "Cache-Control": "no-store" });
  }
  if (url.pathname === "/admin/login") return handleAdminLogin(request, env);
  if (url.pathname === "/admin/logout") return logoutResponse();
  if (request.method === "GET" && url.pathname === "/admin") {
    if (!(await validAdminSession(request, env))) return redirect("/admin/login");
    return redirect("/admin/templates");
  }
  if (request.method === "GET" && ["/admin/templates", "/admin/invites", "/admin/devices"].includes(url.pathname)) {
    if (!(await validAdminSession(request, env))) return redirect("/admin/login");
    return html(renderAdminPage(url.pathname));
  }
  if (url.pathname.startsWith("/admin/api/")) {
    if (!(await validAdminSession(request, env))) return json({ error: "Unauthorized" }, 401);
    if (request.method !== "GET" && !sameOrigin(request)) return json({ error: "Forbidden" }, 403);
    return handleAdminApi(request, env, ctx);
  }
  if (request.method === "POST" && url.pathname === "/api/v1/activate") {
    return activateDevice(request, env, ctx);
  }
  if (request.method === "GET" && url.pathname === "/api/v1/config") {
    return clientConfig(request, env, ctx);
  }
  if (request.method === "GET" && url.pathname === "/api/v1/subscription") {
    return clientSubscription(request, env, ctx);
  }
  return json({ error: "Not Found" }, 404);
}

async function handleAdminLogin(request, env) {
  if (request.method === "GET") {
    if (await validAdminSession(request, env)) return redirect("/admin");
    return html(renderLoginPage());
  }
  if (request.method !== "POST") return json({ error: "Method Not Allowed" }, 405);
  const body = await request.json().catch(() => ({}));
  const authError = await verifyTotpWithRateLimit(request, env, String(body.code || ""));
  if (authError) return authError;
  const cookie = await createSessionCookie(env);
  return json({ ok: true }, 200, { "Set-Cookie": cookie });
}

async function handleAdminApi(request, env, ctx) {
  const url = new URL(request.url);
  const path = url.pathname;
  if (path === "/admin/api/templates") {
    if (request.method === "GET") {
      const result = await env.DB.prepare(
        "SELECT id,name,upstream_sub_url,enabled,version,update_interval_minutes,created_at,updated_at FROM config_templates ORDER BY created_at DESC"
      ).all();
      return json({ items: result.results });
    }
    if (request.method === "POST") {
      const body = await request.json();
      const item = normalizeTemplate(body);
      const now = nowSeconds();
      const id = crypto.randomUUID();
      await env.DB.prepare(
        "INSERT INTO config_templates(id,name,upstream_sub_url,enabled,version,update_interval_minutes,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)"
      ).bind(id, item.name, item.upstreamSubUrl, item.enabled, item.version, item.interval, now, now).run();
      ctx.waitUntil(audit(env, request, "template.create", id, item.name));
      return json({ id }, 201);
    }
  }
  const templateMatch = path.match(/^\/admin\/api\/templates\/([^/]+)$/);
  if (templateMatch && request.method === "PUT") {
    const body = await request.json();
    const item = normalizeTemplate(body);
    await env.DB.prepare(
      "UPDATE config_templates SET name=?,upstream_sub_url=?,enabled=?,version=?,update_interval_minutes=?,updated_at=? WHERE id=?"
    ).bind(item.name, item.upstreamSubUrl, item.enabled, item.version, item.interval, nowSeconds(), templateMatch[1]).run();
    ctx.waitUntil(audit(env, request, "template.update", templateMatch[1], item.name));
    return json({ ok: true });
  }
  if (templateMatch && request.method === "DELETE") {
    const templateId = templateMatch[1];
    const inUse = await env.DB.prepare("SELECT COUNT(1) AS count FROM invites WHERE template_id=?").bind(templateId).first();
    if (Number(inUse?.count || 0) > 0) {
      return json({ error: "模板仍在使用中", code: "TEMPLATE_IN_USE" }, 409);
    }
    const result = await env.DB.prepare("DELETE FROM config_templates WHERE id=?").bind(templateId).run();
    if (result.meta.changes !== 1) return json({ error: "模板不存在" }, 404);
    ctx.waitUntil(audit(env, request, "template.delete", templateId, ""));
    return json({ ok: true });
  }
  if (path === "/admin/api/invites") {
    if (request.method === "GET") {
      const result = await env.DB.prepare(
        `SELECT i.id,i.code_hint,i.code_value,i.template_id,i.status,i.device_id,i.app_version,i.created_at,
                i.expires_at,i.bound_at,i.last_seen_at,t.name AS template_name
         FROM invites i JOIN config_templates t ON t.id=i.template_id
         ORDER BY i.created_at DESC LIMIT 500`
      ).all();
      return json({ items: result.results });
    }
    if (request.method === "POST") {
      const body = await request.json();
      const templateId = String(body.template_id || "");
      const count = clampInt(body.count, 1, 50, 1);
      const now = nowSeconds();
      const expireDays = body.expire_days === undefined || body.expire_days === null
        ? null
        : clampInt(body.expire_days, 0, 36500, 0);
      const expiresAt = expireDays === null
        ? (body.expires_at ? Math.floor(new Date(body.expires_at).getTime() / 1000) : null)
        : (expireDays === 0 ? null : now + expireDays * 24 * 60 * 60);
      const template = await env.DB.prepare("SELECT id FROM config_templates WHERE id=?").bind(templateId).first();
      if (!template) return json({ error: "配置模板不存在" }, 400);
      const codes = [];
      const statements = [];
      for (let index = 0; index < count; index += 1) {
        const code = generateInviteCode();
        codes.push(code);
        statements.push(env.DB.prepare(
          "INSERT INTO invites(id,code_hash,code_hint,code_value,template_id,status,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?)"
        ).bind(crypto.randomUUID(), await sha256Hex(normalizeInviteCode(code)), code.slice(-4), code, templateId, "unused", now, expiresAt));
      }
      await env.DB.batch(statements);
      ctx.waitUntil(audit(env, request, "invite.create", templateId, `count=${count}`));
      return json({ codes }, 201);
    }
  }
  const actionMatch = path.match(/^\/admin\/api\/invites\/([^/]+)\/action$/);
  if (actionMatch && request.method === "POST") {
    const body = await request.json();
    const action = String(body.action || "");
    const id = actionMatch[1];
    if (action === "revoke") {
      const invite = await env.DB.prepare("SELECT device_id FROM invites WHERE id=?").bind(id).first();
      if (!invite) return json({ error: "邀请码不存在" }, 404);
      if (invite.device_id) {
        await env.DB.prepare("DELETE FROM request_nonces WHERE device_id=?").bind(invite.device_id).run();
      }
      await env.DB.prepare("DELETE FROM invites WHERE id=?").bind(id).run();
    } else if (action === "restore") {
      await env.DB.prepare("UPDATE invites SET status=CASE WHEN device_id IS NULL THEN 'unused' ELSE 'active' END WHERE id=?").bind(id).run();
    } else if (action === "unbind") {
      const invite = await env.DB.prepare("SELECT device_id FROM invites WHERE id=?").bind(id).first();
      if (invite?.device_id) {
        await env.DB.prepare("DELETE FROM request_nonces WHERE device_id=?").bind(invite.device_id).run();
      }
      await env.DB.prepare(
        "UPDATE invites SET status='unused',device_id=NULL,device_public_key=NULL,key_alg=NULL,app_version=NULL,bound_at=NULL,last_seen_at=NULL WHERE id=?"
      ).bind(id).run();
    } else if (action === "assign") {
      await env.DB.prepare("UPDATE invites SET template_id=? WHERE id=?").bind(String(body.template_id || ""), id).run();
    } else {
      return json({ error: "未知操作" }, 400);
    }
    ctx.waitUntil(audit(env, request, `invite.${action}`, id, ""));
    return json({ ok: true });
  }
  return json({ error: "Not Found" }, 404);
}

async function activateDevice(request, env, ctx) {
  const ip = request.headers.get("CF-Connecting-IP") || "unknown";
  const rateKey = `activate_fail:${ip}`;
  const failures = Number.parseInt((await env.CONFIG_KV.get(rateKey)) || "0", 10) || 0;
  if (failures >= 10) return json({ error: "尝试次数过多", code: "RATE_LIMITED" }, 429);

  const body = await request.json().catch(() => null);
  if (!body) return json({ error: "请求格式错误", code: "INVALID_REQUEST" }, 400);
  const normalizedCode = normalizeInviteCode(body.invite_code);
  const publicKey = String(body.public_key || "");
  const keyAlg = String(body.key_alg || "");
  if (!normalizedCode || !publicKey || !["ES256", "RS256"].includes(keyAlg)) {
    return json({ error: "激活参数不完整", code: "INVALID_REQUEST" }, 400);
  }
  const codeHash = await sha256Hex(normalizedCode);
  const now = nowSeconds();
  const invite = await env.DB.prepare(
    `SELECT id,status,device_id,device_public_key,key_alg
     FROM invites WHERE code_hash=? AND (expires_at IS NULL OR expires_at>?)`
  ).bind(codeHash, now).first();
  if (!invite) {
    await env.CONFIG_KV.put(rateKey, String(failures + 1), { expirationTtl: 600 });
    return json({ error: "邀请码无效、已使用或已过期", code: "INVITE_UNAVAILABLE" }, 409);
  }

  let deviceId = "";
  let status = 201;
  if (invite.status === "unused") {
    deviceId = crypto.randomUUID();
    await env.DB.prepare(
      `UPDATE invites SET status='active',device_id=?,device_public_key=?,key_alg=?,app_version=?,bound_at=?,last_seen_at=?
       WHERE id=?`
    ).bind(deviceId, publicKey, keyAlg, String(body.app_version || ""), now, now, invite.id).run();
  } else if (invite.status === "active" && invite.device_public_key === publicKey && invite.key_alg === keyAlg) {
    deviceId = invite.device_id;
    status = 200;
    await env.DB.prepare(
      "UPDATE invites SET app_version=?,last_seen_at=? WHERE id=?"
    ).bind(String(body.app_version || ""), now, invite.id).run();
  } else {
    await env.CONFIG_KV.put(rateKey, String(failures + 1), { expirationTtl: 600 });
    return json({ error: "邀请码无效、已使用或已过期", code: "INVITE_UNAVAILABLE" }, 409);
  }

  await env.CONFIG_KV.delete(rateKey);
  ctx.waitUntil(audit(env, request, status === 200 ? "device.reuse" : "device.activate", deviceId, ""));
  return json({ device_id: deviceId }, status);
}

async function clientConfig(request, env, ctx) {
  const auth = await authenticateDevice(request, env, ctx);
  if (auth.response) return auth.response;
  return json({
    status: "active",
    template_name: auth.record.template_name,
    template_version: auth.record.template_version,
    update_interval_minutes: auth.record.update_interval_minutes,
    subscription_url: `${new URL(request.url).origin}/api/v1/subscription`,
  }, 200, { "Cache-Control": "no-store" });
}

async function clientSubscription(request, env, ctx) {
  const auth = await authenticateDevice(request, env, ctx);
  if (auth.response) return auth.response;
  let upstream;
  try {
    upstream = new URL(auth.record.upstream_sub_url);
    if (upstream.protocol !== "https:") throw new Error("HTTPS required");
  } catch {
    return json({ error: "订阅配置无效", code: "CONFIG_INVALID" }, 500);
  }
  const response = await fetch(upstream.toString(), {
    headers: { "User-Agent": request.headers.get("User-Agent") || "CMSS-Box" },
    redirect: "follow",
  });
  if (!response.ok) {
    console.warn("Upstream subscription rejected Worker request", response.status);
    return Response.redirect(upstream.toString(), 307);
  }
  const headers = new Headers({
    "Content-Type": response.headers.get("Content-Type") || "text/plain; charset=utf-8",
    "Cache-Control": "no-store",
  });
  for (const name of ["Subscription-Userinfo", "Content-Disposition"]) {
    const value = response.headers.get(name);
    if (value) headers.set(name, value);
  }
  return new Response(response.body, { status: 200, headers });
}

async function authenticateDevice(request, env, ctx) {
  const url = new URL(request.url);
  const deviceId = url.searchParams.get("device_id") || "";
  const timestamp = Number.parseInt(url.searchParams.get("ts") || "0", 10);
  const nonce = url.searchParams.get("nonce") || "";
  const algorithm = url.searchParams.get("alg") || "";
  const signature = url.searchParams.get("sig") || "";
  if (!deviceId || !timestamp || !nonce || !signature) {
    return { response: json({ error: "缺少设备签名", code: "AUTH_REQUIRED" }, 401) };
  }
  if (Math.abs(nowSeconds() - timestamp) > REQUEST_WINDOW_SECONDS) {
    return { response: json({ error: "设备时间无效", code: "CLOCK_SKEW", server_time: nowSeconds() }, 401) };
  }
  const record = await env.DB.prepare(
    `SELECT i.id,i.status,i.device_id,i.device_public_key,i.key_alg,t.name AS template_name,
            t.enabled,t.version AS template_version,t.update_interval_minutes,t.upstream_sub_url
     FROM invites i JOIN config_templates t ON t.id=i.template_id WHERE i.device_id=?`
  ).bind(deviceId).first();
  if (!record) return { response: json({ error: "设备不存在", code: "DEVICE_NOT_FOUND" }, 403) };
  if (record.status !== "active") return { response: json({ error: "设备已停用", code: "DEVICE_REVOKED" }, 403) };
  if (!record.enabled) return { response: json({ error: "配置已停用", code: "CONFIG_DISABLED" }, 403) };
  if (record.key_alg !== algorithm) return { response: json({ error: "签名算法不匹配", code: "AUTH_FAILED" }, 401) };
  const canonical = `GET\n${url.pathname}\n${deviceId}\n${timestamp}\n${nonce}`;
  const valid = await verifyDeviceSignature(record, canonical, signature);
  if (!valid) return { response: json({ error: "设备签名无效", code: "AUTH_FAILED" }, 401) };
  try {
    await env.DB.prepare("INSERT INTO request_nonces(device_id,nonce,expires_at) VALUES(?,?,?)")
      .bind(deviceId, nonce, nowSeconds() + 300).run();
  } catch {
    return { response: json({ error: "重复请求", code: "REPLAYED_REQUEST" }, 409) };
  }
  ctx.waitUntil(env.DB.batch([
    env.DB.prepare("UPDATE invites SET last_seen_at=? WHERE id=?").bind(nowSeconds(), record.id),
    env.DB.prepare("DELETE FROM request_nonces WHERE expires_at<?").bind(nowSeconds()),
  ]));
  return { record };
}

async function verifyDeviceSignature(record, canonical, signature) {
  try {
    const data = new TextEncoder().encode(canonical);
    const signatureBytes = decodeBase64Url(signature);
    const publicKey = decodeBase64Url(record.device_public_key);
    if (record.key_alg === "ES256") {
      const key = await crypto.subtle.importKey("spki", publicKey, { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
      return crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, key, signatureBytes, data);
    }
    const key = await crypto.subtle.importKey("spki", publicKey, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
    return crypto.subtle.verify("RSASSA-PKCS1-v1_5", key, signatureBytes, data);
  } catch {
    return false;
  }
}

function normalizeTemplate(body) {
  const name = String(body.name || "").trim();
  const upstreamSubUrl = String(body.upstream_sub_url || "").trim();
  const parsed = new URL(upstreamSubUrl);
  if (!name || parsed.protocol !== "https:") throw new Error("模板名称和 HTTPS 订阅地址不能为空");
  return {
    name,
    upstreamSubUrl,
    enabled: body.enabled === false ? 0 : 1,
    version: clampInt(body.version, 1, 2147483647, 1),
    interval: clampInt(body.update_interval_minutes, 1, 1440, 10),
  };
}

async function validAdminSession(request, env) {
  if (!env.ADMIN_SESSION_SECRET) return false;
  const token = readCookie(request, "cmss_admin");
  if (!token) return false;
  const [payloadPart, signaturePart] = token.split(".");
  if (!payloadPart || !signaturePart) return false;
  const expected = await hmac(env.ADMIN_SESSION_SECRET, payloadPart);
  if (!constantTimeEqual(expected, signaturePart)) return false;
  try {
    const payload = JSON.parse(new TextDecoder().decode(decodeBase64Url(payloadPart)));
    return Number(payload.exp) > nowSeconds();
  } catch {
    return false;
  }
}

async function createSessionCookie(env) {
  if (!env.ADMIN_SESSION_SECRET) throw new Error("ADMIN_SESSION_SECRET is not configured");
  const payload = encodeBase64Url(new TextEncoder().encode(JSON.stringify({
    iat: nowSeconds(), exp: nowSeconds() + SESSION_SECONDS, nonce: crypto.randomUUID(),
  })));
  const signature = await hmac(env.ADMIN_SESSION_SECRET, payload);
  return `cmss_admin=${payload}.${signature}; Path=/admin; Max-Age=${SESSION_SECONDS}; HttpOnly; Secure; SameSite=Strict`;
}

function logoutResponse() {
  return new Response(null, {
    status: 302,
    headers: { Location: "/admin/login", "Set-Cookie": "cmss_admin=; Path=/admin; Max-Age=0; HttpOnly; Secure; SameSite=Strict" },
  });
}

async function hmac(secret, value) {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return encodeBase64Url(new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(value))));
}

async function verifyTotpWithRateLimit(request, env, code) {
  if (!env.TOTP_SECRET_BASE32) return json({ error: "TOTP未配置" }, 500);
  const ip = request.headers.get("CF-Connecting-IP") || "unknown";
  const key = `${AUTH_FAIL_PREFIX}${ip}`;
  const failures = Number.parseInt((await env.CONFIG_KV.get(key)) || "0", 10) || 0;
  if (failures >= AUTH_FAILURE_LIMIT) return json({ error: "尝试次数过多" }, 429);
  if (!(await verifyTotpCode(env.TOTP_SECRET_BASE32, code, Date.now()))) {
    await env.CONFIG_KV.put(key, String(failures + 1), { expirationTtl: AUTH_FAILURE_TTL_SECONDS });
    return json({ error: "验证码错误" }, 401);
  }
  await env.CONFIG_KV.delete(key);
  return null;
}

async function verifyTotpCode(secretBase32, code, nowMs) {
  if (!/^\d{6}$/.test(code)) return false;
  const secret = base32ToBytes(secretBase32);
  const counter = Math.floor(nowMs / 1000 / AUTH_WINDOW_SECONDS);
  for (const offset of [-1, 0, 1]) {
    if (constantTimeEqual(await generateTotpCode(secret, counter + offset), code)) return true;
  }
  return false;
}

async function generateTotpCode(secret, counter) {
  const key = await crypto.subtle.importKey("raw", secret, { name: "HMAC", hash: "SHA-1" }, false, ["sign"]);
  const bytes = new ArrayBuffer(8);
  const view = new DataView(bytes);
  view.setUint32(0, Math.floor(counter / 4294967296), false);
  view.setUint32(4, counter >>> 0, false);
  const digest = new Uint8Array(await crypto.subtle.sign("HMAC", key, bytes));
  const offset = digest[digest.length - 1] & 15;
  const binary = (digest[offset] & 127) << 24 | digest[offset + 1] << 16 | digest[offset + 2] << 8 | digest[offset + 3];
  return String(binary % 1000000).padStart(6, "0");
}

function base32ToBytes(value) {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  const clean = value.toUpperCase().replace(/=+$/g, "").replace(/\s+/g, "");
  let bits = "";
  for (const char of clean) {
    const index = alphabet.indexOf(char);
    if (index < 0) throw new Error("Invalid Base32 secret");
    bits += index.toString(2).padStart(5, "0");
  }
  const output = [];
  for (let index = 0; index + 8 <= bits.length; index += 8) output.push(Number.parseInt(bits.slice(index, index + 8), 2));
  return new Uint8Array(output);
}

async function readLegacyConfig(env) {
  const stored = await env.CONFIG_KV.get(CONFIG_KEY, "json");
  return stored && typeof stored === "object" ? { ...DEFAULT_CONFIG, ...stored } : { ...DEFAULT_CONFIG };
}

async function audit(env, request, action, targetId, detail) {
  await env.DB.prepare("INSERT INTO audit_logs(action,target_id,detail,ip,created_at) VALUES(?,?,?,?,?)")
    .bind(action, targetId || null, detail || null, request.headers.get("CF-Connecting-IP") || "unknown", nowSeconds()).run();
}

function renderLoginPage() {
  return page("后台登录", `
    <main class="login-card"><h1>CMSS-Box 后台</h1><p>请输入验证器中的六位动态码</p>
    <form id="login"><input id="code" inputmode="numeric" autocomplete="one-time-code" maxlength="6" placeholder="000000" required>
    <button>验证并进入后台</button><div id="status"></div></form></main>
    <script>document.querySelector('#login').addEventListener('submit',async e=>{e.preventDefault();const s=document.querySelector('#status');s.textContent='正在验证...';const r=await fetch('/admin/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({code:document.querySelector('#code').value})});const d=await r.json().catch(()=>({}));if(r.ok)location.href='/admin';else s.textContent=d.error||'验证失败';});<\/script>`);
}

function renderAdminPage(path) {
  const pages = {
    "/admin/templates": {
      key: "templates",
      title: "配置模板",
      description: "管理客户端使用的订阅来源与更新策略",
      content: renderTemplatesContent(),
      script: templatesScript(),
    },
    "/admin/invites": {
      key: "invites",
      title: "创建邀请码",
      description: "为指定配置模板生成一次性设备邀请码",
      content: renderInvitesContent(),
      script: invitesScript(),
    },
    "/admin/devices": {
      key: "devices",
      title: "邀请码与设备",
      description: "查看邀请码使用状态并管理已绑定设备",
      content: renderDevicesContent(),
      script: devicesScript(),
    },
  };
  const current = pages[path] || pages["/admin/templates"];
  const nav = [
    ["templates", "/admin/templates", "配置模板"],
    ["invites", "/admin/invites", "创建邀请码"],
    ["devices", "/admin/devices", "邀请码与设备"],
  ].map(([key, href, label]) => `<a class="nav-link${current.key === key ? " active" : ""}" href="${href}">${label}</a>`).join("");

  return page(`${current.title} - CMSS-Box`, `
    <div class="admin-shell">
      <aside class="sidebar">
        <div class="brand"><strong>CMSS-Box</strong><span>管理后台</span></div>
        <nav aria-label="后台导航">${nav}</nav>
        <a class="button secondary logout" href="/admin/logout">退出登录</a>
      </aside>
      <main class="workspace">
        <header class="page-header"><div><h1>${current.title}</h1><p>${current.description}</p></div></header>
        ${current.content}
        <div id="status" class="status" role="status" aria-live="polite"></div>
      </main>
    </div>
    <script>${commonAdminScript()}${current.script}<\/script>`);
}

function renderTemplatesContent() {
  return `
    <section class="panel form-panel" id="templateEditor">
      <div class="section-heading"><div><h2 id="templateFormTitle">新建模板</h2><p>保存后即可用于生成邀请码</p></div><button id="newTemplate" class="secondary" type="button">新建模板</button></div>
      <form id="templateForm">
        <input id="templateId" type="hidden">
        <label>名称<input id="templateName" required autocomplete="off"></label>
        <label>上游订阅 URL<input id="templateUrl" type="url" required autocomplete="url"></label>
        <div class="form-row"><label>版本<input id="templateVersion" type="number" value="1" min="1"></label><label>更新间隔（分钟）<input id="templateInterval" type="number" value="10" min="1"></label></div>
        <label class="check"><input id="templateEnabled" type="checkbox" checked><span>启用此模板</span></label>
        <div class="form-actions"><button id="saveTemplate" type="submit">保存模板</button><button id="cancelTemplate" class="secondary hidden" type="button">取消编辑</button></div>
      </form>
    </section>
    <section class="panel form-panel list-panel"><div class="section-heading"><div><h2>现有模板</h2><p>编辑不会创建重复模板</p></div></div><div id="templates" class="list"><div class="empty-state">正在加载模板...</div></div></section>`;
}

function renderInvitesContent() {
  return `
    <section class="panel invite-panel">
      <div class="section-heading"><div><h2>生成邀请码</h2><p>邀请码只能绑定一台设备</p></div></div>
      <form id="inviteForm">
        <label>配置模板<select id="inviteTemplate" required disabled><option>正在加载模板...</option></select></label>
        <div id="templateHint" class="field-hint"></div>
        <div class="form-row">
          <label>数量<input id="inviteCount" type="number" value="1" min="1" max="50"></label>
          <label>有效天数<input id="inviteExpireDays" type="number" value="0" min="0" max="36500"></label>
        </div>
        <p class="field-hint">有效天数输入 0 表示不限时。</p>
        <button id="createInvites" type="submit" disabled>生成邀请码</button>
      </form>
    </section>
    <section id="createdSection" class="panel invite-panel list-panel hidden"><div class="section-heading"><div><h2>新生成的邀请码</h2><p>邀请码仅在本次生成后完整显示，请及时保存</p></div><button id="copyAllCodes" class="secondary" type="button">复制全部</button></div><div id="createdCodes" class="code-list"></div></section>`;
}

function renderDevicesContent() {
  return `
    <section class="panel device-panel list-panel">
      <div class="section-heading"><div><h2>邀请码与绑定设备</h2><p>管理设备访问权限和绑定关系</p></div><button id="refreshDevices" class="secondary" type="button">刷新</button></div>
      <div id="invites" class="list"><div class="empty-state">正在加载设备...</div></div>
    </section>`;
}

function commonAdminScript() {
  return `
    const q = selector => document.querySelector(selector);
    async function api(path, options = {}) {
      options.headers = {'Content-Type': 'application/json', ...(options.headers || {})};
      const response = await fetch(path, options);
      const data = await response.json().catch(() => ({}));
      if (response.status === 401) { location.href = '/admin/login'; throw new Error('登录已过期'); }
      if (!response.ok) throw new Error(data.error || '请求失败');
      return data;
    }
    function node(tag, className, text) {
      const value = document.createElement(tag);
      if (className) value.className = className;
      if (text !== undefined) value.textContent = text;
      return value;
    }
    function setStatus(message, type = 'error') {
      const status = q('#status');
      status.textContent = message || '';
      status.className = 'status' + (message ? ' visible ' + type : '');
    }
    function time(value) { return value ? new Date(value * 1000).toLocaleString() : '--'; }
    function expireTime(value) { return value ? new Date(value * 1000).toLocaleString() : '不限时'; }
    function retryState(message, onRetry) {
      const wrap = node('div', 'empty-state');
      wrap.append(node('p', '', message));
      const button = node('button', 'secondary', '重新加载');
      button.type = 'button';
      button.addEventListener('click', onRetry);
      wrap.append(button);
      return wrap;
    }
  `;
}

function templatesScript() {
  return `
    let templates = [];
    function resetTemplateForm() {
      q('#templateForm').reset();
      q('#templateId').value = '';
      q('#templateVersion').value = '1';
      q('#templateInterval').value = '10';
      q('#templateEnabled').checked = true;
      q('#templateFormTitle').textContent = '新建模板';
      q('#saveTemplate').textContent = '保存模板';
      q('#cancelTemplate').classList.add('hidden');
    }
    function editTemplate(id) {
      const item = templates.find(value => value.id === id);
      if (!item) return;
      q('#templateId').value = item.id;
      q('#templateName').value = item.name;
      q('#templateUrl').value = item.upstream_sub_url;
      q('#templateVersion').value = item.version;
      q('#templateInterval').value = item.update_interval_minutes;
      q('#templateEnabled').checked = Boolean(item.enabled);
      q('#templateFormTitle').textContent = '编辑模板';
      q('#saveTemplate').textContent = '保存修改';
      q('#cancelTemplate').classList.remove('hidden');
      q('#templateEditor').scrollIntoView({behavior: 'smooth', block: 'start'});
    }
    function renderTemplates() {
      const list = q('#templates');
      list.replaceChildren();
      if (!templates.length) { list.append(node('div', 'empty-state', '暂无模板，请先新建一个配置模板。')); return; }
      templates.forEach(item => {
        const row = node('article', 'list-row');
        const details = node('div', 'list-main');
        details.append(node('strong', '', item.name));
        details.append(node('span', 'url-text', item.upstream_sub_url));
        details.append(node('small', '', (item.enabled ? '已启用' : '已停用') + ' · 版本 ' + item.version + ' · 每 ' + item.update_interval_minutes + ' 分钟更新'));
        const actions = node('div', 'actions');
        const edit = node('button', 'secondary', '编辑');
        edit.type = 'button';
        edit.dataset.id = item.id;
        const remove = node('button', 'danger', '删除');
        remove.type = 'button';
        remove.dataset.deleteId = item.id;
        actions.append(edit, remove);
        row.append(details, actions);
        list.append(row);
      });
    }
    async function loadTemplates() {
      const list = q('#templates');
      list.replaceChildren(node('div', 'empty-state', '正在加载模板...'));
      setStatus('');
      try {
        const data = await api('/admin/api/templates');
        templates = data.items || [];
        renderTemplates();
      } catch (error) {
        list.replaceChildren(retryState('模板加载失败：' + error.message, loadTemplates));
      }
    }
    q('#templates').addEventListener('click', event => {
      const button = event.target.closest('button[data-id]');
      if (button) editTemplate(button.dataset.id);
    });
    q('#templates').addEventListener('click', async event => {
      const button = event.target.closest('button[data-delete-id]');
      if (!button) return;
      if (!confirm('确认删除这个模板？仍在使用中的模板无法删除。')) return;
      button.disabled = true;
      setStatus('正在删除模板...', 'info');
      try {
        await api('/admin/api/templates/' + encodeURIComponent(button.dataset.deleteId), { method: 'DELETE' });
        if (q('#templateId').value === button.dataset.deleteId) resetTemplateForm();
        await loadTemplates();
        setStatus('模板已删除', 'success');
      } catch (error) {
        setStatus('删除失败：' + error.message);
        button.disabled = false;
      }
    });
    q('#newTemplate').addEventListener('click', () => { resetTemplateForm(); q('#templateName').focus(); });
    q('#cancelTemplate').addEventListener('click', resetTemplateForm);
    q('#templateForm').addEventListener('submit', async event => {
      event.preventDefault();
      const id = q('#templateId').value;
      const submit = q('#saveTemplate');
      submit.disabled = true;
      setStatus('正在保存...', 'info');
      try {
        await api('/admin/api/templates' + (id ? '/' + encodeURIComponent(id) : ''), {
          method: id ? 'PUT' : 'POST',
          body: JSON.stringify({
            name: q('#templateName').value,
            upstream_sub_url: q('#templateUrl').value,
            version: Number(q('#templateVersion').value),
            update_interval_minutes: Number(q('#templateInterval').value),
            enabled: q('#templateEnabled').checked
          })
        });
        resetTemplateForm();
        await loadTemplates();
        setStatus(id ? '模板已更新' : '模板已创建', 'success');
      } catch (error) { setStatus('保存失败：' + error.message); }
      finally { submit.disabled = false; }
    });
    loadTemplates();
  `;
}

function invitesScript() {
  return `
    let createdCodes = [];
    async function loadInviteTemplates() {
      const select = q('#inviteTemplate');
      const submit = q('#createInvites');
      const hint = q('#templateHint');
      select.disabled = true;
      submit.disabled = true;
      select.replaceChildren(node('option', '', '正在加载模板...'));
      hint.replaceChildren();
      setStatus('');
      try {
        const data = await api('/admin/api/templates');
        const available = (data.items || []).filter(item => Boolean(item.enabled));
        select.replaceChildren();
        if (!available.length) {
          select.append(node('option', '', '暂无可用模板'));
          const message = node('span', '', '请先创建并启用一个配置模板。');
          const link = node('a', 'inline-link', '前往配置模板');
          link.href = '/admin/templates';
          hint.append(message, link);
          return;
        }
        available.forEach(item => {
          const option = node('option', '', item.name);
          option.value = item.id;
          select.append(option);
        });
        select.disabled = false;
        submit.disabled = false;
      } catch (error) {
        select.replaceChildren(node('option', '', '模板加载失败'));
        const retry = node('button', 'link-button', '重新加载');
        retry.type = 'button';
        retry.addEventListener('click', loadInviteTemplates);
        hint.append(node('span', '', error.message + '。'), retry);
      }
    }
    function renderCodes() {
      const list = q('#createdCodes');
      list.replaceChildren();
      createdCodes.forEach(code => {
        const row = node('div', 'code-row');
        row.append(node('code', '', code));
        const copy = node('button', 'secondary compact', '复制');
        copy.type = 'button';
        copy.dataset.code = code;
        row.append(copy);
        list.append(row);
      });
      q('#createdSection').classList.toggle('hidden', !createdCodes.length);
    }
    async function copyText(value) {
      await navigator.clipboard.writeText(value);
      setStatus('已复制到剪贴板', 'success');
    }
    q('#createdCodes').addEventListener('click', event => {
      const button = event.target.closest('button[data-code]');
      if (button) copyText(button.dataset.code).catch(() => setStatus('复制失败，请手动复制'));
    });
    q('#copyAllCodes').addEventListener('click', () => copyText(createdCodes.join('\\n')).catch(() => setStatus('复制失败，请手动复制')));
    q('#inviteForm').addEventListener('submit', async event => {
      event.preventDefault();
      const submit = q('#createInvites');
      submit.disabled = true;
      setStatus('正在生成邀请码...', 'info');
      try {
        const data = await api('/admin/api/invites', {
          method: 'POST',
          body: JSON.stringify({
            template_id: q('#inviteTemplate').value,
            count: Number(q('#inviteCount').value),
            expire_days: Number(q('#inviteExpireDays').value)
          })
        });
        createdCodes = data.codes || [];
        renderCodes();
        setStatus('邀请码已生成', 'success');
      } catch (error) { setStatus('生成失败：' + error.message); }
      finally { submit.disabled = q('#inviteTemplate').disabled; }
    });
    loadInviteTemplates();
  `;
}

function devicesScript() {
  return `
    const statusNames = {unused: '未使用', active: '已绑定', revoked: '已禁用'};
    function renderDevices(items) {
      const list = q('#invites');
      list.replaceChildren();
      if (!items.length) { list.append(node('div', 'empty-state', '暂无邀请码或绑定设备。')); return; }
      items.forEach(item => {
        const row = node('article', 'device-row');
        const details = node('div', 'list-main');
        const title = node('div', 'device-title');
        const fullCode = item.code_value || ('历史邀请码不可查看（****-' + item.code_hint + '）');
        title.append(node('strong', '', fullCode));
        title.append(node('span', 'badge ' + item.status, statusNames[item.status] || item.status));
        details.append(title);
        details.append(node('span', '', '模板：' + item.template_name + ' · 设备：' + (item.device_id || '未绑定')));
        details.append(node('small', '', '绑定时间：' + time(item.bound_at) + ' · 最近访问：' + time(item.last_seen_at) + ' · 过期时间：' + expireTime(item.expires_at)));
        const actions = node('div', 'actions');
        [['revoke', '禁用并删除', 'danger'], ['restore', '恢复', 'secondary'], ['unbind', '解绑', 'secondary']].forEach(value => {
          const button = node('button', value[2], value[1]);
          button.type = 'button';
          button.dataset.id = item.id;
          button.dataset.action = value[0];
          if ((value[0] === 'revoke' && item.status === 'revoked') || (value[0] === 'restore' && item.status !== 'revoked') || (value[0] === 'unbind' && !item.device_id)) button.disabled = true;
          actions.append(button);
        });
        row.append(details, actions);
        list.append(row);
      });
    }
    async function loadDevices() {
      const list = q('#invites');
      list.replaceChildren(node('div', 'empty-state', '正在加载设备...'));
      setStatus('');
      try {
        const data = await api('/admin/api/invites');
        renderDevices(data.items || []);
      } catch (error) {
        list.replaceChildren(retryState('设备列表加载失败：' + error.message, loadDevices));
      }
    }
    q('#refreshDevices').addEventListener('click', loadDevices);
    q('#invites').addEventListener('click', async event => {
      const button = event.target.closest('button[data-action]');
      if (!button || button.disabled) return;
      if (button.dataset.action === 'revoke' && !confirm('确认禁用这个设备并删除对应邀请码？删除后该设备需要重新获取邀请码。')) return;
      if (button.dataset.action === 'unbind' && !confirm('确认解绑该设备？解绑后需要重新激活。')) return;
      button.disabled = true;
      setStatus('正在处理...', 'info');
      try {
        await api('/admin/api/invites/' + encodeURIComponent(button.dataset.id) + '/action', {
          method: 'POST', body: JSON.stringify({action: button.dataset.action})
        });
        await loadDevices();
        setStatus('操作已完成', 'success');
      } catch (error) { setStatus('操作失败：' + error.message); button.disabled = false; }
    });
    loadDevices();
  `;
}

function page(title, body) {
  return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${title}</title><style>
  :root{color-scheme:dark;font-family:system-ui,sans-serif;background:#080b12;color:#f1f5f9}*{box-sizing:border-box}body{margin:0;min-height:100vh;background:#080b12}h1,h2,p{margin-top:0}h1{font-size:28px;margin-bottom:7px}h2{font-size:19px;margin-bottom:5px}p,small,.field-hint{color:#94a3b8}.login-card{max-width:420px;margin:12vh auto 0;background:#121826;border:1px solid #293449;border-radius:8px;padding:24px}.admin-shell{width:100%;min-height:100vh;display:grid;grid-template-columns:220px minmax(0,1fr)}.sidebar{position:sticky;top:0;height:100vh;padding:28px 18px;border-right:1px solid #1e293b;background:#0d1320;display:flex;flex-direction:column}.brand{padding:0 12px 24px}.brand strong,.brand span{display:block}.brand strong{font-size:21px}.brand span{margin-top:4px;color:#94a3b8}.sidebar nav{display:grid;gap:6px}.nav-link{padding:11px 12px;border-radius:6px;color:#aebbd0;text-decoration:none;font-weight:650}.nav-link:hover,.nav-link.active{background:#1b2639;color:#f8fafc}.logout{margin-top:auto}.workspace{min-width:0;padding:32px 40px 48px;display:flex;flex-direction:column;align-items:flex-start}.page-header{width:min(100%,960px);margin-bottom:24px;border-bottom:1px solid #1e293b}.page-header p{margin-bottom:22px}.panel{width:min(100%,960px);background:#121826;border:1px solid #293449;border-radius:8px;padding:22px;margin-bottom:18px}.form-panel,.invite-panel{width:min(100%,860px);max-width:860px}.device-panel{width:min(100%,1120px);max-width:1120px}.section-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;margin-bottom:18px}.section-heading p{margin-bottom:0}.list-panel{padding-bottom:8px}form{display:grid;gap:14px}label{display:grid;gap:7px;font-size:13px;color:#cbd5e1}input,select{width:100%;padding:11px;border:1px solid #475569;border-radius:6px;background:#020617;color:#f8fafc;font:inherit}input:focus,select:focus{outline:2px solid #10b981;outline-offset:1px}.check{display:flex;align-items:center;gap:9px}.check input{width:18px;height:18px}.form-row{display:grid;grid-template-columns:1fr 1fr;gap:14px}.form-actions,.actions{display:flex;gap:10px;flex-wrap:wrap}button,.button{border:0;border-radius:6px;background:#10b981;color:#04120d;padding:10px 15px;font:inherit;font-weight:750;cursor:pointer;text-decoration:none;text-align:center}button:hover,.button:hover{filter:brightness(1.08)}button:disabled{opacity:.45;cursor:not-allowed;filter:none}.secondary{background:#334155;color:#f8fafc}.danger{background:#dc2626;color:white}.compact{padding:7px 12px}.hidden{display:none!important}.list{border-top:1px solid #293449}.list-row,.device-row{display:flex;align-items:center;justify-content:space-between;gap:18px;padding:16px 0;border-bottom:1px solid #243047}.list-main{min-width:0;display:grid;gap:5px}.list-main span,.list-main small{overflow-wrap:anywhere}.url-text{color:#aebbd0}.empty-state{padding:32px 12px;text-align:center;color:#94a3b8}.empty-state p{margin-bottom:12px}.device-title{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.badge{display:inline-flex;width:max-content;padding:3px 8px;border-radius:999px;font-size:12px;background:#334155;color:#dbeafe}.badge.active{background:#064e3b;color:#a7f3d0}.badge.revoked{background:#7f1d1d;color:#fecaca}.code-list{display:grid;gap:10px}.code-row{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:12px 14px;background:#090e18;border:1px solid #293449;border-radius:6px}.code-row code{font-size:16px;color:#6ee7b7}.field-hint{display:flex;align-items:center;gap:8px;font-size:13px}.inline-link,.link-button{color:#6ee7b7}.link-button{border:0;background:none;padding:0}.status{display:none;width:min(100%,960px);margin-top:12px;padding:11px 13px;border-radius:6px}.status.visible{display:block}.status.error{background:#3f151b;color:#fecaca}.status.success{background:#073c30;color:#a7f3d0}.status.info{background:#172554;color:#bfdbfe}
  @media(max-width:800px){.admin-shell{display:block}.sidebar{position:static;width:100%;height:auto;padding:16px;border-right:0;border-bottom:1px solid #1e293b}.brand{padding:0 4px 14px}.sidebar nav{display:flex;overflow-x:auto}.nav-link{white-space:nowrap}.logout{margin-top:14px;display:block}.workspace{padding:22px 14px 36px}.form-row{grid-template-columns:1fr}.list-row,.device-row,.section-heading{align-items:stretch;flex-direction:column}.actions button{flex:1}.page-header{margin-bottom:18px;width:100%}.panel,.form-panel,.invite-panel,.device-panel,.status{width:100%;max-width:none}}
  </style></head><body>${body}</body></html>`;
}

function generateInviteCode() {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  const bytes = crypto.getRandomValues(new Uint8Array(8));
  const value = Array.from(bytes, byte => alphabet[byte % alphabet.length]).join("");
  return `CMSS-${value.slice(0, 4)}-${value.slice(4)}`;
}

function normalizeInviteCode(value) { return String(value || "").toUpperCase().replace(/[^A-Z0-9]/g, ""); }
function nowSeconds() { return Math.floor(Date.now() / 1000); }
function clampInt(value, min, max, fallback) { const n = Number.parseInt(value, 10); return Number.isFinite(n) ? Math.min(max, Math.max(min, n)) : fallback; }
function sameOrigin(request) { const origin = request.headers.get("Origin"); return !origin || origin === new URL(request.url).origin; }
function readCookie(request, name) { const match = (request.headers.get("Cookie") || "").match(new RegExp(`(?:^|; )${name}=([^;]+)`)); return match ? match[1] : ""; }
function redirect(location) { return new Response(null, { status: 302, headers: { Location: location } }); }
function html(value) { return new Response(value, { headers: { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store", "X-Frame-Options": "DENY", "Content-Security-Policy": "default-src 'self'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; frame-ancestors 'none'" } }); }
function json(value, status = 200, extra = {}) { return new Response(JSON.stringify(value), { status, headers: { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store", ...extra } }); }
function constantTimeEqual(a, b) { if (a.length !== b.length) return false; let diff = 0; for (let i = 0; i < a.length; i += 1) diff |= a.charCodeAt(i) ^ b.charCodeAt(i); return diff === 0; }
function encodeBase64Url(bytes) { let binary = ""; for (const byte of bytes) binary += String.fromCharCode(byte); return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, ""); }
function decodeBase64Url(value) { const normalized = value.replace(/-/g, "+").replace(/_/g, "/"); const binary = atob(normalized + "=".repeat((4 - normalized.length % 4) % 4)); return Uint8Array.from(binary, char => char.charCodeAt(0)); }
async function sha256Hex(value) { return Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value))), byte => byte.toString(16).padStart(2, "0")).join(""); }
