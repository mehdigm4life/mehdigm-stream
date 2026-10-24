import fs from "node:fs";
import { execFile, spawn } from "node:child_process";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

const DESKTOP_UA =
  "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

const KNOWN_GOOD_PROXIES = [
  "socks5h://184.178.172.25:15291",
  "socks5h://184.178.172.5:15303",
  "socks5h://45.74.31.46:8287",
  "socks5h://45.74.31.46:8506",
  "socks5h://91.107.179.68:10809",
  "socks5h://45.74.31.46:8478",
];

const PROXY_FEEDS = [
  ["socks5h", "https://raw.githubusercontent.com/vakhov/fresh-proxy-list/master/socks5.txt"],
  ["http", "https://raw.githubusercontent.com/vakhov/fresh-proxy-list/master/https.txt"],
  ["socks5h", "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt"],
];

function isCimaBlock(html) {
  return !html || html.includes("ستوب! المخرج عايز كدة") || html.includes("مشهد محذوف");
}

async function curlReq(
  url,
  {
    method = "GET",
    postData = null,
    proxy = null,
    referer = null,
    extraHeaders = [],
    cookieJarFile = null,
    timeoutSec = 14,
    follow = true,
    includeHeaders = false,
  } = {}
) {
  const hdrFile = `/tmp/curl_h_${Math.random().toString(36).slice(2)}.txt`;
  const args = [
    "-sS",
    "-k",
    "--compressed",
    "--max-time",
    String(timeoutSec),
    "--connect-timeout",
    "5",
    "-X",
    method,
    "-A",
    DESKTOP_UA,
    "-H",
    "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "-H",
    "Accept-Language: ar,en-US;q=0.9,en;q=0.8",
  ];
  if (postData !== null) args.push("--data", postData);
  if (includeHeaders) args.push("-D", hdrFile);
  args.push("-w", "\n__CURL_META__:%{http_code}|%{url_effective}|%{redirect_url}");
  if (follow) args.push("-L", "--max-redirs", "6");
  if (proxy) args.push("-x", proxy);
  if (referer) args.push("-e", referer);
  if (cookieJarFile) args.push("-b", cookieJarFile, "-c", cookieJarFile);
  for (const h of extraHeaders) args.push("-H", h);
  args.push(url);

  try {
    const t0 = Date.now();
    const { stdout } = await execFileAsync("curl", args, { maxBuffer: 25 * 1024 * 1024 });
    const elapsedMs = Date.now() - t0;
    const markerIdx = stdout.lastIndexOf("\n__CURL_META__:");
    const text = markerIdx >= 0 ? stdout.slice(0, markerIdx) : stdout;
    const [codeStr, finalUrl, redirectUrl] =
      markerIdx >= 0 ? stdout.slice(markerIdx + "\n__CURL_META__:".length).trim().split("|") : ["0", url, ""];
    const respHeaders = includeHeaders && fs.existsSync(hdrFile) ? fs.readFileSync(hdrFile, "utf8") : "";
    return { status: Number(codeStr) || 0, finalUrl, redirectUrl, respHeaders, text, elapsedMs };
  } finally {
    if (fs.existsSync(hdrFile)) try { fs.unlinkSync(hdrFile); } catch {}
  }
}

async function getRankedProxies() {
  const results = [];
  await Promise.all(
    KNOWN_GOOD_PROXIES.map(async (p) => {
      try {
        const r = await curlReq("https://cimanow.cc/home/", { proxy: p, timeoutSec: 8 });
        if (r.status === 200 && !isCimaBlock(r.text) && r.text.length > 5000) {
          results.push({ proxy: p, ms: r.elapsedMs });
        }
      } catch {}
    })
  );

  const candidates = [];
  await Promise.all(
    PROXY_FEEDS.map(async ([scheme, feedUrl]) => {
      try {
        const res = await fetch(feedUrl, { signal: AbortSignal.timeout(6000) });
        const txt = await res.text();
        for (const raw of txt.split(/\r?\n/).slice(0, 100)) {
          const line = raw.trim().replace(/^(https?|socks5h?):\/\//i, "");
          if (/^\d+\.\d+\.\d+\.\d+:\d+$/.test(line)) candidates.push(`${scheme}://${line}`);
        }
      } catch {}
    })
  );
  let idx = 0;
  await Promise.all(
    Array.from({ length: 30 }, async () => {
      while (idx < candidates.length && results.length < 6) {
        const p = candidates[idx++];
        try {
          const r = await curlReq("https://cimanow.cc/home/", { proxy: p, timeoutSec: 7 });
          if (r.status === 200 && !isCimaBlock(r.text) && r.text.length > 5000) {
            results.push({ proxy: p, ms: r.elapsedMs });
          }
        } catch {}
      }
    })
  );

  results.sort((a, b) => a.ms - b.ms);
  return results;
}

async function probeWatchingMatrixWithCurl(proxies, movieUrl, shineUrl) {
  const out = {};
  const watchUrl = movieUrl.replace(/\/?$/, "/watching/");

  // A. Test `rm.freex2line.online/loadon/?link=...` across ALL working proxies!
  out.freex2lineAcrossProxies = [];
  for (const p of proxies) {
    try {
      const r = await curlReq(shineUrl, {
        proxy: p,
        referer: movieUrl,
        includeHeaders: true,
        timeoutSec: 12,
      });
      out.freex2lineAcrossProxies.push({
        proxy: p,
        status: r.status,
        finalUrl: r.finalUrl,
        headers: r.respHeaders.slice(0, 800),
        title: r.text.match(/<title[^>]*>([\s\S]*?)<\/title>/i)?.[1]?.trim() || "",
        len: r.text.length,
        bodySample: r.text.slice(0, 3500),
      });
      if (r.status === 200 && !r.text.includes("Just a moment")) break;
    } catch (e) {
      out.freex2lineAcrossProxies.push({ proxy: p, err: String(e.message || e) });
    }
  }

  // B. Test HTTP POST & custom Referers / query params directly to `/watching/`!
  const p0 = proxies[0];
  const tests = [
    { name: "POST_empty_ref_movie", method: "POST", postData: "", referer: movieUrl },
    { name: "POST_empty_ref_freex2line", method: "POST", postData: "", referer: shineUrl },
    { name: "GET_ref_full_shineUrl", method: "GET", referer: shineUrl },
    { name: "GET_ref_vip_cimanowinc", method: "GET", referer: "https://vip.cimanowinc.com/" },
    { name: "GET_ref_cimanow_home", method: "GET", referer: "https://cimanow.cc/home/" },
    { name: "GET_no_follow_inspect_302", method: "GET", referer: shineUrl, follow: false },
  ];
  out.directWatchProbes = [];
  for (const t of tests) {
    try {
      const r = await curlReq(watchUrl, {
        proxy: p0,
        method: t.method,
        postData: t.postData ?? null,
        referer: t.referer,
        follow: t.follow ?? true,
        includeHeaders: true,
      });
      out.directWatchProbes.push({
        name: t.name,
        status: r.status,
        finalUrl: r.finalUrl,
        redirectUrl: r.redirectUrl,
        headers: r.respHeaders,
        hasWatchId: r.text.includes('id="watch"') || r.text.includes("id='watch'"),
        hasCorePhp: r.text.includes("core.php"),
        len: r.text.length,
        sample: r.text.includes("core.php") || r.text.includes('id="watch"') ? r.text.slice(0, 4000) : r.text.slice(0, 400),
      });
    } catch (e) {
      out.directWatchProbes.push({ name: t.name, err: String(e.message || e) });
    }
  }

  // C. Also test calling `core.php?action=switch&index=0&id=...` directly if we know or guess post ID (check wp shortlink / body classes / watchlist `data-post` on movie HTML!)
  const mv = await curlReq(movieUrl, { proxy: p0, referer: "https://cimanow.cc/home/", includeHeaders: true });
  const dataPostMatch = mv.text.match(/data-post=["'](\d+)["']|post[_-]?id["'\s:=]+(\d+)|\?p=(\d+)/i);
  const allDataAttrs = [...mv.text.matchAll(/\bdata-[a-z0-9_-]+=["'][^"']*["']/gi)].map((m) => m[0]).slice(0, 30);
  const metaTags = [...mv.text.matchAll(/<(?:meta|link)\b[^>]+>/gi)].map((m) => m[0]);
  out.moviePostIdClues = {
    dataPostMatch: dataPostMatch?.[0] || null,
    allDataAttrs,
    metaAndLinkTags: metaTags,
  };

  return out;
}

async function runFastXvfbChrome(rankedProxies, movieUrl, epUrl, shineUrl) {
  const chromeBin = ["/usr/bin/google-chrome", "/usr/bin/google-chrome-stable", "/usr/bin/chromium-browser"].find((p) =>
    fs.existsSync(p)
  );
  const xvfbBin = "/usr/bin/xvfb-run";
  if (!chromeBin || rankedProxies.length === 0) return { available: false };

  // Pick fastest SOCKS5 proxy (or HTTP proxy)
  const chosen = rankedProxies.find((x) => x.proxy.startsWith("socks5")) || rankedProxies[0];
  const rawProxy = chosen.proxy;
  const hostPort = rawProxy.replace(/^[a-z0-9]+:\/\//i, "");
  const proxyHost = hostPort.split(":")[0];
  const chromeProxyScheme = rawProxy.startsWith("socks5") ? "socks5" : "http";

  const port = 9235;
  const userDataDir = `/tmp/chrome_fast_${Date.now()}`;
  const chromeArgs = [
    "--no-sandbox",
    "--disable-setuid-sandbox",
    "--disable-dev-shm-usage",
    "--disable-blink-features=AutomationControlled",
    "--no-first-run",
    "--no-default-browser-check",
    "--window-size=1366,768",
    `--proxy-server=${chromeProxyScheme}://${hostPort}`,
    `--host-resolver-rules=MAP * ~NOTFOUND , EXCLUDE ${proxyHost} , EXCLUDE 127.0.0.1`,
    `--remote-debugging-port=${port}`,
    `--user-data-dir=${userDataDir}`,
    "about:blank",
  ];

  const proc = fs.existsSync(xvfbBin)
    ? spawn(xvfbBin, ["-a", chromeBin, ...chromeArgs], { stdio: "ignore" })
    : spawn(chromeBin, ["--headless=new", ...chromeArgs], { stdio: "ignore" });

  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  try {
    let targets = null;
    for (let i = 0; i < 30; i++) {
      await sleep(300);
      try {
        const res = await fetch(`http://127.0.0.1:${port}/json/list`);
        targets = await res.json();
        if (Array.isArray(targets) && targets.length > 0) break;
      } catch {}
    }
    const pageTarget = targets?.find((t) => t.type === "page") || targets?.[0];
    if (!pageTarget?.webSocketDebuggerUrl) return { available: true, error: "No CDP target" };

    const ws = new WebSocket(pageTarget.webSocketDebuggerUrl);
    await new Promise((resolve, reject) => {
      ws.addEventListener("open", resolve, { once: true });
      ws.addEventListener("error", reject, { once: true });
    });

    let msgId = 1;
    const pending = new Map();
    const netTrace = [];

    const cdp = (method, params = {}) =>
      new Promise((resolve, reject) => {
        const id = msgId++;
        pending.set(id, { resolve, reject });
        ws.send(JSON.stringify({ id, method, params }));
        setTimeout(() => {
          if (pending.has(id)) {
            pending.delete(id);
            reject(new Error(`CDP timeout: ${method}`));
          }
        }, 20000);
      });

    ws.addEventListener("message", async (ev) => {
      const msg = JSON.parse(String(ev.data));
      if (msg.id && pending.has(msg.id)) {
        const { resolve, reject } = pending.get(msg.id);
        pending.delete(msg.id);
        if (msg.error) reject(new Error(msg.error.message));
        else resolve(msg.result);
      } else if (msg.method === "Fetch.requestPaused") {
        const reqUrl = msg.params.request.url;
        const reqId = msg.params.requestId;
        // Abort heavy images/CSS-JS-packer/popunder ads so pages load in <1s over SOCKS5!
        if (
          /\.(png|jpe?g|gif|webp|svg|woff2?|ttf)(\?|$)/i.test(reqUrl) ||
          reqUrl.includes("0CYA6X1KhKIS.js") ||
          reqUrl.includes("llvpn.com") ||
          reqUrl.includes("keewoach") ||
          reqUrl.includes("viiukuhe") ||
          reqUrl.includes("can-get-some") ||
          reqUrl.includes("fontawesome")
        ) {
          cdp("Fetch.fulfillRequest", {
            requestId: reqId,
            responseCode: 200,
            responseHeaders: [{ name: "Content-Type", value: "application/javascript" }],
            body: "",
          }).catch(() => {});
        } else {
          cdp("Fetch.continueRequest", { requestId: reqId }).catch(() => {});
        }
      } else if (msg.method === "Network.requestWillBeSent") {
        const u = msg.params.request.url;
        if (!/\.(png|jpe?g|gif|webp|svg|css)(\?|$)/i.test(u) && !u.startsWith("data:")) {
          netTrace.push({
            ev: "req",
            url: u,
            method: msg.params.request.method,
            headers: msg.params.request.headers,
            postData: msg.params.request.postData,
          });
        }
      } else if (msg.method === "Network.responseReceived") {
        const u = msg.params.response.url;
        if (!/\.(png|jpe?g|gif|webp|svg|css)(\?|$)/i.test(u) && !u.startsWith("data:")) {
          netTrace.push({
            ev: "resp",
            url: u,
            status: msg.params.response.status,
            headers: msg.params.response.headers,
          });
        }
      }
    });

    await cdp("Page.enable");
    await cdp("Network.enable");
    await cdp("Fetch.enable", { patterns: [{ urlPattern: "*" }] });
    await cdp("Page.addScriptToEvaluateOnNewDocument", {
      source: `Object.defineProperty(navigator, 'webdriver', { get: () => undefined });`,
    });

    const evalJs = async (expression) => {
      const r = await cdp("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true });
      return r?.result?.value;
    };

    const result = { chosenProxy: rawProxy };

    // 1. Navigate directly to `shineUrl` (`https://rm.freex2line.online/loadon/?link=...`) with `referrer: movieUrl` in REAL Xvfb Chrome!
    await cdp("Page.navigate", { url: shineUrl, referrer: movieUrl });
    for (let i = 0; i < 20; i++) {
      await sleep(1000);
      const st = await evalJs(`({ url: location.href, title: document.title, ready: document.readyState, len: document.body?.innerHTML?.length || 0 })`);
      if (st && st.ready === "complete" && !st.title.includes("Just a moment") && st.len > 300) break;
    }

    result.gatewayStep1 = await evalJs(`(() => ({
      url: location.href,
      title: document.title,
      cookie: document.cookie,
      watchTabsCount: document.querySelectorAll('#watch li').length,
      htmlSample: document.documentElement?.outerHTML?.slice(0, 6000) || '',
      scripts: [...document.querySelectorAll('script')].map(s => ({ src: s.src, code: s.innerHTML.slice(0, 1500) })),
      formsAndLinks: [...document.querySelectorAll('form, a[href], button')].slice(0, 25).map(e => e.outerHTML.slice(0, 400)),
    }))()`);

    // If gateway has a form or link or timer, wait/interact up to 12s to reach `/watching/`!
    for (let step = 0; step < 3; step++) {
      const onWatch = await evalJs(`document.querySelectorAll('#watch li').length > 0 || location.href.includes('/watching/')`);
      if (onWatch) break;
      await evalJs(`(() => {
        const f = document.querySelector('form');
        if (f) { f.submit(); return 'form-submitted'; }
        const btn = [...document.querySelectorAll('a[href], button')].find(el =>
          /watch|continue|skip|مشاهد|انتقل|الرابط|اضغط/i.test(el.innerText || '') || (el.href && el.href.includes('cimanow'))
        );
        if (btn) { btn.removeAttribute('target'); btn.click(); return 'clicked'; }
      })()`);
      await sleep(4000);
    }

    result.afterGateway = await evalJs(`(() => ({
      url: location.href,
      title: document.title,
      cookie: document.cookie,
      hasTokenFn: typeof window._getCoreToken,
      tokenVal: typeof window._getCoreToken === 'function' ? window._getCoreToken() : null,
      tokenFnSrc: typeof window._getCoreToken === 'function' ? window._getCoreToken.toString().slice(0, 1500) : null,
      watchTabsCount: document.querySelectorAll('#watch li').length,
      scriptsOnPage: [...document.querySelectorAll('script')].map(s => ({ src: s.src, id: s.id, len: s.innerHTML.length, preview: s.innerHTML.slice(0, 800) })),
    }))()`);

    if (result.afterGateway?.watchTabsCount > 0) {
      result.movieWatchingData = await evalJs(`(async () => {
        const tabs = [...document.querySelectorAll('#watch li')].map(li => ({
          ariaLabel: li.getAttribute('aria-label'),
          dataIndex: li.getAttribute('data-index'),
          dataId: li.getAttribute('data-id'),
          className: li.className,
          text: li.innerText.trim(),
          html: li.innerHTML.slice(0, 500),
        }));
        const downloads = [...document.querySelectorAll('#download > li')].map(qLi => ({
          ariaLabel: qLi.getAttribute('aria-label'),
          qualityTitle: qLi.querySelector('span, em, h3, strong')?.innerText?.trim() || qLi.innerText.split('\\n')[0],
          links: [...qLi.querySelectorAll('a[href]')].map(a => ({
            href: a.href,
            text: a.innerText.trim().replace(/\\s+/g, ' '),
            html: a.outerHTML.slice(0, 400),
          })),
        }));
        const switchResults = [];
        const serverTabs = [...document.querySelectorAll('#watch li:not([aria-label="embed"]):not([aria-label="uptobox"])')]
          .filter(li => li.getAttribute('data-index') !== '00' && li.getAttribute('data-id'));
        for (const li of serverTabs) {
          const idx = li.getAttribute('data-index');
          const id = li.getAttribute('data-id');
          const tok = typeof window._getCoreToken === 'function' ? window._getCoreToken() : '';
          const u = '/wp-content/themes/Cima%20Now%20New/core.php' + (tok ? '?token=' + encodeURIComponent(tok) : '') + (tok ? '&' : '?') + 'action=switch&index=' + encodeURIComponent(idx) + '&id=' + encodeURIComponent(id);
          try {
            const r = await fetch(u, { headers: { 'X-Requested-With': 'XMLHttpRequest' } });
            const txt = await r.text();
            switchResults.push({ name: li.innerText.trim(), idx, id, status: r.status, tok, resp: txt.slice(0, 600) });
          } catch (e) {
            switchResults.push({ name: li.innerText.trim(), idx, id, err: String(e) });
          }
        }
        return { tabs, downloads, switchResults };
      })()`);
    }

    result.allCookies = (await cdp("Network.getAllCookies")).cookies;
    result.netTrace = netTrace.slice(0, 80);
    ws.close();
    return result;
  } catch (e) {
    return { available: true, error: String(e.stack || e) };
  } finally {
    try { proc.kill("SIGKILL"); } catch {}
  }
}

async function main() {
  const out = {};
  const ranked = await getRankedProxies();
  out.rankedProxies = ranked;
  const proxies = ranked.map((x) => x.proxy);

  const movieUrl = "https://cimanow.cc/%d9%81%d9%8a%d9%84%d9%85-fall-2-deadpoint-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/";
  const epUrl = "https://cimanow.cc/%d9%85%d8%b3%d9%84%d8%b3%d9%84-lupin-%d8%ac4-%d8%ad1-%d9%85%d8%aa%d8%b1%d8%ac%d9%85%d8%a9/";
  const shineUrl =
    "https://rm.freex2line.online/loadon/?link=aHR0cHM6Ly9jaW1hbm93LmNjLyVkOSU4MSVkOSU4YSVkOSU4NCVkOSU4NS1mYWxsLTItZGVhZHBvaW50LTIwMjYtJWQ5JTg1JWQ4JWFhJWQ4JWIxJWQ4JWFjJWQ5JTg1L3dhdGNoaW5nLw==";

  out.curlMatrix = await probeWatchingMatrixWithCurl(proxies, movieUrl, shineUrl);
  out.xvfbGateway = await runFastXvfbChrome(ranked, movieUrl, epUrl, shineUrl);

  fs.writeFileSync("cimanow_recon.json", JSON.stringify(out, null, 2));
  console.log("Wrote v6 recon report, bytes:", fs.statSync("cimanow_recon.json").size);
}

main();
