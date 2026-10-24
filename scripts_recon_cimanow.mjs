import fs from "node:fs";
import vm from "node:vm";
import crypto from "node:crypto";
import { execFile, spawn } from "node:child_process";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

const UA =
  "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

const KNOWN_GOOD_PROXIES = [
  "socks5h://184.178.172.25:15291",
  "socks5h://184.178.172.5:15303",
  "socks5h://45.74.31.46:8506",
  "socks5h://45.74.31.46:8287",
  "socks5h://91.107.179.68:10809",
  "socks5h://45.74.31.46:8478",
];

const PROXY_FEEDS = [
  ["socks5h", "https://raw.githubusercontent.com/vakhov/fresh-proxy-list/master/socks5.txt"],
  ["http", "https://raw.githubusercontent.com/vakhov/fresh-proxy-list/master/https.txt"],
  ["socks5h", "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt"],
];

function isBlockPage(html) {
  return (
    !html ||
    html.includes("ستوب! المخرج عايز كدة") ||
    html.includes("مشهد محذوف") ||
    html.includes("Just a moment...") ||
    html.length < 400
  );
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
    timeoutSec = 12,
    follow = true,
    includeHeaders = false,
  } = {}
) {
  const hdrFile = `/tmp/curl_hdr_${Math.random().toString(36).slice(2)}.txt`;
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
    UA,
    "-H",
    "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "-H",
    "Accept-Language: ar,en-US;q=0.9,en;q=0.8",
  ];
  if (postData !== null) args.push("--data", postData);
  if (includeHeaders) args.push("-D", hdrFile);
  args.push("-w", "\n__CURL_META__:%{http_code}|%{url_effective}|%{redirect_url}");
  if (follow) args.push("-L", "--max-redirs", "5");
  if (proxy) args.push("-x", proxy);
  if (referer) args.push("-e", referer);
  if (cookieJarFile) args.push("-b", cookieJarFile, "-c", cookieJarFile);
  for (const h of extraHeaders) args.push("-H", h);
  args.push(url);

  try {
    const { stdout } = await execFileAsync("curl", args, { maxBuffer: 25 * 1024 * 1024 });
    const markerIdx = stdout.lastIndexOf("\n__CURL_META__:");
    const text = markerIdx >= 0 ? stdout.slice(0, markerIdx) : stdout;
    const [codeStr, finalUrl, redirectUrl] =
      markerIdx >= 0 ? stdout.slice(markerIdx + "\n__CURL_META__:".length).trim().split("|") : ["0", url, ""];
    const respHeaders = includeHeaders && fs.existsSync(hdrFile) ? fs.readFileSync(hdrFile, "utf8") : "";
    return { status: Number(codeStr) || 0, finalUrl, redirectUrl, respHeaders, text };
  } finally {
    if (fs.existsSync(hdrFile)) try { fs.unlinkSync(hdrFile); } catch {}
  }
}

async function getWorkingProxies() {
  const working = [];
  await Promise.all(
    KNOWN_GOOD_PROXIES.map(async (p) => {
      try {
        const r = await curlReq("https://cimanow.cc/home/", { proxy: p, timeoutSec: 7 });
        if (r.status === 200 && !isBlockPage(r.text)) working.push(p);
      } catch {}
    })
  );
  if (working.length >= 2) return working;

  const candidates = [];
  await Promise.all(
    PROXY_FEEDS.map(async ([scheme, feedUrl]) => {
      try {
        const res = await fetch(feedUrl, { signal: AbortSignal.timeout(6000) });
        const txt = await res.text();
        for (const raw of txt.split(/\r?\n/).slice(0, 120)) {
          const line = raw.trim().replace(/^(https?|socks5h?):\/\//i, "");
          if (/^\d+\.\d+\.\d+\.\d+:\d+$/.test(line)) candidates.push(`${scheme}://${line}`);
        }
      } catch {}
    })
  );
  let idx = 0;
  await Promise.all(
    Array.from({ length: 30 }, async () => {
      while (idx < candidates.length && working.length < 4) {
        const p = candidates[idx++];
        try {
          const r = await curlReq("https://cimanow.cc/home/", { proxy: p, timeoutSec: 7 });
          if (r.status === 200 && !isBlockPage(r.text)) working.push(p);
        } catch {}
      }
    })
  );
  return working;
}

async function smartGet(url, proxies, opts = {}) {
  for (const proxy of proxies) {
    try {
      const r = await curlReq(url, { ...opts, proxy });
      if (r.status > 0 && !r.text.includes("ستوب! المخرج عايز كدة") && !r.text.includes("مشهد محذوف")) {
        return { ...r, usedProxy: proxy };
      }
    } catch {}
  }
  throw new Error(`All proxies failed for ${url}`);
}

function deobfuscate0CYA(jsCode) {
  const result = {};
  try {
    // Find the string table function `function _0x58a6(){...}` and decoder `function _0x25fd(...){...}` and rotation IIFE
    const fn58a6Match = jsCode.match(/function (_0x[0-9a-f]+)\(\)\{var _0x[0-9a-f]+=\[[\s\S]*?\];\1=function\(\)\{return _0x[0-9a-f]+;\};return \1\(\);\}/);
    const fn25fdIdx = jsCode.indexOf("function _0x25fd(");
    let fn25fd = "";
    if (fn25fdIdx >= 0) {
      let d = 0;
      for (let i = fn25fdIdx; i < jsCode.length; i++) {
        if (jsCode[i] === "{") d++;
        else if (jsCode[i] === "}") {
          d--;
          if (d === 0) {
            fn25fd = jsCode.slice(fn25fdIdx, i + 1);
            break;
          }
        }
      }
    }
    // Rotation IIFE at the very start `(function(_0x1d8d7c,_0x22e95e){...}(_0x58a6,...))`
    let rotIife = "";
    if (jsCode.startsWith("(function(")) {
      // Find matching `}(_0x58a6,` ... `)`
      const callIdx = jsCode.indexOf("(_0x58a6,");
      if (callIdx > 0) {
        let p = 0;
        for (let i = callIdx; i < jsCode.length; i++) {
          if (jsCode[i] === "(") p++;
          else if (jsCode[i] === ")") {
            p--;
            if (p === 0) {
              rotIife = jsCode.slice(0, i + 1) + ");";
              break;
            }
          }
        }
      }
    }
    result.foundParts = {
      hasTable: !!fn58a6Match,
      fn25fdLen: fn25fd.length,
      rotIifeLen: rotIife.length,
    };

    if (fn58a6Match && fn25fd && rotIife) {
      const ctx = {
        window: {},
        document: { cookie: "", domain: "cimanow.cc" },
        navigator: { userAgent: UA },
        location: { hostname: "cimanow.cc", host: "cimanow.cc", origin: "https://cimanow.cc", href: "https://cimanow.cc/" },
        atob: (b) => Buffer.from(b, "base64").toString("binary"),
        btoa: (b) => Buffer.from(b, "binary").toString("base64"),
      };
      ctx.self = ctx.window;
      ctx.globalThis = ctx;
      vm.createContext(ctx);
      vm.runInContext(`${fn58a6Match[0]}\n${fn25fd}\n${rotIife}`, ctx, { timeout: 8000 });
      const tableName = fn58a6Match[1];
      const allStrings = vm.runInContext(`${tableName}()`, ctx);
      result.tableLen = allStrings.length;

      // Collect all wrapper functions `function _0x...(...){return _0x...(...);}`
      const wrapperRe = /function (_0x[0-9a-f]+)\(([^)]*)\)\{return (_0x[0-9a-f]+)\(([^)]*)\);\}/g;
      let wrappers = "";
      let m;
      while ((m = wrapperRe.exec(jsCode)) !== null) {
        wrappers += m[0] + "\n";
      }
      for (let pass = 0; pass < 5; pass++) {
        try { vm.runInContext(wrappers, ctx, { timeout: 3000 }); } catch {}
      }

      // Remove table & wrapper definitions to shrink code, then resolve string calls!
      let cleaned = jsCode
        .replace(fn58a6Match[0], "")
        .replace(fn25fd, "")
        .replace(/function _0x[0-9a-f]+\([^)]*\)\{return _0x[0-9a-f]+\([^)]*\);\}/g, "");

      for (let pass = 0; pass < 3; pass++) {
        cleaned = cleaned.replace(/(_0x[0-9a-f]+)\(([-0-9a-fx,\s]+)\)/g, (full, fn, args) => {
          try {
            const v = vm.runInContext(`${fn}(${args})`, ctx);
            if (typeof v === "string") return JSON.stringify(v);
          } catch {}
          return full;
        });
        for (let k = 0; k < 6; k++) {
          cleaned = cleaned.replace(/"([^"\\]*)"\s*\+\s*"([^"\\]*)"/g, (_, a, b) => JSON.stringify(a + b));
          cleaned = cleaned.replace(/"([^"\\]*)"\s*\+\s*'([^'\\]*)'/g, (_, a, b) => JSON.stringify(a + b));
        }
      }

      // Extract all unique string literals >= 4 chars (excluding 5-char obfuscator keys)
      const uniqueLits = [...new Set([...cleaned.matchAll(/"([^"\\]{4,})"/g)].map((x) => x[1]))].filter(
        (s) => !/^[A-Za-z]{5}$/.test(s)
      );
      result.interestingLiterals = uniqueLits.filter((s) =>
        /token|watch|cookie|core|shine|loadon|freex2line|http|\/wp-|hash|hmac|sha|md5|base64|atob|btoa|crypto|subtle|fetch|ajax|open|href|location|time|date|nonce|secret|key|sign|cimanow|cnvids|embed|download|userAgent|canvas|webdriver/i.test(
          s
        )
      );
      result.allReadableLiterals = uniqueLits.slice(0, 500);

      // Extract snippets around `_getCoreToken`, `watching`, `cookie`, `loadon`, `core.php`, `shine`
      result.snippets = {};
      for (const kw of ["_getCoreToken", "watching", "loadon", "freex2line", "cookie", "core.php", "shine", "subtle", "charCodeAt"]) {
        const hits = [];
        let pos = 0;
        while (hits.length < 6) {
          const idx = cleaned.indexOf(kw, pos);
          if (idx < 0) break;
          hits.push(cleaned.slice(Math.max(0, idx - 600), Math.min(cleaned.length, idx + 1400)));
          pos = idx + kw.length + 200;
        }
        if (hits.length > 0) result.snippets[kw] = hits;
      }
      result.cleanedTotalLen = cleaned.length;
      // Save full cleaned JS locally during run if we want to inspect more
      fs.writeFileSync("/tmp/deob_0CYA.js", cleaned);
    }
  } catch (e) {
    result.error = String(e.stack || e);
  }
  return result;
}

async function runChromeRecon(proxyUrl, urlsToTest) {
  const chromeBin = ["/usr/bin/google-chrome", "/usr/bin/google-chrome-stable", "/usr/bin/chromium-browser", "/usr/bin/chromium"].find(
    (p) => fs.existsSync(p)
  );
  if (!chromeBin) return { available: false };

  const chromeProxy = proxyUrl.replace(/^socks5h:\/\//, "socks5://");
  const port = 9229;
  const userDataDir = `/tmp/chrome_cn_${Date.now()}`;
  const proc = spawn(
    chromeBin,
    [
      "--headless=new",
      "--no-sandbox",
      "--disable-setuid-sandbox",
      "--disable-dev-shm-usage",
      "--disable-gpu",
      "--ignore-certificate-errors",
      `--user-agent=${UA}`,
      `--proxy-server=${chromeProxy}`,
      `--remote-debugging-port=${port}`,
      `--user-data-dir=${userDataDir}`,
      "about:blank",
    ],
    { stdio: "ignore" }
  );

  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
  try {
    let targets = null;
    for (let i = 0; i < 25; i++) {
      await sleep(250);
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
    const netRequests = [];
    ws.addEventListener("message", (ev) => {
      const msg = JSON.parse(String(ev.data));
      if (msg.id && pending.has(msg.id)) {
        const { resolve, reject } = pending.get(msg.id);
        pending.delete(msg.id);
        if (msg.error) reject(new Error(msg.error.message));
        else resolve(msg.result);
      } else if (msg.method === "Network.requestWillBeSent") {
        const u = msg.params?.request?.url || "";
        if (u.includes("cimanow") || u.includes("freex2line") || u.includes("cnvids") || u.includes("worldcdn")) {
          netRequests.push({
            url: u,
            method: msg.params.request.method,
            headers: msg.params.request.headers,
            postData: msg.params.request.postData,
          });
        }
      }
    });

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
        }, 15000);
      });

    await cdp("Page.enable");
    await cdp("Network.enable");
    await cdp("Runtime.enable");

    const evalJs = async (expression) => {
      const r = await cdp("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true });
      return r?.result?.value;
    };

    const itemReports = [];
    for (const itemUrl of urlsToTest) {
      const rep = { itemUrl };
      await cdp("Page.navigate", { url: itemUrl });
      await sleep(4500);

      rep.pageInfo = await evalJs(`(() => ({
        url: location.href,
        title: document.title,
        cookie: document.cookie,
        hasTokenFn: typeof window._getCoreToken,
        tokenSample1: typeof window._getCoreToken === 'function' ? window._getCoreToken() : null,
        tokenFnSrc: typeof window._getCoreToken === 'function' ? window._getCoreToken.toString().slice(0, 600) : null,
        shineHref: document.querySelector('.btns a.shine, a.shine')?.href || null,
        shineOuter: document.querySelector('.btns a.shine, a.shine')?.outerHTML || null,
      }))()`);

      await sleep(1100);
      rep.tokenSample2After1s = await evalJs(
        `typeof window._getCoreToken === 'function' ? window._getCoreToken() : null`
      );
      const allCookiesBefore = await cdp("Network.getAllCookies");
      rep.cookiesOnMovie = allCookiesBefore.cookies;

      // Navigate to watching URL directly first or click .shine? Let's test navigating to `${itemUrl}watching/` IN THIS REAL CHROME SESSION!
      const watchTargetUrl = itemUrl.replace(/\/?$/, "/watching/");
      await cdp("Page.navigate", { url: watchTargetUrl, referrer: itemUrl });
      await sleep(4500);

      rep.directWatchNav = await evalJs(`(() => ({
        url: location.href,
        title: document.title,
        cookie: document.cookie,
        watchTabsCount: document.querySelectorAll('#watch li').length,
      }))()`);

      // If direct nav redirected to home, go back to itemUrl and inspect/click `.shine`!
      if (!rep.directWatchNav?.watchTabsCount) {
        await cdp("Page.navigate", { url: itemUrl });
        await sleep(3500);
        const shineHref = await evalJs(`document.querySelector('.btns a.shine, a.shine')?.href || ''`);
        rep.shineHrefOnRetry = shineHref;
        if (shineHref) {
          await cdp("Page.navigate", { url: shineHref, referrer: itemUrl });
          await sleep(6000);
          rep.afterShineNav = await evalJs(`(() => ({
            url: location.href,
            title: document.title,
            bodySnippet: document.body?.innerText?.slice(0, 600) || '',
            htmlSnippet: document.documentElement?.outerHTML?.slice(0, 2500) || '',
            links: [...document.querySelectorAll('a, button, form, iframe, script[src]')].slice(0, 20).map(e => e.outerHTML.slice(0, 350)),
          }))()`);
          // If still on loadon gateway and there's a button/form/link to continue, trigger it!
          const stillNotWatching = await evalJs(`document.querySelectorAll('#watch li').length === 0`);
          if (stillNotWatching) {
            await evalJs(`(() => {
              const btn = document.querySelector('form button, button, a[href*="cimanow"], a.btn, #btn, .continue');
              if (btn) btn.click();
              const frm = document.querySelector('form');
              if (frm) frm.submit();
            })()`);
            await sleep(5000);
            rep.afterGatewayClick = await evalJs(`(() => ({
              url: location.href,
              title: document.title,
              watchTabsCount: document.querySelectorAll('#watch li').length,
            }))()`);
          }
        }
      }

      // If we are on `/watching/` (either way), dump ALL watch servers + switch responses + download links!
      const onWatching = await evalJs(`document.querySelectorAll('#watch li').length > 0`);
      if (onWatching) {
        rep.watchingDom = await evalJs(`(async () => {
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
              html: a.outerHTML.slice(0, 350),
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
              switchResults.push({ name: li.innerText.trim(), idx, id, status: r.status, resp: txt.slice(0, 600) });
            } catch (e) {
              switchResults.push({ name: li.innerText.trim(), idx, id, err: String(e) });
            }
          }
          return { tabs, downloads, switchResults };
        })()`);
      }

      const allCookiesAfter = await cdp("Network.getAllCookies");
      rep.cookiesAfter = allCookiesAfter.cookies;
      itemReports.push(rep);
    }

    ws.close();
    return { available: true, itemReports, netRequests: netRequests.slice(0, 80) };
  } catch (e) {
    return { available: true, error: String(e.stack || e) };
  } finally {
    try { proc.kill("SIGKILL"); } catch {}
  }
}

async function main() {
  const out = {};
  const proxies = await getWorkingProxies();
  out.proxies = proxies;

  // 1. Inspect grid `<article>` cards (indices 5..9, skipping hero slider 0..4!) on category, latest, most-watched, and search!
  const listUrls = {
    catPage2: "https://cimanow.cc/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a%d8%a9/page/2/",
    latestPage2: "https://cimanow.cc/%d8%a7%d9%84%d8%a7%d8%ad%d8%af%d8%ab/page/2/",
    mostWatchedPage1: "https://cimanow.cc/%d8%a7%d9%84%d8%a7%d9%83%d8%ab%d8%b1-%d9%85%d8%b4%d8%a7%d9%87%d8%af%d8%a9/",
    searchLupin: "https://cimanow.cc/?s=Lupin",
    searchSpiderPage2: "https://cimanow.cc/page/2/?s=Spider",
  };
  out.listGridSamples = {};
  for (const [k, u] of Object.entries(listUrls)) {
    try {
      const r = await smartGet(u, proxies);
      const articles = [...r.text.matchAll(/<article\b[^>]*>[\s\S]*?<\/article>/gi)].map((m) => m[0]);
      const sections = [...r.text.matchAll(/<section\b[^>]*aria-label=["']posts["'][^>]*>[\s\S]*?<\/section>/gi)].map((m) => m[0]);
      const pagination = r.text.match(/<ul[^>]*aria-label=["']pagination["'][^>]*>[\s\S]*?<\/ul>/i)?.[0] || "";
      out.listGridSamples[k] = {
        status: r.status,
        finalUrl: r.finalUrl,
        totalArticles: articles.length,
        postsSectionCount: sections.length,
        article0Preview: articles[0]?.slice(0, 600),
        article5Grid: articles[5]?.slice(0, 1400),
        article6Grid: articles[6]?.slice(0, 1400),
        paginationHtml: pagination.slice(0, 1200),
      };
    } catch (e) {
      out.listGridSamples[k] = { error: String(e.message || e) };
    }
  }

  // 2. Fetch full `0CYA6X1KhKIS.js?v=3.1` and deobfuscate it!
  const jsUrl = "https://cimanow.cc/wp-content/themes/Cima%20Now%20New/Assets/js/0CYA6X1KhKIS.js?v=3.1";
  const jsResp = await smartGet(jsUrl, proxies, { referer: "https://cimanow.cc/home/" });
  out.js0CYALen = jsResp.text.length;
  out.deob0CYA = deobfuscate0CYA(jsResp.text);

  // 3. Run real Headless Chrome through our working SOCKS5 proxy on a Movie + an Episode!
  const movieUrl = "https://cimanow.cc/%d9%81%d9%8a%d9%84%d9%85-fall-2-deadpoint-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/";
  const epUrl = "https://cimanow.cc/%d9%85%d8%b3%d9%84%d8%b3%d9%84-lupin-%d8%ac4-%d8%ad1-%d9%85%d8%aa%d8%b1%d8%ac%d9%85%d8%a9/";
  out.chromeRecon = await runChromeRecon(proxies[0], [movieUrl, epUrl]);

  fs.writeFileSync("cimanow_recon.json", JSON.stringify(out, null, 2));
  console.log("Wrote v4 recon report, bytes:", fs.statSync("cimanow_recon.json").size);
}

main();
