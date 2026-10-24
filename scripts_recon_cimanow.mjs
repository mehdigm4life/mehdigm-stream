import fs from "fs";
import os from "os";
import path from "path";
import crypto from "crypto";
import { spawn, execFile } from "child_process";
import { promisify } from "util";

const execFileAsync = promisify(execFile);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const UA =
  "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

const PROXY_FEEDS = [
  ["socks5", "https://raw.githubusercontent.com/vakhov/fresh-proxy-list/master/socks5.txt"],
  ["http", "https://raw.githubusercontent.com/vakhov/fresh-proxy-list/master/https.txt"],
  ["socks5", "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt"],
  ["socks5", "https://cdn.jsdelivr.net/gh/proxifly/free-proxy-list@main/proxies/protocols/socks5/data.txt"],
];

function isCimaBlock(body) {
  return (
    !body ||
    body.includes("ستوب! المخرج عايز كدة") ||
    body.includes("مشهد محذوف") ||
    (body.includes("403") && body.length < 5000 && body.includes("Cima Now"))
  );
}

async function curlReq(url, opts = {}) {
  const {
    proxy = null,
    referer = null,
    cookieJar = null,
    method = "GET",
    formBody = null,
    headers = {},
    follow = true,
    range = null,
    timeoutSec = 15,
  } = opts;
  const args = [
    "-sS",
    "-k",
    "--compressed",
    "--max-time",
    String(timeoutSec),
    "--connect-timeout",
    "7",
    "-A",
    UA,
  ];
  if (follow) args.push("-L", "--max-redirs", "5");
  if (method !== "GET") args.push("-X", method);
  if (formBody !== null) args.push("--data-raw", formBody);
  if (range) args.push("-r", range);
  if (proxy) args.push("-x", proxy.replace(/^socks5:\/\//, "socks5h://"));
  if (referer) args.push("-e", referer);
  if (cookieJar) args.push("-b", cookieJar, "-c", cookieJar);
  for (const [k, v] of Object.entries(headers)) args.push("-H", `${k}: ${v}`);
  args.push("-w", "\n__CURL_META__:%{http_code}|%{url_effective}|%{content_type}");
  args.push(url);
  const { stdout } = await execFileAsync("curl", args, { maxBuffer: 30 * 1024 * 1024 });
  const idx = stdout.lastIndexOf("\n__CURL_META__:");
  const text = idx >= 0 ? stdout.slice(0, idx) : stdout;
  const [codeStr, effectiveUrl, contentType] = (idx >= 0 ? stdout.slice(idx + 15).trim() : "0||").split("|");
  return { status: Number(codeStr) || 0, effectiveUrl, contentType, text };
}

async function getWorkingProxies() {
  const candidates = [];
  for (const [scheme, feedUrl] of PROXY_FEEDS) {
    try {
      const r = await curlReq(feedUrl, { timeoutSec: 8 });
      const lines = r.text
        .split(/\r?\n/)
        .map((s) => s.trim().replace(/^(socks5h?|https?):\/\//i, ""))
        .filter((s) => /^\d+\.\d+\.\d+\.\d+:\d+$/.test(s))
        .slice(0, 45);
      for (const hp of lines) candidates.push(`${scheme}://${hp}`);
    } catch {}
  }
  const working = [];
  for (let i = 0; i < candidates.length && working.length < 10; i += 25) {
    const batch = candidates.slice(i, i + 25);
    await Promise.all(
      batch.map(async (p) => {
        if (working.length >= 10) return;
        try {
          const t0 = Date.now();
          const r1 = await curlReq("https://cimanow.cc/home/", { proxy: p, timeoutSec: 8 });
          if (r1.status === 200 && !isCimaBlock(r1.text) && r1.text.includes("cimanow")) {
            working.push({ proxy: p, ms: Date.now() - t0 });
          }
        } catch {}
      })
    );
  }
  working.sort((a, b) => a.ms - b.ms);
  return working.map((w) => w.proxy);
}

function buildGatewayPostBody(vaultHtml) {
  const arrStart = vaultHtml.search(/window\["[^"]+"\]\s*=\s*\[/);
  const openBracket = arrStart >= 0 ? vaultHtml.indexOf("[", vaultHtml.indexOf("=", arrStart)) : -1;
  if (openBracket < 0) return null;
  const headSlice = vaultHtml.slice(openBracket + 1, openBracket + 4096);
  const b64Joined = [...headSlice.matchAll(/"([A-Za-z0-9+/=]+)"/g)].map((m) => m[1]).join("");
  const raw = Buffer.from(b64Joined, "base64");
  const needle = Buffer.from("<script>");
  for (let off = 0; off <= 8; off++) {
    for (let klen = 1; klen <= 10; klen++) {
      const key = Buffer.alloc(klen);
      for (let j = 0; j < klen && j < needle.length; j++) key[(off + j) % klen] = raw[off + j] ^ needle[j];
      let ok = true;
      for (let j = 0; j < needle.length; j++) {
        if ((raw[off + j] ^ key[(off + j) % klen]) !== needle[j]) { ok = false; break; }
      }
      if (!ok) continue;
      const topDecoded = Buffer.from(raw.map((v, i) => v ^ key[i % klen])).toString("utf8");
      if (!topDecoded.includes("<script>") || !topDecoded.includes("window.")) continue;
      const ts = topDecoded.match(/<script>([\s\S]*?)<\/script>/i)?.[1] || "";
      const mapBlock = ts.match(/window\.map_[a-z0-9]+\s*=\s*\{([\s\S]*?)\}/)?.[1];
      if (!mapBlock) continue;
      const chVar = mapBlock.match(/ch:\s*'([^']+)'/)?.[1];
      const riVar = mapBlock.match(/ri:\s*'([^']+)'/)?.[1];
      const keVar = mapBlock.match(/ke:\s*'([^']+)'/)?.[1];
      const seVar = mapBlock.match(/se:\s*'([^']+)'/)?.[1];
      const getVal = (v) => ts.match(new RegExp(`'${v}'\\s*:\\s*'([^']+)'`))?.[1];
      const ch = getVal(chVar), ri = getVal(riVar), ke = getVal(keVar), se = getVal(seVar);
      if (!ch || !ri || !ke || !se) continue;

      const keBytes = Buffer.from(ke, "base64");
      const seBytes = Buffer.from(se, "utf8");
      const secretHex = Buffer.from(keBytes.map((b, i) => b ^ seBytes[i % seBytes.length])).toString("utf8");
      const fp = "TW96aWxsYS81LjQ4";
      const hmac = crypto
        .createHmac("sha256", Buffer.from(secretHex, "utf8"))
        .update(Buffer.from(ri + ch + fp, "utf8"))
        .digest("base64");

      const postBody =
        `request_id=${encodeURIComponent(ri)}` +
        `&hmac_token=${encodeURIComponent(hmac)}` +
        `&ch=${encodeURIComponent(ch)}` +
        `&fp=${encodeURIComponent(fp)}`;
      return { ch, ri, secretHex, fp, hmac, postBody };
    }
  }
  return null;
}

class CDPClient {
  constructor(wsUrl) {
    this.wsUrl = wsUrl;
    this.id = 0;
    this.pending = new Map();
    this.listeners = new Map();
  }
  async connect() {
    this.ws = new WebSocket(this.wsUrl);
    await new Promise((resolve, reject) => {
      this.ws.addEventListener("open", resolve, { once: true });
      this.ws.addEventListener("error", reject, { once: true });
    });
    this.ws.addEventListener("message", (ev) => {
      const msg = JSON.parse(typeof ev.data === "string" ? ev.data : ev.data.toString());
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id);
        this.pending.delete(msg.id);
        if (msg.error) reject(new Error(msg.error.message));
        else resolve(msg.result);
      } else if (msg.method) {
        const arr = this.listeners.get(msg.method) || [];
        for (const fn of arr) fn(msg.params);
      }
    });
  }
  send(method, params = {}) {
    const id = ++this.id;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`CDP timeout: ${method}`));
      }, 20000);
      this.pending.set(id, {
        resolve: (v) => { clearTimeout(timer); resolve(v); },
        reject: (e) => { clearTimeout(timer); reject(e); },
      });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }
  on(method, fn) {
    if (!this.listeners.has(method)) this.listeners.set(method, []);
    this.listeners.get(method).push(fn);
  }
  close() {
    try { this.ws.close(); } catch {}
  }
}

async function fetchCimaAssets(proxies) {
  const assets = {};
  const urls = [
    ["https://cimanow.cc/wp-content/themes/Cima%20Now%20New/Assets/js/nbm2oHaQcN60y6Ozwm.js?v=2.3", true],
    ["https://cimanow.cc/wp-content/themes/Cima%20Now%20New/Assets/js/0CYA6X1KhKIS.js?v=3.1", true],
    ["https://cimanow.cc/wp-content/themes/Cima%20Now%20New/Assets/js/jquery-3.5.1.min.js", true],
    ["https://cimanow.cc/wp-content/themes/Cima%20Now%20New/Assets/js/owl.carousel.min.js", true],
    ["https://cdnjs.cloudflare.com/ajax/libs/jquery-cookie/1.4.1/jquery.cookie.min.js", false],
    ["https://cdn.jsdelivr.net/npm/sweetalert2@latest/dist/sweetalert2.all.min.js", false],
    ["https://cdnjs.cloudflare.com/ajax/libs/vanilla-lazyload/17.8.3/lazyload.min.js", false],
  ];
  for (const [u, needProxy] of urls) {
    for (const p of needProxy ? proxies.slice(0, 4) : [null]) {
      try {
        const r = await curlReq(u, { proxy: p, timeoutSec: 10 });
        if (r.status === 200 && r.text.length > 200) {
          assets[new URL(u).href] = r.text;
          break;
        }
      } catch {}
    }
  }
  return assets;
}

async function renderAndSwitchInChrome(watchingUrl, rawHtml, assetsMap, proxy, cookieJar, port) {
  const userDataDir = `/tmp/chrome_w17_${port}_${Math.random().toString(36).slice(2)}`;
  const chromeBin = fs.existsSync("/usr/bin/google-chrome")
    ? "/usr/bin/google-chrome"
    : "/usr/bin/google-chrome-stable";

  const proc = spawn(
    chromeBin,
    [
      "--headless=new",
      "--no-sandbox",
      "--disable-setuid-sandbox",
      "--disable-dev-shm-usage",
      "--disable-gpu",
      "--disable-blink-features=AutomationControlled",
      "--window-size=1280,800",
      `--user-agent=${UA}`,
      `--remote-debugging-port=${port}`,
      `--user-data-dir=${userDataDir}`,
      "about:blank",
    ],
    { stdio: "ignore" }
  );

  let cdp = null;
  const cryptoLogs = [];
  const consoleErrors = [];
  const switchCaptures = [];

  try {
    let wsUrl = null;
    for (let i = 0; i < 60; i++) {
      await sleep(250);
      try {
        const res = await fetch(`http://127.0.0.1:${port}/json/list`);
        const pages = await res.json();
        const page = pages.find((p) => p.type === "page" && p.webSocketDebuggerUrl);
        if (page) { wsUrl = page.webSocketDebuggerUrl; break; }
      } catch {}
    }
    if (!wsUrl) throw new Error("Local Chrome CDP failed to start");

    cdp = new CDPClient(wsUrl);
    await cdp.connect();
    await cdp.send("Page.enable");
    await cdp.send("Runtime.enable");
    await cdp.send("Fetch.enable", { patterns: [{ urlPattern: "*" }] });

    await cdp.send("Page.addScriptToEvaluateOnNewDocument", {
      source: `
        try { delete Object.getPrototypeOf(navigator).webdriver; } catch (e) {}
        try {
          const toHex = (buf) => {
            const u8 = buf instanceof Uint8Array ? buf : new Uint8Array(buf.buffer ? buf.buffer.slice(buf.byteOffset, buf.byteOffset + buf.byteLength) : buf);
            let s = '', a = '';
            for (let i = 0; i < u8.length; i++) {
              s += u8[i].toString(16).padStart(2, '0');
              a += (u8[i] >= 32 && u8[i] < 127) ? String.fromCharCode(u8[i]) : '.';
            }
            return { len: u8.length, hex: s.slice(0, 300), ascii: a.slice(0, 300) };
          };
          if (window.SubtleCrypto) {
            SubtleCrypto.prototype.importKey = new Proxy(SubtleCrypto.prototype.importKey, {
              apply(target, thisArg, args) {
                try {
                  if (args[0] === 'raw' && args[1]) {
                    console.debug('__CRYPTO__:' + JSON.stringify({ op: 'importKey', alg: args[2], key: toHex(args[1]) }));
                  }
                } catch (e) {}
                return Reflect.apply(target, thisArg, args);
              }
            });
            SubtleCrypto.prototype.sign = new Proxy(SubtleCrypto.prototype.sign, {
              apply(target, thisArg, args) {
                try {
                  if (args[2]) {
                    console.debug('__CRYPTO__:' + JSON.stringify({ op: 'sign', alg: args[0], data: toHex(args[2]) }));
                  }
                } catch (e) {}
                return Reflect.apply(target, thisArg, args);
              }
            });
            SubtleCrypto.prototype.decrypt = new Proxy(SubtleCrypto.prototype.decrypt, {
              apply(target, thisArg, args) {
                try {
                  console.debug('__CRYPTO__:' + JSON.stringify({ op: 'decrypt', alg: args[0]?.name, iv: args[0]?.iv ? toHex(args[0].iv) : null, dataLen: args[2]?.byteLength }));
                } catch (e) {}
                return Reflect.apply(target, thisArg, args);
              }
            });
          }
        } catch (e) {}
      `,
    });

    cdp.on("Runtime.consoleAPICalled", (p) => {
      if (p.type === "debug" && p.args?.[0]?.value?.startsWith?.("__CRYPTO__:")) {
        try {
          const item = JSON.parse(p.args[0].value.slice("__CRYPTO__:".length));
          if (cryptoLogs.length < 60) cryptoLogs.push(item);
        } catch {}
      } else if (p.type === "error") {
        consoleErrors.push(p.args?.map((a) => a.value || a.description).join(" "));
      }
    });
    cdp.on("Runtime.exceptionThrown", (p) => {
      consoleErrors.push(p.exceptionDetails?.exception?.description || p.exceptionDetails?.text || "exc");
    });

    const normalizedTarget = new URL(watchingUrl).href;
    cdp.on("Fetch.requestPaused", async (p) => {
      const u = p.request.url;
      try {
        if (p.resourceType === "Document" && !u.includes("/watching/")) {
          await cdp.send("Fetch.failRequest", { requestId: p.requestId, errorReason: "Aborted" });
          return;
        }
        if (u.includes("core.php")) {
          // Proxy core.php through real curl with the same session cookieJar!
          const sw = await curlReq(u, {
            proxy,
            cookieJar,
            referer: watchingUrl,
            headers: { "X-Requested-With": "XMLHttpRequest" },
          });
          switchCaptures.push({
            url: u,
            method: p.request.method,
            reqHeaders: p.request.headers,
            status: sw.status,
            body: sw.text.trim().slice(0, 800),
          });
          await cdp.send("Fetch.fulfillRequest", {
            requestId: p.requestId,
            responseCode: sw.status || 200,
            responseHeaders: [{ name: "Content-Type", value: "text/html; charset=UTF-8" }],
            body: Buffer.from(sw.text, "utf8").toString("base64"),
          });
          return;
        }
        if (u === normalizedTarget || u === watchingUrl || u.includes("/watching/")) {
          await cdp.send("Fetch.fulfillRequest", {
            requestId: p.requestId,
            responseCode: 200,
            responseHeaders: [{ name: "Content-Type", value: "text/html; charset=UTF-8" }],
            body: Buffer.from(rawHtml, "utf8").toString("base64"),
          });
        } else if (assetsMap[u]) {
          await cdp.send("Fetch.fulfillRequest", {
            requestId: p.requestId,
            responseCode: 200,
            responseHeaders: [{ name: "Content-Type", value: "application/javascript; charset=UTF-8" }],
            body: Buffer.from(assetsMap[u], "utf8").toString("base64"),
          });
        } else if (u.includes("cimanow.cc") && u.includes(".js")) {
          let jsTxt = "";
          try {
            const r = await curlReq(u, { proxy, cookieJar, referer: watchingUrl, timeoutSec: 8 });
            if (r.status === 200 && !isCimaBlock(r.text)) {
              jsTxt = r.text;
              assetsMap[u] = jsTxt;
            }
          } catch {}
          await cdp.send("Fetch.fulfillRequest", {
            requestId: p.requestId,
            responseCode: 200,
            responseHeaders: [{ name: "Content-Type", value: "application/javascript; charset=UTF-8" }],
            body: Buffer.from(jsTxt, "utf8").toString("base64"),
          });
        } else {
          const ct = u.includes(".js")
            ? "application/javascript"
            : u.includes(".css")
            ? "text/css"
            : "text/plain";
          await cdp.send("Fetch.fulfillRequest", {
            requestId: p.requestId,
            responseCode: 200,
            responseHeaders: [{ name: "Content-Type", value: ct }],
            body: "",
          });
        }
      } catch {}
    });

    await cdp.send("Page.navigate", {
      url: watchingUrl,
      referrer: "https://rm.freex2line.online/2020/02/blog-post.html/",
    });

    // Wait 3.5s so ALL scripts finish running!
    await sleep(3500);

    // Click EVERY server tab inside Chrome so the page's own JS invokes `_getCoreToken()` / `core.php`!
    const clickEv = await cdp.send("Runtime.evaluate", {
      expression: `(async () => {
        const sleep = ms => new Promise(r => setTimeout(r, ms));
        const scriptsDom = [...document.querySelectorAll('script')].map((s, i) => ({
          i,
          src: s.src || '',
          id: s.id || '',
          type: s.type || '',
          len: (s.innerHTML || '').length,
          code: s.src ? '' : (s.innerHTML || '').slice(0, 6000)
        }));
        const lis = [...document.querySelectorAll('#watch li[data-id]')].filter(
          li => li.getAttribute('data-index') !== '00' && li.getAttribute('aria-label') !== 'embed'
        );
        for (const li of lis) {
          li.click();
          await sleep(600);
        }
        return JSON.stringify({
          hasFn: typeof window._getCoreToken === 'function',
          sampleTok: typeof window._getCoreToken === 'function' ? window._getCoreToken() : null,
          scriptsDom
        });
      })()`,
      awaitPromise: true,
      returnByValue: true,
    });

    const clickInfo = clickEv?.result?.value ? JSON.parse(clickEv.result.value) : null;
    return { clickInfo, cryptoLogs, consoleErrors, switchCaptures };
  } finally {
    if (cdp) cdp.close();
    try { proc.kill("SIGKILL"); } catch {}
    try { fs.rmSync(userDataDir, { recursive: true, force: true }); } catch {}
  }
}

function inspectRawWatchingVault(rawHtml) {
  const tags = [...rawHtml.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)].map((m, idx) => ({
    idx,
    attrs: m[1].trim().slice(0, 320),
    len: m[2].length,
    content: m[1].includes("text/plain") ? m[2].slice(0, 300) : m[2].slice(0, 30000),
  }));
  return { totalLen: rawHtml.length, scriptCount: tags.length, tags };
}

async function probeDownloadHosts(proxy) {
  const probes = {};
  // 1) Jetload direct CDN vs gateway
  const jetCdn = "https://cdn1.jetload.top/%5BCima-Now.CoM%5D%20Fall.2.Deadpoint.2026.HD-1080p.mp4";
  const jetGw = "https://jetload.top/Jetloadon4/?link=aHR0cHM6Ly9jZG4xLmpldGxvYWQudG9wLyU1QkNpbWEtTm93LkNvTSU1RCUyMEZhbGwuMi5EZWFkcG9pbnQuMjAyNi5IRC0xMDgwcC5tcDQ=";
  try {
    const r = await curlReq(jetCdn, { range: "0-511", timeoutSec: 10 });
    probes.jetloadDirectNoRef = { status: r.status, ct: r.contentType, eff: r.effectiveUrl };
  } catch (e) { probes.jetloadDirectNoRef = { err: String(e) }; }
  try {
    const r = await curlReq(jetCdn, { referer: "https://jetload.top/", range: "0-511", timeoutSec: 10 });
    probes.jetloadDirectWithRef = { status: r.status, ct: r.contentType, eff: r.effectiveUrl };
  } catch (e) { probes.jetloadDirectWithRef = { err: String(e) }; }
  try {
    const r = await curlReq(jetGw, { referer: "https://cimanow.cc/", timeoutSec: 10 });
    probes.jetloadGateway = { status: r.status, eff: r.effectiveUrl, sample: r.text.slice(0, 1200) };
  } catch (e) { probes.jetloadGateway = { err: String(e) }; }

  // 2) Reva CimaNowTV direct MP4
  try {
    const r = await curlReq(
      "https://reva.cimanowtv.com/uploads/2023/09/08/_Cima-Now.CoM_%20Beit.El.Ruby.2023.HD/%5BCima-Now.CoM%5D%20Beit.El.Ruby.2023.HD-720p.mp4?dl=1",
      { referer: "https://cimanow.cc/", range: "0-511", timeoutSec: 10 }
    );
    probes.revaDirect = { status: r.status, ct: r.contentType, eff: r.effectiveUrl };
  } catch (e) { probes.revaDirect = { err: String(e) }; }

  // 3) VK doc
  try {
    const r = await curlReq("https://vk.com/doc1100704408_708544332", { timeoutSec: 10 });
    probes.vkDoc = {
      status: r.status,
      eff: r.effectiveUrl,
      docUrls: [...r.text.matchAll(/https?:\\?\/\\?\/[^"'\s<>]+/g)].map((m) => m[0]).filter((u) => u.includes("vkuser") || u.includes("doc") || u.includes("mp4")).slice(0, 8),
      sample: r.text.slice(0, 1000),
    };
  } catch (e) { probes.vkDoc = { err: String(e) }; }

  // 4) Forafile
  try {
    const r = await curlReq("https://forafile.com/n4zcdwrwmfy1/%5BCima-Now.CoM%5D_Fall.2.Deadpoint.2026.HD-720p.mp4.html", { timeoutSec: 10 });
    probes.forafile = {
      status: r.status,
      forms: [...r.text.matchAll(/<input[^>]+>/gi)].map((m) => m[0]).slice(0, 15),
      mp4s: [...r.text.matchAll(/https?:\/\/[^"'\s<>]+\.mp4[^"'\s<>]*/gi)].map((m) => m[0]),
      sample: r.text.slice(0, 1000),
    };
  } catch (e) { probes.forafile = { err: String(e) }; }

  // 5) Frdl
  try {
    const r = await curlReq("https://frdl.my/8eolgnnxymz0/%5BCima-Now.CoM%5D+Fall.2.Deadpoint.2026.HD-720p.mp4", { timeoutSec: 10 });
    probes.frdl = {
      status: r.status,
      forms: [...r.text.matchAll(/<input[^>]+>/gi)].map((m) => m[0]).slice(0, 15),
      mp4s: [...r.text.matchAll(/https?:\/\/[^"'\s<>]+\.mp4[^"'\s<>]*/gi)].map((m) => m[0]),
      sample: r.text.slice(0, 1000),
    };
  } catch (e) { probes.frdl = { err: String(e) }; }

  return probes;
}

async function processItem(item, proxies, assetsMap, idx) {
  for (const proxy of proxies.slice(0, 4)) {
    const jar = path.join(os.tmpdir(), `cn_v17_${idx}_${Math.random().toString(36).slice(2)}.cookies`);
    try {
      const mv = await curlReq(item.url, { proxy, cookieJar: jar, referer: "https://cimanow.cc/home/" });
      if (mv.status !== 200 || isCimaBlock(mv.text)) continue;
      const shineUrl = mv.text.match(/<a\b[^>]*class=["'][^"']*shine[^"']*["'][^>]*href=["']([^"']+)["']/i)?.[1] || "";
      if (!shineUrl) continue;

      await curlReq(shineUrl, { proxy, cookieJar: jar, referer: item.url });
      await curlReq("https://rm.freex2line.online/redirectingfree/", { proxy, cookieJar: jar });
      const r3 = await curlReq("https://rm.freex2line.online/2020/02/blog-post.html/", {
        proxy,
        cookieJar: jar,
        referer: "https://rm.freex2line.online/redirectingfree/",
      });
      const gw = buildGatewayPostBody(r3.text);
      if (!gw) continue;

      await sleep(10300);
      const p10 = await curlReq("https://rm.freex2line.online/2020/02/blog-post.html/get-link.php", {
        proxy,
        cookieJar: jar,
        method: "POST",
        formBody: gw.postBody,
        referer: "https://rm.freex2line.online/2020/02/blog-post.html/",
        headers: {
          "Content-Type": "application/x-www-form-urlencoded",
          Origin: "https://rm.freex2line.online",
          "X-Requested-With": "XMLHttpRequest",
        },
      });
      const candUrl = p10.text.trim();
      if (!candUrl.startsWith("http") || !candUrl.includes("?token=") || candUrl.includes("/pig/")) continue;

      // Capture response headers of `/watching/?token=...` too (cookies set on cimanow.cc!)
      const watchRes = await curlReq(candUrl, {
        proxy,
        cookieJar: jar,
        referer: "https://rm.freex2line.online/2020/02/blog-post.html/",
      });
      if (watchRes.status !== 200 || isCimaBlock(watchRes.text)) continue;

      const rawVault = idx === 0 ? inspectRawWatchingVault(watchRes.text) : undefined;
      const cookiesAfterWatching = fs.existsSync(jar) ? fs.readFileSync(jar, "utf8") : "";

      const chromeRes = await renderAndSwitchInChrome(
        candUrl,
        watchRes.text,
        assetsMap,
        proxy,
        jar,
        9520 + idx
      );

      if (idx > 0 && chromeRes.clickInfo) {
        delete chromeRes.clickInfo.scriptsDom;
      }

      return {
        ok: true,
        proxy,
        mintedUrl: candUrl,
        cookiesAfterWatching,
        rawVault,
        chromeRes,
      };
    } catch {} finally {
      try { fs.rmSync(jar, { force: true }); } catch {}
    }
  }
  return { ok: false };
}

async function main() {
  const out = {};
  try {
    const proxies = await getWorkingProxies();
    out.proxies = proxies;
    const assetsMap = await fetchCimaAssets(proxies);
    out.loadedAssets = Object.keys(assetsMap);
    out.hostProbes = await probeDownloadHosts(proxies[0]);

    const items = [
      {
        name: "ForeignMovie_Fall2",
        url: "https://cimanow.cc/%d9%81%d9%8a%d9%84%d9%85-fall-2-deadpoint-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/",
      },
      {
        name: "ArabicMovie_BeitElRuby",
        url: "https://cimanow.cc/%d9%81%d9%8a%d9%84%d9%85-%d8%a8%d9%8a%d8%aa-%d8%a7%d9%84%d8%b1%d9%88%d8%a8%d9%8a-2023/",
      },
    ];

    out.items = {};
    for (let i = 0; i < items.length; i++) {
      out.items[items[i].name] = await processItem(items[i], proxies, assetsMap, i);
    }
  } catch (e) {
    out.fatalError = String(e.stack || e);
  }

  fs.writeFileSync("cimanow_recon.json", JSON.stringify(out, null, 2));
  console.log("Wrote v17 recon report, bytes:", fs.statSync("cimanow_recon.json").size);
}

main();
