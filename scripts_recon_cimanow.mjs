import fs from "fs";
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
          // Test BOTH cimanow.cc AND rm.freex2line.online so Chrome proxy works for both!
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

  const netEvents = [];
  const reqMap = new Map();
  let cdp = null;

  try {
    // Wait for /json/list
    let wsUrl = null;
    for (let i = 0; i < 30; i++) {
      await sleep(300);
      try {
        const res = await fetch(`http://127.0.0.1:${port}/json/list`);
        const pages = await res.json();
        const page = pages.find((p) => p.type === "page" && p.webSocketDebuggerUrl);
        if (page) {
          wsUrl = page.webSocketDebuggerUrl;
          break;
        }
      } catch {}
    }
    if (!wsUrl) throw new Error("Chrome CDP failed to start");

    cdp = new CDPClient(wsUrl);
    await cdp.connect();
    await cdp.send("Page.enable");
    await cdp.send("Network.enable");
    await cdp.send("Runtime.enable");

    // Ensure `navigator.webdriver` is completely absent on prototype (no own property!)
    await cdp.send("Page.addScriptToEvaluateOnNewDocument", {
      source: `
        try {
          delete Object.getPrototypeOf(navigator).webdriver;
        } catch (e) {}
      `,
    });

    cdp.on("Network.requestWillBeSent", (p) => {
      const u = p.request.url;
      if (
        u.includes("freex2line") ||
        u.includes("cimanow") ||
        u.includes("href.li") ||
        p.type === "XHR" ||
        p.type === "Fetch" ||
        p.type === "Document"
      ) {
        if (!u.match(/\.(png|jpg|jpeg|gif|webp|svg|woff2?|css)(\?|$)/i)) {
          const entry = {
            requestId: p.requestId,
            type: p.type,
            method: p.request.method,
            url: u,
            headers: p.request.headers,
            postData: p.request.postData || null,
          };
          reqMap.set(p.requestId, entry);
          netEvents.push(entry);
        }
      }
    });

    cdp.on("Network.responseReceived", (p) => {
      const entry = reqMap.get(p.requestId);
      if (entry) {
        entry.status = p.response.status;
        entry.respHeaders = p.response.headers;
        entry.mimeType = p.response.mimeType;
      }
    });

    cdp.on("Network.loadingFinished", async (p) => {
      const entry = reqMap.get(p.requestId);
      if (
        entry &&
        (entry.type === "XHR" ||
          entry.type === "Fetch" ||
          entry.url.includes("core.php") ||
          entry.url.includes("mint") ||
          entry.url.includes("token") ||
          entry.url.includes("ajax") ||
          (entry.url.includes("freex2line") && !entry.url.endsWith("blog-post.html/")))
      ) {
        try {
          const b = await cdp.send("Network.getResponseBody", { requestId: p.requestId });
          entry.respBody = (b.base64Encoded ? Buffer.from(b.body, "base64").toString("utf8") : b.body).slice(0, 4000);
        } catch {}
      }
    });

    // Step 1: Open item page or navigate directly to `shineUrl` with Referer `item.url`!
    await cdp.send("Page.navigate", { url: item.shineUrl, referrer: item.url });

    // Wait up to 22s for countdown + mint XHR + `#downloadbtn` to become visible/populated!
    let gatewayDomState = null;
    for (let t = 0; t < 24; t++) {
      await sleep(1000);
      try {
        const ev = await cdp.send("Runtime.evaluate", {
          expression: `(() => {
            const anchors = [...document.querySelectorAll('a, button, #downloadbtn, .downloadbtn')].map(el => ({
              tag: el.tagName,
              id: el.id,
              cls: el.className,
              href: el.href || el.getAttribute('href') || el.getAttribute('data-href') || '',
              text: (el.innerText || el.textContent || '').trim().slice(0, 120),
              rect: el.getBoundingClientRect ? el.getBoundingClientRect().toJSON() : null
            }));
            return JSON.stringify({
              url: location.href,
              title: document.title,
              hasWatch: !!document.querySelector('#watch'),
              anchors: anchors.filter(a => a.id || a.cls || a.href.includes('cimanow') || a.text.includes('مشاهد') || a.text.includes('انقر') || a.text.includes('تحميل'))
            });
          })()`,
          returnByValue: true,
        });
        if (ev?.result?.value) {
          gatewayDomState = JSON.parse(ev.result.value);
          // Check if an anchor pointing to cimanow watching appeared and has width > 0!
          const readyBtn = gatewayDomState.anchors?.find(
            (a) => (a.href && a.href.includes("cimanow") && a.rect && a.rect.width > 0) || gatewayDomState.hasWatch
          );
          if (readyBtn && t >= 12) break;
        }
      } catch {}
    }

    // Also grab stripped body HTML of blog-post.html so we see exact markup!
    let strippedGatewayHtml = "";
    try {
      const evHtml = await cdp.send("Runtime.evaluate", {
        expression: `(() => {
          const clone = document.documentElement.cloneNode(true);
          clone.querySelectorAll('script, style, svg').forEach(e => e.remove());
          return clone.outerHTML.slice(0, 12000);
        })()`,
        returnByValue: true,
      });
      strippedGatewayHtml = evHtml?.result?.value || "";
    } catch {}

    // Find the button to click with a REAL trusted mouse click (`Input.dispatchMouseEvent`)!
    let clickInfo = null;
    if (gatewayDomState && !gatewayDomState.hasWatch) {
      const targetBtn =
        gatewayDomState.anchors?.find((a) => a.href && a.href.includes("cimanow") && a.rect && a.rect.width > 0) ||
        gatewayDomState.anchors?.find((a) => (a.id === "downloadbtn" || a.cls.includes("downloadbtn")) && a.rect && a.rect.width > 0) ||
        gatewayDomState.anchors?.find((a) => a.rect && a.rect.width > 0 && a.href && !a.href.startsWith("javascript"));

      if (targetBtn && targetBtn.rect) {
        // Scroll element into view first and get fresh coordinates!
        const freshBoxEv = await cdp.send("Runtime.evaluate", {
          expression: `(() => {
            const el = document.querySelector('#downloadbtn a, a#downloadbtn, .downloadbtn a, a.downloadbtn, a[href*="cimanow"]');
            if (!el) return null;
            el.scrollIntoView({ block: 'center' });
            const r = el.getBoundingClientRect();
            return JSON.stringify({ x: r.left + r.width / 2, y: r.top + r.height / 2, href: el.href, outer: el.outerHTML.slice(0, 500) });
          })()`,
          returnByValue: true,
        });
        if (freshBoxEv?.result?.value) {
          const box = JSON.parse(freshBoxEv.result.value);
          clickInfo = box;
          await cdp.send("Input.dispatchMouseEvent", { type: "mousePressed", x: box.x, y: box.y, button: "left", clickCount: 1 });
          await cdp.send("Input.dispatchMouseEvent", { type: "mouseReleased", x: box.x, y: box.y, button: "left", clickCount: 1 });
        }
      }
    }

    // Wait up to 16s for `/watching/` (`#watch`) to load!
    let watchingData = null;
    for (let t = 0; t < 16; t++) {
      await sleep(1000);
      try {
        // Check if a new tab was opened (`target="_blank"`)!
        const listRes = await fetch(`http://127.0.0.1:${port}/json/list`);
        const pages = await listRes.json();
        const watchTab = pages.find((p) => p.url && p.url.includes("cimanow.cc") && p.webSocketDebuggerUrl);
        if (watchTab && watchTab.webSocketDebuggerUrl !== wsUrl) {
          const tabCdp = new CDPClient(watchTab.webSocketDebuggerUrl);
          await tabCdp.connect();
          await tabCdp.send("Runtime.enable");
          await tabCdp.send("Network.enable");
          cdp.close();
          cdp = tabCdp;
          wsUrl = watchTab.webSocketDebuggerUrl;
        }

        const evW = await cdp.send("Runtime.evaluate", {
          expression: `(() => {
            if (!document.querySelector('#watch')) return JSON.stringify({ ready: false, url: location.href, title: document.title });
            const tabs = [...document.querySelectorAll('#watch li')].map(li => ({
              ariaLabel: li.getAttribute('aria-label') || '',
              dataIndex: li.getAttribute('data-index') || '',
              dataId: li.getAttribute('data-id') || '',
              cls: li.className || '',
              text: (li.innerText || li.textContent || '').trim(),
              innerHtml: li.innerHTML.slice(0, 600)
            }));
            const downloads = [...document.querySelectorAll('#download li[aria-label="quality"], #download > li')].map(q => ({
              qualityHeader: (q.querySelector('span, h3, h4, em, b') || {}).innerText || q.innerText.split('\\n')[0] || '',
              links: [...q.querySelectorAll('a[href]')].map(a => ({
                href: a.href,
                text: (a.innerText || a.textContent || '').trim()
              }))
            }));
            const scripts = [...document.querySelectorAll('script')].map((s, idx) => ({
              idx,
              src: s.src || '',
              id: s.id || '',
              len: (s.innerHTML || '').length,
              preview: (s.innerHTML || '').slice(0, 500)
            }));
            return JSON.stringify({
              ready: true,
              url: location.href,
              title: document.title,
              cookie: document.cookie,
              hasGetCoreToken: typeof window._getCoreToken === 'function',
              sampleCoreToken: typeof window._getCoreToken === 'function' ? window._getCoreToken() : null,
              coreTokenFnSrc: typeof window._getCoreToken === 'function' ? window._getCoreToken.toString().slice(0, 2000) : null,
              tabs,
              downloads,
              scripts
            });
          })()`,
          returnByValue: true,
        });
        if (evW?.result?.value) {
          const parsed = JSON.parse(evW.result.value);
          watchingData = parsed;
          if (parsed.ready) break;
        }
      } catch {}
    }

    // If `#watch` is ready, switch EVERY server tab right inside the page via fetch/jQuery so we get EVERY iframe!
    let switchResults = [];
    if (watchingData?.ready) {
      const evSw = await cdp.send("Runtime.evaluate", {
        expression: `(async () => {
          const out = [];
          const lis = [...document.querySelectorAll('#watch li[data-id]')].filter(
            li => li.getAttribute('data-index') !== '00' && li.getAttribute('aria-label') !== 'embed'
          );
          // Test first tab WITHOUT token to see if core.php requires token!
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

    // Also dump all cookies in Chrome's cookie jar!
    let allBrowserCookies = [];
    try {
      const ck = await cdp.send("Network.getAllCookies");
      allBrowserCookies = (ck.cookies || []).filter(
        (c) => c.domain.includes("cimanow") || c.domain.includes("freex2line")
      );
    } catch {}

    return {
      ok: !!watchingData?.ready,
      proxyUsed: proxyUrl,
      gatewayDomState,
      strippedGatewayHtml: strippedGatewayHtml.slice(0, 8000),
      clickInfo,
      allBrowserCookies,
      netEvents,
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
      // Get `.shine` URL via curl
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

      // Try up to 2 proxies in Chrome if first proxy stalls
      let flowRes = null;
      for (const candP of [workingP, ...proxies.filter((x) => x !== workingP)].slice(0, 3)) {
        try {
          flowRes = await runChromeFlow(it, candP, 9222 + i);
          if (flowRes.ok) break;
        } catch (e) {
          flowRes = { ok: false, err: String(e.stack || e), proxyTried: candP };
        }
      }
      if (i > 0 && flowRes?.strippedGatewayHtml) delete flowRes.strippedGatewayHtml;
      out.items[it.name] = flowRes;
    }
  } catch (e) {
    out.fatalError = String(e.stack || e);
  }

  fs.writeFileSync("cimanow_recon.json", JSON.stringify(out, null, 2));
  console.log("Wrote v11 recon report, bytes:", fs.statSync("cimanow_recon.json").size);
}

main();
