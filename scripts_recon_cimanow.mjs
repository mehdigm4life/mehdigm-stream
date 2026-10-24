import fs from "node:fs";
import vm from "node:vm";
import { execFile } from "node:child_process";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

const UA =
  "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

const KNOWN_GOOD_PROXIES = [
  "socks5h://45.74.31.46:8506",
  "socks5h://45.74.31.46:8287",
  "socks5h://91.107.179.68:10809",
  "socks5h://184.178.172.5:15303",
  "socks5h://184.178.172.25:15291",
  "socks5h://45.74.31.46:8478",
];

const PROXY_FEEDS = [
  ["socks5h", "https://raw.githubusercontent.com/vakhov/fresh-proxy-list/master/socks5.txt"],
  ["http", "https://raw.githubusercontent.com/vakhov/fresh-proxy-list/master/https.txt"],
  ["socks5h", "https://raw.githubusercontent.com/monosans/proxy-list/main/proxies/socks5.txt"],
];

function isBlockPage(html) {
  return !html || html.includes("ستوب! المخرج عايز كدة") || html.includes("Just a moment...") || html.length < 400;
}

async function curlGet(url, { proxy = null, referer = null, extraHeaders = [], cookieJarFile = null, timeoutSec = 12, follow = true, includeHeaders = false } = {}) {
  const args = [
    "-sS",
    "-k",
    "--compressed",
    "--max-time",
    String(timeoutSec),
    "--connect-timeout",
    "5",
    "-A",
    UA,
    "-H",
    "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "-H",
    "Accept-Language: ar,en-US;q=0.9,en;q=0.8",
  ];
  if (includeHeaders) args.push("-D", "/tmp/curl_resp_headers.txt");
  args.push("-w", "\n__CURL_META__:%{http_code}|%{url_effective}|%{redirect_url}");
  if (follow) args.push("-L", "--max-redirs", "5");
  if (proxy) args.push("-x", proxy);
  if (referer) args.push("-e", referer);
  if (cookieJarFile) args.push("-b", cookieJarFile, "-c", cookieJarFile);
  for (const h of extraHeaders) args.push("-H", h);
  args.push(url);

  const { stdout } = await execFileAsync("curl", args, { maxBuffer: 25 * 1024 * 1024 });
  const markerIdx = stdout.lastIndexOf("\n__CURL_META__:");
  const text = markerIdx >= 0 ? stdout.slice(0, markerIdx) : stdout;
  const [codeStr, finalUrl, redirectUrl] =
    markerIdx >= 0 ? stdout.slice(markerIdx + "\n__CURL_META__:".length).trim().split("|") : ["0", url, ""];
  const respHeaders = includeHeaders && fs.existsSync("/tmp/curl_resp_headers.txt")
    ? fs.readFileSync("/tmp/curl_resp_headers.txt", "utf8")
    : "";
  return { status: Number(codeStr) || 0, finalUrl, redirectUrl, respHeaders, text };
}

async function getWorkingProxies() {
  const working = [];
  await Promise.all(
    KNOWN_GOOD_PROXIES.map(async (p) => {
      try {
        const r = await curlGet("https://cimanow.cc/home/", { proxy: p, timeoutSec: 7 });
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
          const r = await curlGet("https://cimanow.cc/home/", { proxy: p, timeoutSec: 7 });
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
      const r = await curlGet(url, { ...opts, proxy });
      if (r.status > 0 && !r.text.includes("ستوب! المخرج عايز كدة")) {
        return { ...r, usedProxy: proxy };
      }
    } catch {}
  }
  throw new Error(`All proxies failed for ${url}`);
}

async function main() {
  const out = {};
  const proxies = await getWorkingProxies();
  out.proxies = proxies;
  const stickyProxy = [proxies[0]]; // Keep sticky IP for session/token!
  const cookieFile = "/tmp/cn_sticky_cookies.txt";
  if (fs.existsSync(cookieFile)) fs.unlinkSync(cookieFile);

  // 1. Inspect exact card HTML on `/home/`, `/category/.../page/2/`, and `/?s=Spider`
  const home = await smartGet("https://cimanow.cc/home/", proxies, { cookieJarFile: cookieFile });
  const homeSections = [...home.text.matchAll(/<section\b[^>]*>[\s\S]*?<\/section>/gi)].map((m) => m[0]);
  out.homeSection0Raw = homeSections[0]?.slice(0, 4500);
  out.homeSection5Raw = homeSections[5]?.slice(0, 4500);
  out.homeSection10Raw = homeSections[10]?.slice(0, 4500);

  const cat2 = await smartGet(
    "https://cimanow.cc/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a%d8%a9/page/2/",
    proxies,
    { cookieJarFile: cookieFile }
  );
  const cat2Articles = [...cat2.text.matchAll(/<article\b[^>]*>[\s\S]*?<\/article>/gi)].map((m) => m[0]);
  out.cat2ArticleCount = cat2Articles.length;
  out.cat2ArticlesSample = cat2Articles.slice(0, 4).map((a) => a.slice(0, 2000));

  const srch = await smartGet("https://cimanow.cc/?s=Lupin", proxies, { cookieJarFile: cookieFile });
  const srchArticles = [...srch.text.matchAll(/<article\b[^>]*>[\s\S]*?<\/article>/gi)].map((m) => m[0]);
  out.srchArticleCount = srchArticles.length;
  out.srchArticlesSample = srchArticles.slice(0, 5).map((a) => a.slice(0, 2000));

  // 2. Inspect ALL `<script>` tags on `movieUrl` (head + body + footer) to reverse `_getCoreToken()` & `//wtc btns`!
  const movieUrl = "https://cimanow.cc/%d9%81%d9%8a%d9%84%d9%85-fall-2-deadpoint-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/";
  const mv = await smartGet(movieUrl, stickyProxy, {
    referer: "https://cimanow.cc/home/",
    cookieJarFile: cookieFile,
    includeHeaders: true,
  });
  out.movieRespHeaders = mv.respHeaders;
  out.cookiesAfterMovie = fs.existsSync(cookieFile) ? fs.readFileSync(cookieFile, "utf8") : "";

  const allMovieScripts = [...mv.text.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)].map((m, i) => ({
    idx: i,
    attrs: m[1].trim(),
    len: m[2].length,
    code: m[2],
  }));
  out.movieScriptsMeta = allMovieScripts.map((s) => ({
    idx: s.idx,
    attrs: s.attrs,
    len: s.len,
    preview: s.code.slice(0, 300),
  }));
  // Save full scripts that are NOT Cloudflare web-vitals beacon (`data-cf-beacon`)
  out.movieRelevantScripts = allMovieScripts.filter(
    (s) => !s.attrs.includes("data-cf-beacon") && !s.code.includes("__CF$cv$params")
  );

  // Also check `<head>` of movieUrl for any external `.js` `<script src="...">`!
  const headHtml = mv.text.slice(0, mv.text.toLowerCase().indexOf("<body"));
  out.movieHeadHtml = headHtml;

  // Fetch any external CimaNow `.js` referenced in movie page!
  const extJsUrls = [...mv.text.matchAll(/<script[^>]+src=["']([^"']*cimanow[^"']*)["']/gi)].map((m) => m[1]);
  out.externalCimaJs = [];
  for (const jsUrl of extJsUrls) {
    try {
      const jr = await smartGet(jsUrl, stickyProxy, { referer: movieUrl, cookieJarFile: cookieFile });
      out.externalCimaJs.push({ url: jsUrl, len: jr.text.length, code: jr.text.slice(0, 20000) });
    } catch (e) {
      out.externalCimaJs.push({ url: jsUrl, error: String(e.message || e) });
    }
  }

  // 3. Execute the movie's `<head>` scripts + `x19WCM90B19F` (`//wtc btns`) in a simulated jQuery + DOM sandbox to see EXACTLY what it does to `.btns a.shine` and `document.cookie` and `fetch`/`XMLHttpRequest`!
  const capturedActions = [];
  const shineAnchor = {
    href: "https://rm.freex2line.online/loadon/?link=aHR0cHM6Ly9jaW1hbm93LmNjLyVkOSU4MSVkOSU4YSVkOSU4NCVkOSU4NS1mYWxsLTItZGVhZHBvaW50LTIwMjYtJWQ5JTg1JWQ4JWFhJWQ4JWIxJWQ4JWFjJWQ5JTg1L3dhdGNoaW5nLw==",
    attrs: {
      href: "https://rm.freex2line.online/loadon/?link=aHR0cHM6Ly9jaW1hbm93LmNjLyVkOSU4MSVkOSU4YSVkOSU4NCVkOSU4NS1mYWxsLTItZGVhZHBvaW50LTIwMjYtJWQ5JTg1JWQ4JWFhJWQ4JWIxJWQ4JWFjJWQ5JTg1L3dhdGNoaW5nLw==",
      class: "shine",
    },
    clickHandlers: [],
  };

  function makeJqWrapper(sel) {
    const obj = {
      length: 1,
      0: shineAnchor,
      ready(fn) {
        capturedActions.push({ type: "jq.ready" });
        if (typeof fn === "function") fn();
        return obj;
      },
      on(ev, arg2, arg3) {
        const fn = typeof arg2 === "function" ? arg2 : arg3;
        capturedActions.push({ type: "jq.on", sel: String(sel), ev });
        if (ev === "click" && typeof fn === "function") {
          shineAnchor.clickHandlers.push({ sel: String(sel), fn });
        }
        return obj;
      },
      click(fn) {
        if (typeof fn === "function") shineAnchor.clickHandlers.push({ sel: String(sel), fn });
        return obj;
      },
      addClass() { return obj; },
      removeClass() { return obj; },
      remove() { capturedActions.push({ type: "jq.remove", sel: String(sel) }); return obj; },
      hide() { return obj; },
      show() { return obj; },
      each(fn) {
        if (typeof fn === "function") fn.call(shineAnchor, 0, shineAnchor);
        return obj;
      },
      find() { return obj; },
      text() { return "شاهد وحمل الان"; },
      data(k) { return shineAnchor.attrs[`data-${k}`] || ""; },
      attr(k, v) {
        if (v !== undefined) {
          capturedActions.push({ type: "jq.setAttr", sel: String(sel), k, v: String(v) });
          shineAnchor.attrs[k] = String(v);
          if (k === "href") shineAnchor.href = String(v);
          return obj;
        }
        return shineAnchor.attrs[k] || "";
      },
      prop(k, v) { return obj.attr(k, v); },
    };
    return obj;
  }

  const jq = (sel) => makeJqWrapper(sel);
  jq.ajax = (cfg) => {
    capturedActions.push({ type: "jq.ajax", cfg });
    return { done() { return this; }, fail() { return this; }, always() { return this; } };
  };
  jq.post = (url, data) => {
    capturedActions.push({ type: "jq.post", url, data });
  };
  jq.get = (url, data) => {
    capturedActions.push({ type: "jq.get", url, data });
  };

  const vmSandbox = {
    $: jq,
    jQuery: jq,
    atob: (b64) => Buffer.from(b64, "base64").toString("binary"),
    btoa: (bin) => Buffer.from(bin, "binary").toString("base64"),
    console: { log() {}, warn() {}, error() {} },
    setTimeout: (fn, ms) => {
      capturedActions.push({ type: "setTimeout", ms });
      return 1;
    },
    clearTimeout() {},
    setInterval: () => 1,
    clearInterval() {},
    fetch: (url, opts) => {
      capturedActions.push({ type: "fetch", url, opts });
      return Promise.resolve({ ok: true, json: async () => ({}), text: async () => "" });
    },
    XMLHttpRequest: function () {
      this.open = (m, u) => capturedActions.push({ type: "xhr.open", m, u });
      this.setRequestHeader = (k, v) => capturedActions.push({ type: "xhr.hdr", k, v });
      this.send = (b) => capturedActions.push({ type: "xhr.send", b });
    },
    navigator: { userAgent: UA },
    screen: { orientation: { lock() {} } },
    location: {
      href: movieUrl,
      origin: "https://cimanow.cc",
      protocol: "https:",
      host: "cimanow.cc",
      hostname: "cimanow.cc",
      pathname: new URL(movieUrl).pathname,
      assign(u) { capturedActions.push({ type: "location.assign", u }); },
      replace(u) { capturedActions.push({ type: "location.replace", u }); },
    },
    document: {
      cookie: "",
      domain: "cimanow.cc",
      readyState: "complete",
      referrer: "https://cimanow.cc/home/",
      documentElement: { appendChild() {} },
      body: { appendChild() {} },
      addEventListener(ev, fn) {
        capturedActions.push({ type: "doc.addEventListener", ev });
      },
      createElement(tag) {
        const el = {
          tag,
          dataset: {},
          style: {},
          attrs: {},
          setAttribute(k, v) { this.attrs[k] = v; capturedActions.push({ type: "el.setAttr", tag, k, v }); },
          appendChild(ch) { capturedActions.push({ type: "el.appendChild", tag, ch }); },
          submit() { capturedActions.push({ type: "form.submit", action: this.action, method: this.method, attrs: this.attrs, el: this }); },
        };
        capturedActions.push({ type: "doc.createElement", tag, el });
        return el;
      },
      querySelector: () => shineAnchor,
      querySelectorAll: () => [shineAnchor],
    },
  };
  vmSandbox.window = vmSandbox;
  vmSandbox.self = vmSandbox;
  vmSandbox.globalThis = vmSandbox;

  vm.createContext(vmSandbox);
  for (const s of out.movieRelevantScripts) {
    if (!s.code.trim()) continue;
    try {
      vm.runInContext(s.code, vmSandbox, { timeout: 3000 });
    } catch (e) {
      capturedActions.push({ type: "scriptError", idx: s.idx, err: String(e.message || e) });
    }
  }

  out.tokenFnExists = typeof vmSandbox._getCoreToken;
  if (typeof vmSandbox._getCoreToken === "function") {
    try {
      out.computedCoreToken = vmSandbox._getCoreToken();
    } catch (e) {
      out.computedCoreTokenError = String(e.message || e);
    }
  }
  out.secureCoreTokenVar = vmSandbox.secureCoreToken;
  out.vmCookiesAfterInit = vmSandbox.document.cookie;

  // Trigger click handlers on `.shine`!
  for (const h of shineAnchor.clickHandlers) {
    try {
      h.fn.call(shineAnchor, {
        preventDefault() { capturedActions.push({ type: "event.preventDefault", sel: h.sel }); },
        stopPropagation() {},
        currentTarget: shineAnchor,
        target: shineAnchor,
      });
    } catch (e) {
      capturedActions.push({ type: "clickHandlerError", sel: h.sel, err: String(e.message || e) });
    }
  }

  out.vmCookiesAfterClick = vmSandbox.document.cookie;
  out.finalShineHref = shineAnchor.href;
  out.capturedActions = capturedActions;

  fs.writeFileSync("cimanow_recon.json", JSON.stringify(out, null, 2));
  console.log("Wrote token & DOM recon report");
}

main();
