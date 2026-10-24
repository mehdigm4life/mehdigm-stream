import fs from "node:fs";
import vm from "node:vm";
import crypto from "node:crypto";
import { execFile, spawn } from "node:child_process";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

const DESKTOP_UA =
  "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

const KNOWN_GOOD_PROXIES = [
  "socks5h://184.178.172.5:15303",
  "socks5h://184.178.172.25:15291",
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

function isBlockPage(html) {
  return (
    !html ||
    html.includes("ستوب! المخرج عايز كدة") ||
    html.includes("مشهد محذوف") ||
    html.includes("Just a moment...") ||
    html.length < 400
  );
}

async function curlReq(url, { proxy = null, referer = null, timeoutSec = 12, follow = true } = {}) {
  const args = [
    "-sS",
    "-k",
    "--compressed",
    "--max-time",
    String(timeoutSec),
    "--connect-timeout",
    "5",
    "-A",
    DESKTOP_UA,
    "-H",
    "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "-H",
    "Accept-Language: ar,en-US;q=0.9,en;q=0.8",
    "-w",
    "\n__CURL_META__:%{http_code}|%{url_effective}",
  ];
  if (follow) args.push("-L", "--max-redirs", "5");
  if (proxy) args.push("-x", proxy);
  if (referer) args.push("-e", referer);
  args.push(url);

  const { stdout } = await execFileAsync("curl", args, { maxBuffer: 25 * 1024 * 1024 });
  const markerIdx = stdout.lastIndexOf("\n__CURL_META__:");
  const text = markerIdx >= 0 ? stdout.slice(0, markerIdx) : stdout;
  const [codeStr, finalUrl] =
    markerIdx >= 0 ? stdout.slice(markerIdx + "\n__CURL_META__:".length).trim().split("|") : ["0", url];
  return { status: Number(codeStr) || 0, finalUrl, text };
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

function analyzeAndRun0CYAInVm(jsCode, movieUrl) {
  const report = {
    envReads: [],
    envWrites: [],
    cookiesSet: [],
    domEvents: [],
    createdElements: [],
    fetchedUrls: [],
  };

  // Capture strings built via String.fromCharCode during JScrambler's inner string-table init!
  const charBuf = [];
  const origFromCharCode = String.fromCharCode;

  const seenReads = new Set();
  const logRead = (scope, prop) => {
    const key = `${scope}.${String(prop)}`;
    if (!seenReads.has(key) && report.envReads.length < 250) {
      seenReads.add(key);
      report.envReads.push(key);
    }
  };

  let cookieStore = "";
  const localStore = new Map();
  const sessionStore = new Map();

  const makeStorage = (name, map) => ({
    getItem(k) {
      logRead(name, `getItem:${k}`);
      return map.has(k) ? map.get(k) : null;
    },
    setItem(k, v) {
      report.envWrites.push({ scope: name, k, v: String(v).slice(0, 300) });
      map.set(k, String(v));
    },
    removeItem(k) {
      map.delete(k);
    },
  });

  const shineAnchor = {
    tagName: "A",
    className: "shine",
    rel: "nofollow",
    href: "https://rm.freex2line.online/loadon/?link=aHR0cHM6Ly9jaW1hbm93LmNjLyVkOSU4MSVkOSU4YSVkOSU4NCVkOSU4NS1mYWxsLTItZGVhZHBvaW50LTIwMjYtJWQ5JTg1JWQ4JWFhJWQ4JWIxJWQ4JWFjJWQ5JTg1L3dhdGNoaW5nLw==",
    getAttribute(k) {
      if (k === "href") return this.href;
      if (k === "class") return this.className;
      return null;
    },
    setAttribute(k, v) {
      report.envWrites.push({ scope: "shineAnchor.setAttribute", k, v: String(v) });
      if (k === "href") this.href = String(v);
    },
    remove() {
      report.envWrites.push({ scope: "shineAnchor.remove" });
    },
    addEventListener(ev, fn) {
      report.domEvents.push({ target: "shineAnchor", ev });
      this._clickFn = fn;
    },
    closest(sel) {
      return sel.includes("shine") || sel.includes("a") ? this : null;
    },
  };

  const docListeners = [];
  const docTarget = {
    domain: "cimanow.cc",
    URL: movieUrl,
    documentURI: movieUrl,
    referrer: "https://cimanow.cc/home/",
    readyState: "complete",
    visibilityState: "visible",
    hidden: false,
    title: "فيلم Fall 2: Deadpoint 2026 مترجم",
    characterSet: "UTF-8",
    compatMode: "CSS1Compat",
    documentElement: {
      getAttribute: () => null,
      appendChild: () => {},
      style: {},
    },
    head: { appendChild: () => {} },
    body: { appendChild: () => {}, removeChild: () => {}, style: {} },
    get cookie() {
      logRead("document", "cookie");
      return cookieStore;
    },
    set cookie(val) {
      report.cookiesSet.push(String(val));
      const pair = String(val).split(";")[0];
      cookieStore = cookieStore ? `${cookieStore}; ${pair}` : pair;
    },
    addEventListener(ev, fn) {
      report.domEvents.push({ target: "document", ev });
      docListeners.push({ ev, fn });
    },
    removeEventListener() {},
    querySelector(sel) {
      logRead("document", `querySelector:${sel}`);
      if (sel.includes("shine") || sel.includes("btns") || sel === "a") return shineAnchor;
      return null;
    },
    querySelectorAll(sel) {
      logRead("document", `querySelectorAll:${sel}`);
      if (sel.includes("shine") || sel.includes("btns") || sel === "a") return [shineAnchor];
      return [];
    },
    getElementsByTagName(tag) {
      logRead("document", `getElementsByTagName:${tag}`);
      if (tag === "a") return [shineAnchor];
      return [{ appendChild: () => {} }];
    },
    getElementById(id) {
      logRead("document", `getElementById:${id}`);
      return null;
    },
    createElement(tag) {
      logRead("document", `createElement:${tag}`);
      if (tag.toLowerCase() === "canvas") {
        return {
          width: 300,
          height: 150,
          getContext: () => ({
            fillText: () => {},
            fillRect: () => {},
            beginPath: () => {},
            arc: () => {},
            fill: () => {},
            getParameter: () => "Intel Open Source Technology Center",
            getExtension: () => ({ UNMASKED_VENDOR_WEBGL: 37445, UNMASKED_RENDERER_WEBGL: 37446 }),
          }),
          toDataURL: () => "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
        };
      }
      const el = {
        tagName: tag.toUpperCase(),
        style: {},
        dataset: {},
        setAttribute(k, v) {
          this[k] = v;
        },
        appendChild() {},
      };
      report.createdElements.push(tag);
      return el;
    },
  };

  const navTarget = {
    userAgent: DESKTOP_UA,
    appVersion: DESKTOP_UA.slice(8),
    platform: "Linux x86_64",
    vendor: "Google Inc.",
    language: "ar",
    languages: ["ar", "en-US", "en"],
    webdriver: false,
    cookieEnabled: true,
    onLine: true,
    hardwareConcurrency: 8,
    deviceMemory: 8,
    maxTouchPoints: 0,
    pdfViewerEnabled: true,
    plugins: { length: 5, 0: { name: "PDF Viewer" } },
    mimeTypes: { length: 2 },
    permissions: { query: async () => ({ state: "prompt" }) },
  };

  const locObj = new URL(movieUrl);
  const locTarget = {
    href: movieUrl,
    protocol: "https:",
    host: "cimanow.cc",
    hostname: "cimanow.cc",
    port: "",
    pathname: locObj.pathname,
    search: "",
    hash: "",
    origin: "https://cimanow.cc",
    assign(u) { report.envWrites.push({ scope: "location.assign", u }); },
    replace(u) { report.envWrites.push({ scope: "location.replace", u }); },
    reload() { report.envWrites.push({ scope: "location.reload" }); },
    toString() { return movieUrl; },
  };

  const customFromCharCode = (...codes) => {
    const s = origFromCharCode(...codes);
    if (charBuf.length < 300000) charBuf.push(s);
    return s;
  };
  const CustomString = new Proxy(String, {
    get(t, p) {
      if (p === "fromCharCode") return customFromCharCode;
      return Reflect.get(t, p);
    },
    apply(t, thisArg, args) {
      return Reflect.apply(t, thisArg, args);
    },
  });

  const winTarget = {
    String: CustomString,
    Array,
    Object,
    Number,
    Boolean,
    RegExp,
    Date,
    Math,
    JSON,
    Error,
    TypeError,
    RangeError,
    SyntaxError,
    URIError,
    Promise,
    Map,
    Set,
    WeakMap,
    WeakSet,
    Symbol,
    Proxy,
    Reflect,
    Uint8Array,
    Uint16Array,
    Uint32Array,
    Int8Array,
    Int32Array,
    ArrayBuffer,
    DataView,
    TextEncoder,
    TextDecoder,
    parseInt,
    parseFloat,
    isNaN,
    isFinite,
    encodeURIComponent,
    decodeURIComponent,
    encodeURI,
    decodeURI,
    escape,
    unescape,
    atob: (b) => Buffer.from(b, "base64").toString("binary"),
    btoa: (b) => Buffer.from(b, "binary").toString("base64"),
    crypto: crypto.webcrypto,
    performance: { now: () => 123.45, timing: { navigationStart: Date.now() - 1500 } },
    navigator: new Proxy(navTarget, {
      get(t, p) {
        logRead("navigator", p);
        return Reflect.get(t, p);
      },
    }),
    document: new Proxy(docTarget, {
      get(t, p) {
        logRead("document", p);
        const v = Reflect.get(t, p, t);
        return typeof v === "function" ? v.bind(t) : v;
      },
      set(t, p, val) {
        Reflect.set(t, p, val, t);
        return true;
      },
    }),
    location: new Proxy(locTarget, {
      get(t, p) {
        logRead("location", p);
        const v = Reflect.get(t, p);
        return typeof v === "function" ? v.bind(t) : v;
      },
      set(t, p, val) {
        report.envWrites.push({ scope: `location.${String(p)}`, val });
        t[p] = val;
        return true;
      },
    }),
    history: { pushState() {}, replaceState() {}, length: 2 },
    screen: { width: 1920, height: 1080, availWidth: 1920, availHeight: 1040, colorDepth: 24, pixelDepth: 24 },
    innerWidth: 1920,
    innerHeight: 969,
    outerWidth: 1920,
    outerHeight: 1040,
    devicePixelRatio: 1,
    chrome: { runtime: {}, app: {}, csi: () => ({}), loadTimes: () => ({}) },
    localStorage: makeStorage("localStorage", localStore),
    sessionStorage: makeStorage("sessionStorage", sessionStore),
    console: { log() {}, warn() {}, error() {}, info() {}, debug() {}, table() {}, trace() {} },
    setTimeout(fn, ms) {
      report.domEvents.push({ target: "setTimeout", ms });
      return 1;
    },
    clearTimeout() {},
    setInterval() { return 1; },
    clearInterval() {},
    requestAnimationFrame(fn) { return 1; },
    addEventListener(ev, fn) {
      report.domEvents.push({ target: "window", ev });
      docListeners.push({ ev: `win:${ev}`, fn });
    },
    removeEventListener() {},
    fetch(u, opts) {
      report.fetchedUrls.push({ type: "fetch", u, opts });
      return Promise.resolve({ ok: true, status: 200, text: async () => "", json: async () => ({}) });
    },
    XMLHttpRequest: function () {
      this.open = (m, u) => report.fetchedUrls.push({ type: "xhr", m, u });
      this.setRequestHeader = () => {};
      this.send = () => {};
    },
    open(u) {
      report.fetchedUrls.push({ type: "window.open", u });
    },
    matchMedia: () => ({ matches: false, addListener() {}, addEventListener() {} }),
    getComputedStyle: () => ({ getPropertyValue: () => "" }),
  };

  const winProxy = new Proxy(winTarget, {
    get(t, p) {
      if (p === "window" || p === "self" || p === "top" || p === "parent" || p === "globalThis" || p === "frames") {
        return winProxy;
      }
      logRead("window", p);
      return Reflect.get(t, p);
    },
    set(t, p, val) {
      report.envWrites.push({
        scope: "window",
        prop: String(p),
        valType: typeof val,
        preview: typeof val === "function" ? val.toString().slice(0, 300) : String(val).slice(0, 300),
      });
      t[p] = val;
      return true;
    },
  });
  winTarget.window = winProxy;
  winTarget.self = winProxy;
  winTarget.top = winProxy;
  winTarget.parent = winProxy;
  winTarget.globalThis = winProxy;

  const ctx = vm.createContext(winProxy);
  try {
    vm.runInContext(jsCode, ctx, { timeout: 10000 });
  } catch (e) {
    report.vmRunError = String(e.stack || e);
  }

  // Reconstruct decoded JScrambler string stream from charBuf!
  const decodedStream = charBuf.join("");
  report.decodedStreamLen = decodedStream.length;
  // Split into readable tokens / strings
  const readableChunks = decodedStream.match(/[A-Za-z0-9_./:?&=%#\-]{4,}/g) || [];
  const uniqueDecodedWords = [...new Set(readableChunks)];
  report.decodedKeywords = uniqueDecodedWords.filter((w) =>
    /token|watch|cookie|core|shine|loadon|freex|http|wp-content|hash|hmac|sha|md5|base64|atob|btoa|crypto|subtle|fetch|ajax|href|location|time|date|nonce|secret|key|sign|cimanow|cnvids|embed|download|webdriver|headless|chrome|selenium|phantom|devtools|debugger|canvas|webgl/i.test(
      w
    )
  );
  report.decodedStreamSample = decodedStream.slice(0, 12000);
  report.decodedStreamTail = decodedStream.slice(-12000);

  // Fire DOMContentLoaded & load listeners if registered
  for (const l of docListeners) {
    if (l.ev === "DOMContentLoaded" || l.ev === "win:load" || l.ev === "readystatechange") {
      try { l.fn({ type: l.ev }); } catch (e) { report.listenerErr = String(e.message || e); }
    }
  }

  report.hasGetCoreToken = typeof winTarget._getCoreToken;
  if (typeof winTarget._getCoreToken === "function") {
    try {
      report.tokenVal1 = winTarget._getCoreToken();
      report.tokenVal2 = winTarget._getCoreToken();
    } catch (e) {
      report.tokenCallErr = String(e.stack || e);
    }
  }

  // Simulate clicking `shineAnchor` via document click delegation!
  for (const l of docListeners) {
    if (l.ev === "click" || l.ev === "mousedown" || l.ev === "auxclick") {
      try {
        l.fn({
          type: l.ev,
          target: shineAnchor,
          currentTarget: docTarget,
          preventDefault() { report.envWrites.push({ scope: "click.preventDefault" }); },
          stopPropagation() {},
          stopImmediatePropagation() {},
          isTrusted: true,
          button: 0,
        });
      } catch (e) {
        report.clickErr = String(e.message || e);
      }
    }
  }
  if (typeof shineAnchor._clickFn === "function") {
    try {
      shineAnchor._clickFn({
        type: "click",
        target: shineAnchor,
        currentTarget: shineAnchor,
        preventDefault() { report.envWrites.push({ scope: "shineClick.preventDefault" }); },
        stopPropagation() {},
        isTrusted: true,
      });
    } catch {}
  }

  report.finalShineHref = shineAnchor.href;
  report.finalCookies = cookieStore;
  return report;
}

async function runRealXvfbChrome(proxyUrl, urlsToTest) {
  const chromeBin = ["/usr/bin/google-chrome", "/usr/bin/google-chrome-stable", "/usr/bin/chromium-browser"].find((p) =>
    fs.existsSync(p)
  );
  const xvfbBin = "/usr/bin/xvfb-run";
  if (!chromeBin) return { available: false };

  const chromeProxy = proxyUrl.replace(/^socks5h:\/\//, "socks5://");
  const port = 9231;
  const userDataDir = `/tmp/chrome_xvfb_${Date.now()}`;
  const chromeArgs = [
    "--no-sandbox",
    "--disable-setuid-sandbox",
    "--disable-dev-shm-usage",
    "--disable-blink-features=AutomationControlled",
    "--no-first-run",
    "--no-default-browser-check",
    "--window-size=1920,1080",
    `--proxy-server=${chromeProxy}`,
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
    const netLog = [];
    ws.addEventListener("message", (ev) => {
      const msg = JSON.parse(String(ev.data));
      if (msg.id && pending.has(msg.id)) {
        const { resolve, reject } = pending.get(msg.id);
        pending.delete(msg.id);
        if (msg.error) reject(new Error(msg.error.message));
        else resolve(msg.result);
      } else if (msg.method === "Network.requestWillBeSent") {
        const u = msg.params?.request?.url || "";
        if (!u.endsWith(".png") && !u.endsWith(".jpg") && !u.endsWith(".svg") && !u.endsWith(".css")) {
          netLog.push({
            type: "req",
            url: u,
            method: msg.params.request.method,
            headers: msg.params.request.headers,
            postData: msg.params.request.postData,
          });
        }
      } else if (msg.method === "Network.responseReceived") {
        const u = msg.params?.response?.url || "";
        if (u.includes("cimanow") || u.includes("freex2line") || u.includes("core.php")) {
          netLog.push({
            type: "resp",
            url: u,
            status: msg.params.response.status,
            headers: msg.params.response.headers,
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
        }, 18000);
      });

    // IMPORTANT: Do NOT call `Runtime.enable`! Only `Page.enable` + `Network.enable`!
    await cdp("Page.enable");
    await cdp("Network.enable");
    await cdp("Page.addScriptToEvaluateOnNewDocument", {
      source: `Object.defineProperty(navigator, 'webdriver', { get: () => undefined });`,
    });

    const evalJs = async (expression) => {
      const r = await cdp("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true });
      return r?.result?.value;
    };

    const reports = [];
    for (const itemUrl of urlsToTest) {
      const rep = { itemUrl };
      await cdp("Page.navigate", { url: itemUrl });
      await sleep(5500);

      rep.movieState = await evalJs(`(() => ({
        url: location.href,
        title: document.title,
        webdriver: navigator.webdriver,
        cookie: document.cookie,
        hasTokenFn: typeof window._getCoreToken,
        token1: typeof window._getCoreToken === 'function' ? window._getCoreToken() : null,
        btnsHtml: document.querySelector('ul.btns')?.outerHTML || null,
        allShineLinks: [...document.querySelectorAll('a.shine, .btns a')].map(a => ({ href: a.href, rawHref: a.getAttribute('href'), text: a.innerText.trim() })),
        bodyLen: document.body?.innerHTML?.length || 0,
      }))()`);

      await sleep(1100);
      rep.token2After1s = await evalJs(`typeof window._getCoreToken === 'function' ? window._getCoreToken() : null`);
      rep.cookiesOnMovie = (await cdp("Network.getAllCookies")).cookies;

      // Click the watch button `ul.btns a.shine` inside the page!
      await evalJs(`(() => {
        const a = document.querySelector('ul.btns a.shine, a.shine');
        if (a) {
          a.removeAttribute('target');
          a.click();
          return 'clicked:' + a.href;
        }
        return 'no-shine';
      })()`);
      await sleep(6500);

      rep.afterShineClick = await evalJs(`(() => ({
        url: location.href,
        title: document.title,
        cookie: document.cookie,
        watchTabsCount: document.querySelectorAll('#watch li').length,
        bodySnippet: document.body?.innerText?.slice(0, 600) || '',
        linksAndForms: [...document.querySelectorAll('a[href], form, button, iframe')].slice(0, 20).map(e => e.outerHTML.slice(0, 320)),
      }))()`);

      // If we are on `rm.freex2line.online` or an interstitial, inspect it or wait/click!
      if (!rep.afterShineClick?.watchTabsCount && rep.afterShineClick?.url && !rep.afterShineClick.url.includes("/watching/")) {
        await sleep(5000);
        rep.afterExtraWait = await evalJs(`(() => ({
          url: location.href,
          title: document.title,
          watchTabsCount: document.querySelectorAll('#watch li').length,
          bodySnippet: document.body?.innerText?.slice(0, 600) || '',
        }))()`);
      }

      // Now if `#watch li` is present, extract ALL servers + switch embeds + download links!
      const hasWatch = await evalJs(`document.querySelectorAll('#watch li').length > 0`);
      if (hasWatch) {
        rep.watchingData = await evalJs(`(async () => {
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
              switchResults.push({ name: li.innerText.trim(), idx, id, status: r.status, usedToken: tok, resp: txt.slice(0, 600) });
            } catch (e) {
              switchResults.push({ name: li.innerText.trim(), idx, id, err: String(e) });
            }
          }
          return { tabs, downloads, switchResults };
        })()`);
      }

      rep.cookiesFinal = (await cdp("Network.getAllCookies")).cookies;
      reports.push(rep);
    }

    ws.close();
    return { available: true, usedXvfb: fs.existsSync(xvfbBin), reports, netLog: netLog.slice(0, 100) };
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

  // 1. Verify `<section aria-label="posts">` inner HTML on category & search pages!
  const srch = await smartGet("https://cimanow.cc/?s=Lupin", proxies);
  const postsSection = srch.text.match(/<section\b[^>]*aria-label=["']posts["'][^>]*>[\s\S]*?<\/section>/i)?.[0] || "";
  out.searchPostsSectionSample = postsSection.slice(0, 4500);

  const cat2 = await smartGet(
    "https://cimanow.cc/category/%d8%a7%d9%81%d9%84%d8%a7%d9%85-%d8%a7%d8%ac%d9%86%d8%a8%d9%8a%d8%a9/page/2/",
    proxies
  );
  const cat2PostsSection = cat2.text.match(/<section\b[^>]*aria-label=["']posts["'][^>]*>[\s\S]*?<\/section>/i)?.[0] || "";
  out.cat2PostsSectionSample = cat2PostsSection.slice(0, 4500);

  // 2. Fetch `0CYA6X1KhKIS.js?v=3.1` and run full VM spy + JScrambler decoded stream capture!
  const movieUrl = "https://cimanow.cc/%d9%81%d9%8a%d9%84%d9%85-fall-2-deadpoint-2026-%d9%85%d8%aa%d8%b1%d8%ac%d9%85/";
  const epUrl = "https://cimanow.cc/%d9%85%d8%b3%d9%84%d8%b3%d9%84-lupin-%d8%ac4-%d8%ad1-%d9%85%d8%aa%d8%b1%d8%ac%d9%85%d8%a9/";
  const jsUrl = "https://cimanow.cc/wp-content/themes/Cima%20Now%20New/Assets/js/0CYA6X1KhKIS.js?v=3.1";
  const jsResp = await smartGet(jsUrl, proxies, { referer: movieUrl });
  out.vm0CYAReport = analyzeAndRun0CYAInVm(jsResp.text, movieUrl);

  // 3. Run real Xvfb non-headless Chrome on Movie + Episode!
  out.xvfbChrome = await runRealXvfbChrome(proxies[0], [movieUrl, epUrl]);

  fs.writeFileSync("cimanow_recon.json", JSON.stringify(out, null, 2));
  console.log("Wrote v5 recon report, bytes:", fs.statSync("cimanow_recon.json").size);
}

main();
