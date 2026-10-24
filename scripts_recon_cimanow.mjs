import fs from "fs";
import os from "os";
import path from "path";
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
  if (proxy) args.push("-x", proxy.replace(/^socks5:\/\//, "socks5h://"));
  if (referer) args.push("-e", referer);
  if (cookieJar) args.push("-b", cookieJar, "-c", cookieJar);
  for (const [k, v] of Object.entries(headers)) args.push("-H", `${k}: ${v}`);
  args.push("-w", "\n__CURL_META__:%{http_code}|%{url_effective}|%{redirect_url}");
  args.push(url);
  const { stdout } = await execFileAsync("curl", args, { maxBuffer: 30 * 1024 * 1024 });
  const idx = stdout.lastIndexOf("\n__CURL_META__:");
  const text = idx >= 0 ? stdout.slice(0, idx) : stdout;
  const [codeStr, effectiveUrl, redirectUrl] = (idx >= 0 ? stdout.slice(idx + 15).trim() : "0||").split("|");
  return { status: Number(codeStr) || 0, effectiveUrl, redirectUrl, text };
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

function decodeVaultHtml(vaultHtml) {
  const arrStart = vaultHtml.search(/window\["[^"]+"\]\s*=\s*\[/);
  const openBracket = arrStart >= 0 ? vaultHtml.indexOf("[", vaultHtml.indexOf("=", arrStart)) : -1;
  const arrEnd = openBracket >= 0 ? vaultHtml.indexOf("];", openBracket) : -1;
  if (openBracket < 0 || arrEnd <= openBracket) return null;
  const arrSlice = vaultHtml.slice(openBracket + 1, arrEnd);
  const b64Joined = [...arrSlice.matchAll(/"([A-Za-z0-9+/=]+)"/g)].map((m) => m[1]).join("");
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
      const probe = Buffer.from(raw.slice(0, 96).map((v, i) => v ^ key[i % klen])).toString("utf8");
      if (probe.includes("<script>") && probe.includes("window.")) {
        const decoded = Buffer.from(raw.map((v, i) => v ^ key[i % klen])).toString("utf8");
        const topScript = decoded.match(/<script>([\s\S]*?)<\/script>/i)?.[1] || "";
        return { vaultKey: key.toString("utf8"), topScript, decodedHtml: decoded };
      }
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

async function fetchGatewayAssets(proxies) {
  const assets = {
    "https://rm.freex2line.online/dwcn/Assets/js/openads.js": "var zJSYdQ = true;",
  };
  const urls = [
    "https://rm.freex2line.online/dwcn/Assets/js/jquery-1.8.3.js",
    "https://rm.freex2line.online/dwcn/Assets/js/owl.carousel.min.js",
    "https://rm.freex2line.online/dwcn/Assets/js/jquery.countdown360.min.js",
    "https://cimanow.cc/wp-content/themes/Cima%20Now%20New/Assets/js/nbm2oHaQcN60y6Ozwm.js?v=2.3",
  ];
  for (const u of urls) {
    for (const p of [null, ...proxies.slice(0, 4)]) {
      try {
        const r = await curlReq(u, { proxy: p, timeoutSec: 10 });
        if (r.status === 200 && r.text.length > 500 && !r.text.includes("<html")) {
          assets[u] = r.text;
          break;
        }
      } catch {}
    }
  }
  return assets;
}

// Run LOCAL Chrome (NO proxy!) with Fetch.enable serving the decoded HTML & assets from memory in <50ms!
async function evaluatePageInLocalChrome(targetUrl, htmlBody, assetsMap, port, waitForGetLink = false) {
  const userDataDir = `/tmp/chrome_local_${port}_${Math.random().toString(36).slice(2)}`;
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
  let interceptedGetLinkPost = null;

  try {
    let wsUrl = null;
    for (let i = 0; i < 30; i++) {
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
            SubtleCrypto.prototype.digest = new Proxy(SubtleCrypto.prototype.digest, {
              apply(target, thisArg, args) {
                try {
                  if (args[1]) {
                    console.debug('__CRYPTO__:' + JSON.stringify({ op: 'digest', alg: args[0], data: toHex(args[1]) }));
                  }
                } catch (e) {}
                return Reflect.apply(target, thisArg, args);
              }
            });
          }
          TextEncoder.prototype.encode = new Proxy(TextEncoder.prototype.encode, {
            apply(target, thisArg, args) {
              try {
                if (typeof args[0] === 'string' && args[0].length > 2 && args[0].length < 400) {
                  console.debug('__CRYPTO__:' + JSON.stringify({ op: 'encode', str: args[0] }));
                }
              } catch (e) {}
              return Reflect.apply(target, thisArg, args);
            }
          });
        } catch (e) {}
      `,
    });

    cdp.on("Runtime.consoleAPICalled", (p) => {
      if (p.type === "debug" && p.args?.[0]?.value?.startsWith?.("__CRYPTO__:")) {
        try {
          const item = JSON.parse(p.args[0].value.slice("__CRYPTO__:".length));
          if (cryptoLogs.length < 80) cryptoLogs.push(item);
        } catch {}
      }
    });

    const normalizedTarget = new URL(targetUrl).href;
    cdp.on("Fetch.requestPaused", async (p) => {
      const u = p.request.url;
      try {
        if (u === normalizedTarget || u === targetUrl) {
          await cdp.send("Fetch.fulfillRequest", {
            requestId: p.requestId,
            responseCode: 200,
            responseHeaders: [{ name: "Content-Type", value: "text/html; charset=UTF-8" }],
            body: Buffer.from(htmlBody, "utf8").toString("base64"),
          });
        } else if (u.includes("get-link.php")) {
          interceptedGetLinkPost = {
            url: u,
            method: p.request.method,
            headers: p.request.headers,
            postData: p.request.postData || "",
          };
          await cdp.send("Fetch.fulfillRequest", {
            requestId: p.requestId,
            responseCode: 200,
            responseHeaders: [{ name: "Content-Type", value: "text/plain; charset=UTF-8" }],
            body: Buffer.from("https://cimanow.cc/watching/?token=dummy", "utf8").toString("base64"),
          });
        } else if (assetsMap[u]) {
          await cdp.send("Fetch.fulfillRequest", {
            requestId: p.requestId,
            responseCode: 200,
            responseHeaders: [{ name: "Content-Type", value: "application/javascript; charset=UTF-8" }],
            body: Buffer.from(assetsMap[u], "utf8").toString("base64"),
          });
        } else {
          const ct = u.endsWith(".js")
            ? "application/javascript"
            : u.endsWith(".css")
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

    await cdp.send("Page.navigate", { url: targetUrl });

    if (waitForGetLink) {
      for (let t = 0; t < 30; t++) {
        await sleep(500);
        if (interceptedGetLinkPost) break;
      }
      return { interceptedGetLinkPost, cryptoLogs };
    } else {
      await sleep(1500);
      const ev = await cdp.send("Runtime.evaluate", {
        expression: `(() => {
          const hasFn = typeof window._getCoreToken === 'function';
          let tokens = [], fnSrc = null;
          if (hasFn) {
            for (let i = 0; i < 16; i++) {
              try { tokens.push(window._getCoreToken()); } catch (e) { tokens.push('ERR:' + e); break; }
            }
            try { fnSrc = window._getCoreToken.toString().slice(0, 3000); } catch (e) {}
          }
          const winKeys = Object.keys(window).filter(k => /^(ctx_|map_|ptr_|_0x|_get|__)/.test(k));
          const winVals = {};
          for (const k of winKeys) {
            try { winVals[k] = typeof window[k] === 'function' ? window[k].toString().slice(0, 500) : window[k]; } catch (e) {}
          }
          return JSON.stringify({ hasFn, tokens, fnSrc, winKeys, winVals });
        })()`,
        returnByValue: true,
      });
      return {
        evalResult: ev?.result?.value ? JSON.parse(ev.result.value) : null,
        cryptoLogs,
      };
    }
  } finally {
    if (cdp) cdp.close();
    try { proc.kill("SIGKILL"); } catch {}
    try { fs.rmSync(userDataDir, { recursive: true, force: true }); } catch {}
  }
}

function parseWatchingDom(html) {
  const tabs = [];
  const watchBlock = html.match(/<ul[^>]*id=["']watch["'][^>]*>([\s\S]*?)<\/ul>/i)?.[1] || "";
  for (const m of watchBlock.matchAll(/<li\b([^>]*)>([\s\S]*?)<\/li>/gi)) {
    const attrs = m[1];
    const inner = m[2];
    const dataId = attrs.match(/data-id=["']([^"']+)["']/i)?.[1] || "";
    const dataIndex = attrs.match(/data-index=["']([^"']*)["']/i)?.[1] || "";
    const ariaLabel = attrs.match(/aria-label=["']([^"']*)["']/i)?.[1] || "";
    const text = inner.replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();
    const iframeSrc = inner.match(/<iframe[^>]+src=["']([^"']+)["']/i)?.[1] || "";
    tabs.push({ ariaLabel, dataIndex, dataId, text, iframeSrc });
  }

  const dlPos = html.search(/id=["']download["']/i);
  const dlRawSlice = dlPos >= 0 ? html.slice(Math.max(0, dlPos - 100), dlPos + 12000) : "";
  const dlLinks = [];
  if (dlRawSlice) {
    for (const m of dlRawSlice.matchAll(/<a\b[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)<\/a>/gi)) {
      dlLinks.push({
        href: m[1],
        text: m[2].replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim(),
      });
    }
  }

  const scripts = [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)].map((m, idx) => ({
    idx,
    attrs: m[1].trim(),
    len: m[2].length,
    preview: m[2].trim().slice(0, 600),
  }));

  return { tabs, dlLinks, dlRawSlice: dlRawSlice.slice(0, 6000), scripts };
}

async function processItem(item, proxies, assetsMap, idx) {
  let lastErr = null;
  for (const proxy of proxies.slice(0, 4)) {
    const jar = path.join(os.tmpdir(), `cn_v13_${idx}_${Math.random().toString(36).slice(2)}.cookies`);
    try {
      const mv = await curlReq(item.url, { proxy, cookieJar: jar, referer: "https://cimanow.cc/home/" });
      if (mv.status !== 200 || isCimaBlock(mv.text)) continue;
      const shineUrl = mv.text.match(/<a\b[^>]*class=["'][^"']*shine[^"']*["'][^>]*href=["']([^"']+)["']/i)?.[1] || "";
      if (!shineUrl) continue;

      // 1) loadon -> sets PHPSESSID
      const r1 = await curlReq(shineUrl, { proxy, cookieJar: jar, referer: item.url });
      // 2) redirectingfree (no referer, skips href.li!)
      const r2 = await curlReq("https://rm.freex2line.online/redirectingfree/", { proxy, cookieJar: jar });
      // 3) blog-post.html/
      const tFetchVault = Date.now();
      const r3 = await curlReq("https://rm.freex2line.online/2020/02/blog-post.html/", {
        proxy,
        cookieJar: jar,
        referer: "https://rm.freex2line.online/redirectingfree/",
      });
      const vault = decodeVaultHtml(r3.text);
      if (!vault) continue;

      // 4) Run decodedHtml in LOCAL Chrome (zero network!) to capture exact `get-link.php` POST + WebCrypto calls!
      const localGateway = await evaluatePageInLocalChrome(
        "https://rm.freex2line.online/2020/02/blog-post.html/",
        vault.decodedHtml,
        assetsMap,
        9320 + idx * 2,
        true
      );
      if (!localGateway.interceptedGetLinkPost?.postData) {
        return {
          ok: false,
          stage: "local_gateway_no_post",
          proxy,
          topScript: vault.topScript,
          localGateway,
        };
      }

      // Ensure at least ~10.2s elapsed since blog-post.html/ was issued on server if server checks timestamp,
      // wait — FIRST test immediate POST if <10s elapsed? Actually localGateway ALREADY waited ~10s for countdown!
      const elapsedMs = Date.now() - tFetchVault;
      const postRes = await curlReq("https://rm.freex2line.online/2020/02/blog-post.html/get-link.php", {
        proxy,
        cookieJar: jar,
        method: "POST",
        formBody: localGateway.interceptedGetLinkPost.postData,
        referer: "https://rm.freex2line.online/2020/02/blog-post.html/",
        headers: {
          "Content-Type": "application/x-www-form-urlencoded",
          Origin: "https://rm.freex2line.online",
          "X-Requested-With": "XMLHttpRequest",
        },
      });

      const mintedUrl = postRes.text.trim();
      if (!mintedUrl.startsWith("http")) {
        return {
          ok: false,
          stage: "get_link_php_rejected",
          proxy,
          elapsedMs,
          topScript: vault.topScript,
          localGateway,
          postStatus: postRes.status,
          postResp: postRes.text.slice(0, 600),
          cookiesFile: fs.existsSync(jar) ? fs.readFileSync(jar, "utf8") : "",
        };
      }

      // 5) Fetch `/watching/?token=...` via curl!
      const watchRes = await curlReq(mintedUrl, {
        proxy,
        cookieJar: jar,
        referer: "https://rm.freex2line.online/2020/02/blog-post.html/",
      });
      if (watchRes.status !== 200 || isCimaBlock(watchRes.text) || !watchRes.text.includes("id=\"watch\"")) {
        return {
          ok: false,
          stage: "watching_fetch_failed",
          proxy,
          mintedUrl,
          watchStatus: watchRes.status,
          watchEffectiveUrl: watchRes.effectiveUrl,
          watchPreview: watchRes.text.slice(0, 800),
          topScript: vault.topScript,
          localGateway,
        };
      }

      const dom = parseWatchingDom(watchRes.text);

      // 6) Evaluate `/watching/` HTML in LOCAL Chrome to capture `window._getCoreToken()` + WebCrypto calls!
      const localWatch = await evaluatePageInLocalChrome(
        mintedUrl,
        watchRes.text,
        assetsMap,
        9321 + idx * 2,
        false
      );

      // 7) Call `core.php?action=switch` for ALL tabs (first without token, then with `t1` / fresh tokens)!
      const validTabs = dom.tabs.filter((t) => t.dataId && t.dataIndex !== "00" && t.ariaLabel !== "embed");
      const switchResults = [];
      if (validTabs[0]) {
        const noTok = await curlReq(
          `https://cimanow.cc/wp-content/themes/Cima%20Now%20New/core.php?action=switch&index=${encodeURIComponent(validTabs[0].dataIndex)}&id=${encodeURIComponent(validTabs[0].dataId)}`,
          {
            proxy,
            cookieJar: jar,
            referer: mintedUrl,
            headers: { "X-Requested-With": "XMLHttpRequest" },
          }
        );
        switchResults.push({
          testNoToken: true,
          status: noTok.status,
          body: noTok.text.trim().slice(0, 500),
        });
      }

      const tokList = localWatch.evalResult?.tokens || [];
      for (let ti = 0; ti < validTabs.length; ti++) {
        const tab = validTabs[ti];
        const tokToUse = tokList[ti] || tokList[0] || "";
        const sw = await curlReq(
          `https://cimanow.cc/wp-content/themes/Cima%20Now%20New/core.php?token=${encodeURIComponent(tokToUse)}&action=switch&index=${encodeURIComponent(tab.dataIndex)}&id=${encodeURIComponent(tab.dataId)}`,
          {
            proxy,
            cookieJar: jar,
            referer: mintedUrl,
            headers: { "X-Requested-With": "XMLHttpRequest" },
          }
        );
        switchResults.push({
          name: tab.text,
          index: tab.dataIndex,
          id: tab.dataId,
          tokenUsed: tokToUse,
          status: sw.status,
          body: sw.text.trim().slice(0, 600),
        });
      }

      return {
        ok: true,
        proxy,
        elapsedMs,
        topScript: vault.topScript,
        gatewayPostData: localGateway.interceptedGetLinkPost.postData,
        gatewayCryptoLogs: localGateway.cryptoLogs,
        mintedUrl,
        cookiesAfterWatching: fs.existsSync(jar) ? fs.readFileSync(jar, "utf8") : "",
        watchingLocalEval: localWatch.evalResult,
        watchingCryptoLogs: localWatch.cryptoLogs,
        tabs: dom.tabs,
        dlLinks: dom.dlLinks,
        dlRawSlice: idx === 0 ? dom.dlRawSlice : undefined,
        watchingScripts: idx === 0 ? dom.scripts : undefined,
        switchResults,
      };
    } catch (e) {
      lastErr = String(e.stack || e);
    } finally {
      try { fs.rmSync(jar, { force: true }); } catch {}
    }
  }
  return { ok: false, stage: "all_proxies_failed", lastErr };
}

async function main() {
  const out = {};
  try {
    const proxies = await getWorkingProxies();
    out.proxies = proxies;
    const assetsMap = await fetchGatewayAssets(proxies);
    out.loadedAssets = Object.keys(assetsMap);

    const items = [
      {
        name: "ForeignMovie_Fall2",
        url: "https://cimanow.cc/%d9%81%d9%8a%d9%84%d9%85-fall-2-deadpoint-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/",
      },
      {
        name: "ArabicMovie_BeitElRuby",
        url: "https://cimanow.cc/%d9%81%d9%8a%d9%84%d9%85-%d8%a8%d9%8a%d8%aa-%d8%a7%d9%84%d8%b1%d9%88%d8%a8%d9%8a-2023/",
      },
      {
        name: "ForeignEpisode_LupinS4E1",
        url: "https://cimanow.cc/%d9%85%d8%b3%d9%84%d8%b3%d9%84-lupin-%d8%ac4-%d8%ad1-%d9%85%d8%aa%d8%b1%d8%ac%d9%85%d8%a9/",
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
  console.log("Wrote v13 recon report, bytes:", fs.statSync("cimanow_recon.json").size);
}

main();
