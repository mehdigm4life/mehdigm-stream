import fs from "fs";
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
  const { proxy = null, referer = null, timeoutSec = 14 } = opts;
  const args = [
    "-sS",
    "-k",
    "--compressed",
    "-L",
    "--max-redirs",
    "5",
    "--max-time",
    String(timeoutSec),
    "--connect-timeout",
    "7",
    "-A",
    UA,
    "-w",
    "\n__CURL_META__:%{http_code}|%{url_effective}",
  ];
  if (proxy) args.push("-x", proxy.replace(/^socks5:\/\//, "socks5h://"));
  if (referer) args.push("-e", referer);
  args.push(url);
  const { stdout } = await execFileAsync("curl", args, { maxBuffer: 25 * 1024 * 1024 });
  const idx = stdout.lastIndexOf("\n__CURL_META__:");
  const text = idx >= 0 ? stdout.slice(0, idx) : stdout;
  const [codeStr, effectiveUrl] = (idx >= 0 ? stdout.slice(idx + 15).trim() : "0|").split("|");
  return { status: Number(codeStr) || 0, effectiveUrl, text };
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
  for (let i = 0; i < candidates.length && working.length < 8; i += 25) {
    const batch = candidates.slice(i, i + 25);
    await Promise.all(
      batch.map(async (p) => {
        if (working.length >= 8) return;
        try {
          const r1 = await curlReq("https://cimanow.cc/home/", { proxy: p, timeoutSec: 9 });
          if (r1.status === 200 && !isCimaBlock(r1.text) && r1.text.includes("cimanow")) {
            const r2 = await curlReq("https://rm.freex2line.online/redirectingfree/", { proxy: p, timeoutSec: 9 });
            if (r2.status > 0 && !r2.text.includes("Just a moment...")) {
              working.push(p);
            }
          }
        } catch {}
      })
    );
  }
  return working;
}

function decodeVaultToCtx(vaultHtml) {
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
        const topSlice = Buffer.from(raw.slice(0, 1200).map((v, i) => v ^ key[i % klen])).toString("utf8");
        const topScript = topSlice.match(/<script>([\s\S]*?)<\/script>/i)?.[1] || "";
        return { vaultKey: key.toString("utf8"), topScript };
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
      }, 22000);
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

async function runChromeFlow(item, proxyUrl, port) {
  const userDataDir = `/tmp/chrome_cn_${port}_${Math.random().toString(36).slice(2)}`;
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
      `--proxy-server=${proxyUrl}`,
      `--remote-debugging-port=${port}`,
      `--user-data-dir=${userDataDir}`,
      "about:blank",
    ],
    { stdio: "ignore" }
  );

  const reqMap = new Map();
  let cdp = null;
  let blogPostVaultCtx = null;
  let getLinkEvent = null;
  let mintedWatchingUrl = "";
  let rawWatchingHtmlSample = "";
  const encodedStringsLog = [];

  try {
    let wsUrl = null;
    for (let i = 0; i < 30; i++) {
      await sleep(300);
      try {
        const res = await fetch(`http://127.0.0.1:${port}/json/list`);
        const pages = await res.json();
        const page = pages.find((p) => p.type === "page" && p.webSocketDebuggerUrl);
        if (page) { wsUrl = page.webSocketDebuggerUrl; break; }
      } catch {}
    }
    if (!wsUrl) throw new Error("Chrome CDP failed to start");

    cdp = new CDPClient(wsUrl);
    await cdp.connect();
    await cdp.send("Page.enable");
    await cdp.send("Network.enable");
    await cdp.send("Runtime.enable");

    // Intercept TextEncoder.prototype.encode + SubtleCrypto.prototype.importKey/sign via console.debug (does NOT touch XMLHttpRequest or window globals!)
    await cdp.send("Page.addScriptToEvaluateOnNewDocument", {
      source: `
        try { delete Object.getPrototypeOf(navigator).webdriver; } catch (e) {}
        try {
          const origEncode = TextEncoder.prototype.encode;
          TextEncoder.prototype.encode = function(str) {
            try {
              if (typeof str === 'string' && str.length > 4 && str.length < 400) {
                console.debug('__TE_ENC__:' + str);
              }
            } catch (e) {}
            return origEncode.call(this, str);
          };
        } catch (e) {}
      `,
    });

    cdp.on("Runtime.consoleAPICalled", (p) => {
      if (p.type === "debug" && p.args?.[0]?.value?.startsWith?.("__TE_ENC__:")) {
        const s = p.args[0].value.slice("__TE_ENC__:".length);
        if (!encodedStringsLog.includes(s) && encodedStringsLog.length < 60) {
          encodedStringsLog.push(s);
        }
      }
    });

    // Skip `href.li/__challenge` completely! Intercept navigation to `https://href.li/?...` or detect `loadon` finish!
    cdp.on("Network.requestWillBeSent", (p) => {
      reqMap.set(p.requestId, {
        requestId: p.requestId,
        type: p.type,
        method: p.request.method,
        url: p.request.url,
        headers: p.request.headers,
        postData: p.request.postData || null,
      });
    });

    cdp.on("Network.loadingFinished", async (p) => {
      const entry = reqMap.get(p.requestId);
      if (!entry) return;
      if (entry.url.endsWith("/2020/02/blog-post.html/")) {
        try {
          const b = await cdp.send("Network.getResponseBody", { requestId: p.requestId });
          const html = b.base64Encoded ? Buffer.from(b.body, "base64").toString("utf8") : b.body;
          blogPostVaultCtx = decodeVaultToCtx(html);
        } catch {}
      } else if (entry.url.includes("get-link.php")) {
        try {
          const b = await cdp.send("Network.getResponseBody", { requestId: p.requestId });
          const txt = (b.base64Encoded ? Buffer.from(b.body, "base64").toString("utf8") : b.body).trim();
          getLinkEvent = {
            url: entry.url,
            method: entry.method,
            headers: entry.headers,
            postData: entry.postData,
            respBody: txt,
          };
          if (txt.startsWith("http")) mintedWatchingUrl = txt;
        } catch {}
      } else if (entry.url.includes("/watching/")) {
        try {
          const b = await cdp.send("Network.getResponseBody", { requestId: p.requestId });
          const html = b.base64Encoded ? Buffer.from(b.body, "base64").toString("utf8") : b.body;
          if (html.length > 5000) rawWatchingHtmlSample = html.slice(0, 6000);
        } catch {}
      }
    });

    // Step 1: Load `shineUrl` (`loadon/?link=...`) just long enough to set `PHPSESSID`, then jump straight to `/redirectingfree/` (skipping `href.li`)!
    await cdp.send("Page.navigate", { url: item.shineUrl, referrer: item.url });
    for (let w = 0; w < 12; w++) {
      await sleep(500);
      const ck = await cdp.send("Network.getAllCookies");
      if ((ck.cookies || []).some((c) => c.name === "PHPSESSID" && c.domain.includes("freex2line"))) {
        break;
      }
    }
    // Navigate directly to `/redirectingfree/` with empty referrer (just like href.li does)!
    await cdp.send("Page.navigate", { url: "https://rm.freex2line.online/redirectingfree/", referrer: "" });

    // Wait up to 20s for `get-link.php` to return `mintedWatchingUrl`!
    for (let t = 0; t < 40; t++) {
      await sleep(500);
      if (mintedWatchingUrl) break;
    }

    if (!mintedWatchingUrl) {
      return {
        ok: false,
        stage: "waiting_for_get_link_php",
        proxyUsed: proxyUrl,
        blogPostVaultCtx,
        getLinkEvent,
        encodedStringsLog,
      };
    }

    // Step 2: Navigate directly to `mintedWatchingUrl` with Referer `https://rm.freex2line.online/2020/02/blog-post.html/`!
    await cdp.send("Page.navigate", {
      url: mintedWatchingUrl,
      referrer: "https://rm.freex2line.online/2020/02/blog-post.html/",
    });

    let watchingData = null;
    for (let t = 0; t < 20; t++) {
      await sleep(750);
      try {
        const evW = await cdp.send("Runtime.evaluate", {
          expression: `(() => {
            if (!document.querySelector('#watch')) return JSON.stringify({ ready: false, url: location.href, title: document.title, bodyLen: document.body ? document.body.innerHTML.length : 0 });
            const tabs = [...document.querySelectorAll('#watch li')].map(li => ({
              ariaLabel: li.getAttribute('aria-label') || '',
              dataIndex: li.getAttribute('data-index') || '',
              dataId: li.getAttribute('data-id') || '',
              cls: li.className || '',
              text: (li.innerText || li.textContent || '').trim(),
              innerHtml: li.innerHTML.slice(0, 600)
            }));
            const dlUl = document.querySelector('#download');
            const dlRawHtml = dlUl ? dlUl.outerHTML.slice(0, 10000) : '';
            const downloads = [...document.querySelectorAll('#download li')].map(q => ({
              ariaLabel: q.getAttribute('aria-label') || '',
              text: (q.innerText || '').trim().slice(0, 300),
              links: [...q.querySelectorAll('a[href]')].map(a => ({
                href: a.href,
                text: (a.innerText || a.textContent || '').trim()
              }))
            })).filter(d => d.links.length > 0);
            const scripts = [...document.querySelectorAll('script')].map((s, idx) => ({
              idx,
              src: s.src || '',
              id: s.id || '',
              len: (s.innerHTML || '').length,
              preview: (s.innerHTML || '').slice(0, 400)
            }));
            return JSON.stringify({
              ready: true,
              url: location.href,
              title: document.title,
              cookie: document.cookie,
              hasGetCoreToken: typeof window._getCoreToken === 'function',
              sampleCoreToken1: typeof window._getCoreToken === 'function' ? window._getCoreToken() : null,
              sampleCoreToken2: typeof window._getCoreToken === 'function' ? window._getCoreToken() : null,
              coreTokenFnSrc: typeof window._getCoreToken === 'function' ? window._getCoreToken.toString().slice(0, 3000) : null,
              tabs,
              dlRawHtml,
              downloads,
              scripts
            });
          })()`,
          returnByValue: true,
        });
        if (evW?.result?.value) {
          watchingData = JSON.parse(evW.result.value);
          if (watchingData.ready) break;
        }
      } catch {}
    }

    // Step 3: Switch EVERY server tab via `core.php`!
    let switchResults = [];
    if (watchingData?.ready) {
      const evSw = await cdp.send("Runtime.evaluate", {
        expression: `(async () => {
          const out = [];
          const lis = [...document.querySelectorAll('#watch li[data-id]')].filter(
            li => li.getAttribute('data-index') !== '00' && li.getAttribute('aria-label') !== 'embed'
          );
          if (lis[0]) {
            const r0 = await fetch('/wp-content/themes/Cima%20Now%20New/core.php?action=switch&index=' + encodeURIComponent(lis[0].dataset.index) + '&id=' + encodeURIComponent(lis[0].dataset.id), {
              headers: { 'X-Requested-With': 'XMLHttpRequest' }
            });
            out.push({ testNoToken: true, status: r0.status, body: (await r0.text()).trim().slice(0, 500) });
          }
          for (const li of lis) {
            const tok = typeof window._getCoreToken === 'function' ? window._getCoreToken() : '';
            const u = '/wp-content/themes/Cima%20Now%20New/core.php?token=' + encodeURIComponent(tok) + '&action=switch&index=' + encodeURIComponent(li.dataset.index) + '&id=' + encodeURIComponent(li.dataset.id);
            const r = await fetch(u, { headers: { 'X-Requested-With': 'XMLHttpRequest' } });
            out.push({
              name: (li.innerText || '').trim(),
              index: li.dataset.index,
              id: li.dataset.id,
              tokenUsed: tok,
              status: r.status,
              body: (await r.text()).trim().slice(0, 600)
            });
          }
          return JSON.stringify(out);
        })()`,
        awaitPromise: true,
        returnByValue: true,
      });
      if (evSw?.result?.value) switchResults = JSON.parse(evSw.result.value);
    }

    const ckEnd = await cdp.send("Network.getAllCookies");
    const allBrowserCookies = (ckEnd.cookies || []).filter(
      (c) => c.domain.includes("cimanow") || c.domain.includes("freex2line")
    );

    return {
      ok: !!watchingData?.ready,
      proxyUsed: proxyUrl,
      blogPostVaultCtx,
      getLinkEvent,
      mintedWatchingUrl,
      encodedStringsLog,
      rawWatchingHtmlSample,
      allBrowserCookies,
      watchingData,
      switchResults,
    };
  } finally {
    if (cdp) cdp.close();
    try { proc.kill("SIGKILL"); } catch {}
    try { fs.rmSync(userDataDir, { recursive: true, force: true }); } catch {}
  }
}

async function main() {
  const out = {};
  try {
    const proxies = await getWorkingProxies();
    out.proxies = proxies;

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
      const it = items[i];
      let shineUrl = "";
      let workingP = proxies[0];
      for (const p of proxies) {
        try {
          const mv = await curlReq(it.url, { proxy: p, referer: "https://cimanow.cc/home/" });
          if (mv.status === 200 && !isCimaBlock(mv.text)) {
            shineUrl = mv.text.match(/<a\b[^>]*class=["'][^"']*shine[^"']*["'][^>]*href=["']([^"']+)["']/i)?.[1] || "";
            workingP = p;
            if (shineUrl) break;
          }
        } catch {}
      }
      it.shineUrl = shineUrl;

      let flowRes = null;
      for (const candP of [workingP, ...proxies.filter((x) => x !== workingP)].slice(0, 2)) {
        try {
          flowRes = await runChromeFlow(it, candP, 9222 + i);
          if (flowRes.ok) break;
        } catch (e) {
          flowRes = { ok: false, err: String(e.stack || e), proxyTried: candP };
        }
      }
      if (i > 0 && flowRes?.watchingData) {
        delete flowRes.watchingData.dlRawHtml;
        delete flowRes.watchingData.scripts;
        delete flowRes.rawWatchingHtmlSample;
      }
      out.items[it.name] = flowRes;
    }
  } catch (e) {
    out.fatalError = String(e.stack || e);
  }

  fs.writeFileSync("cimanow_recon.json", JSON.stringify(out, null, 2));
  console.log("Wrote v12 recon report, bytes:", fs.statSync("cimanow_recon.json").size);
}

main();
